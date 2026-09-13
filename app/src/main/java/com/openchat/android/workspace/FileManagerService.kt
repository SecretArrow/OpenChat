package com.openchat.android.workspace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.ubuntu.UbuntuFileSystem
import java.io.File
import java.io.IOException

/**
 * Throwable carrier for a structured [ErrorInfo] produced by the Files domain
 * (stdlib Result.failure requires a Throwable — the info rides inside).
 */
class FileOpException(val info: ErrorInfo) : Exception(info.detail)

/** Storage domains browsable in the Files screen (spec §23). */
enum class FileDomain { APP_DATA, UBUNTU_ROOTFS, WORKSPACE, SHARED }

/**
 * One row of a file listing. [file] is the real host-side location (null only
 * for pseudo-entries such as the SHARED domain, which is SAF-only).
 */
class FileEntry(
    val file: java.io.File?,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val domain: FileDomain,
    val relativePath: String,
)

/**
 * Real file operations over the app's storage domains (spec §23):
 *
 *  - APP_DATA: the app's private filesDir (fully read/write).
 *  - UBUNTU_ROOTFS: the Ubuntu rootfs — destructive ops on protected system
 *    paths (/bin, /etc, /usr, …) are refused (least privilege); everything is
 *    resolved through [UbuntuFileSystem.safeResolve] (anti path-traversal).
 *  - WORKSPACE: /root/workspaces inside the rootfs (null until Ubuntu is ready).
 *  - SHARED: null root — the UI must use the system picker (SAF) and then the
 *    [importFromUri]/[exportToUri] bridges; direct listing is refused honestly.
 */
class FileManagerService(private val context: Context) {

    // ------------------------------------------------------------------ roots

    /** The host directory backing [domain], or null when unavailable (SHARED). */
    fun rootFor(domain: FileDomain): File? = when (domain) {
        FileDomain.APP_DATA -> context.filesDir
        FileDomain.UBUNTU_ROOTFS -> UbuntuFileSystem.rootfsDir(context)
        FileDomain.WORKSPACE ->
            UbuntuFileSystem.rootfsDir(context).takeIf { it.isDirectory }
                ?.let { UbuntuFileSystem.workspaceRoot(it) }
        FileDomain.SHARED -> null
    }

    // ------------------------------------------------------------------- list

    /**
     * Lists [relativePath] (directories first, then case-insensitive by name).
     * A plain file yields a single-entry list. SHARED refuses with guidance.
     */
    fun list(domain: FileDomain, relativePath: String): Result<List<FileEntry>> {
        if (domain == FileDomain.SHARED) {
            return Result.failure(FileOpException(sharedError()))
        }
        val root = rootFor(domain) ?: return Result.failure(FileOpException(unavailableError(domain)))
        val target = safeResolveOr(root, relativePath)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid path"))) }
        if (!target.exists()) {
            return Result.failure(FileOpException(notFoundError(relativePath)))
        }
        if (target.isFile) {
            return Result.success(listOf(entryOf(target, target.name, false, domain, relativePath)))
        }
        val relDir = relativePath.trim().trimStart('/')
        val entries = target.listFiles()?.map { child ->
            val rel = if (relDir.isBlank()) child.name else "$relDir/${child.name}"
            entryOf(child, child.name, child.isDirectory, domain, rel)
        } ?: emptyList()
        return Result.success(
            entries.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() }),
        )
    }

    // ----------------------------------------------------------- create/rename

    /** Creates an empty file named [name] inside [relDir]. */
    fun createFile(domain: FileDomain, relDir: String, name: String): Result<FileEntry> =
        createEntry(domain, relDir, name, directory = false)

    /** Creates a directory named [name] inside [relDir]. */
    fun createDirectory(domain: FileDomain, relDir: String, name: String): Result<FileEntry> =
        createEntry(domain, relDir, name, directory = true)

    private fun createEntry(domain: FileDomain, relDir: String, name: String, directory: Boolean): Result<FileEntry> {
        if (domain == FileDomain.SHARED) return Result.failure(FileOpException(sharedError()))
        val root = rootFor(domain) ?: return Result.failure(FileOpException(unavailableError(domain)))
        val valid = validName(name).getOrElse { return Result.failure(it) }
        val parent = safeResolveOr(root, relDir)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid path"))) }
        if (!parent.isDirectory) return Result.failure(FileOpException(notFoundError(relDir)))
        val target = File(parent, valid)
        if (target.exists()) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Already exists",
                    detail = "“$valid” already exists in ${relDir.ifBlank { "/" }}.",
                    suggestions = listOf("Pick a different name", "Delete the existing entry first"),
                ),
            ))
        }
        val created = if (directory) target.mkdirs() else runCatching { target.createNewFile() }.getOrDefault(false)
        if (!created || !target.exists()) {
            return Result.failure(FileOpException(ioError("create", target)))
        }
        return Result.success(entryOf(target, valid, directory, domain, joinRel(relDir, valid)))
    }

    /** Renames within the same parent directory. */
    fun rename(domain: FileDomain, relPath: String, newName: String): Result<FileEntry> {
        if (domain == FileDomain.SHARED) return Result.failure(FileOpException(sharedError()))
        val root = rootFor(domain) ?: return Result.failure(FileOpException(unavailableError(domain)))
        val valid = validName(newName).getOrElse { return Result.failure(it) }
        val src = safeResolveOr(root, relPath)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid path"))) }
        if (!src.exists()) return Result.failure(FileOpException(notFoundError(relPath)))
        destructiveFailure(domain, relPath)?.let { return Result.failure(FileOpException(it)) }
        val dst = File(src.parentFile, valid)
        if (dst.exists()) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Already exists",
                    detail = "“$valid” already exists next to the renamed item.",
                    suggestions = listOf("Pick a different name"),
                ),
            ))
        }
        if (!src.renameTo(dst)) {
            return Result.failure(FileOpException(ioError("rename", src)))
        }
        val parentRel = relPath.trim().trimStart('/').substringBeforeLast('/', missingDelimiterValue = "")
        return Result.success(entryOf(dst, valid, dst.isDirectory, domain, joinRel(parentRel, valid)))
    }

    // ----------------------------------------------------------- delete/move

    /** Deletes a file or directory (recursive). Protected rootfs paths refused. */
    fun delete(domain: FileDomain, relPath: String): Result<Unit> {
        if (domain == FileDomain.SHARED) return Result.failure(FileOpException(sharedError()))
        val root = rootFor(domain) ?: return Result.failure(FileOpException(unavailableError(domain)))
        val src = safeResolveOr(root, relPath)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid path"))) }
        if (!src.exists()) return Result.failure(FileOpException(notFoundError(relPath)))
        if (src.absolutePath == root.absolutePath) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Refused",
                    detail = "The storage root itself cannot be deleted.",
                    suggestions = listOf("Delete individual files or folders instead"),
                ),
            ))
        }
        destructiveFailure(domain, relPath)?.let { return Result.failure(FileOpException(it)) }
        if (!src.deleteRecursively()) {
            return Result.failure(FileOpException(ioError("delete", src)))
        }
        return Result.success(Unit)
    }

    /** Copies a file or a directory tree across domains. */
    fun copy(srcD: FileDomain, srcRel: String, dstD: FileDomain, dstRel: String): Result<FileEntry> {
        if (srcD == FileDomain.SHARED || dstD == FileDomain.SHARED) return Result.failure(FileOpException(sharedError()))
        val srcRoot = rootFor(srcD) ?: return Result.failure(FileOpException(unavailableError(srcD)))
        val dstRoot = rootFor(dstD) ?: return Result.failure(FileOpException(unavailableError(dstD)))
        val src = safeResolveOr(srcRoot, srcRel)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid source path"))) }
        if (!src.exists()) return Result.failure(FileOpException(notFoundError(srcRel)))
        if (src.absolutePath == srcRoot.absolutePath) {
            return Result.failure(FileOpException(badPath("Copying the whole storage root is not supported")))
        }
        val dst = safeResolveOr(dstRoot, dstRel)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid target path"))) }
        if (dst.exists()) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Target already exists",
                    detail = "${dst.name} already exists at the destination.",
                    suggestions = listOf("Delete or rename the target first"),
                ),
            ))
        }
        dstWriteGuard(dstD, dstRel)?.let { return Result.failure(FileOpException(it)) }
        val copied = runCatching { copyRecursive(src, dst) }
        if (copied.isFailure || !dst.exists()) {
            return Result.failure(FileOpException(ioError("copy", src)))
        }
        return Result.success(entryOf(dst, dst.name, dst.isDirectory, dstD, dstRel.trim().trimStart('/')))
    }

    /** Moves (rename when possible, copy+delete across domains). */
    fun move(srcD: FileDomain, srcRel: String, dstD: FileDomain, dstRel: String): Result<FileEntry> {
        if (srcD == FileDomain.SHARED || dstD == FileDomain.SHARED) return Result.failure(FileOpException(sharedError()))
        val srcRoot = rootFor(srcD) ?: return Result.failure(FileOpException(unavailableError(srcD)))
        val dstRoot = rootFor(dstD) ?: return Result.failure(FileOpException(unavailableError(dstD)))
        val src = safeResolveOr(srcRoot, srcRel)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid source path"))) }
        if (!src.exists()) return Result.failure(FileOpException(notFoundError(srcRel)))
        if (src.absolutePath == srcRoot.absolutePath) {
            return Result.failure(FileOpException(badPath("Moving the whole storage root is not supported")))
        }
        destructiveFailure(srcD, srcRel)?.let { return Result.failure(FileOpException(it)) }
        val dst = safeResolveOr(dstRoot, dstRel)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid target path"))) }
        if (dst.exists()) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Target already exists",
                    detail = "${dst.name} already exists at the destination.",
                    suggestions = listOf("Delete or rename the target first"),
                ),
            ))
        }
        dstWriteGuard(dstD, dstRel)?.let { return Result.failure(FileOpException(it)) }
        val moved = src.renameTo(dst) || runCatching {
            copyRecursive(src, dst)
            if (!dst.exists()) throw IOException("copy produced no target")
            src.deleteRecursively()
            true
        }.getOrDefault(false)
        if (!moved || !dst.exists()) {
            return Result.failure(FileOpException(ioError("move", src)))
        }
        return Result.success(entryOf(dst, dst.name, dst.isDirectory, dstD, dstRel.trim().trimStart('/')))
    }

    // -------------------------------------------------------------- text I/O

    /** Reads a text file for the editor (hard limit 2 MB, spec §23). */
    fun readText(target: File): Result<String> {
        if (!target.isFile) {
            return Result.failure(FileOpException(
                if (target.exists()) {
                    ErrorInfo(
                        title = "Not a file",
                        detail = "${target.name} is a directory — open a file instead.",
                        suggestions = listOf("Pick a file to edit"),
                    )
                } else {
                    notFoundError(target.name)
                },
            ))
        }
        if (target.length() > MAX_EDITOR_BYTES) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "File too large for editor",
                    detail = "${target.name} is ${target.length() / 1024} KB — the editor supports up to 2 MB.",
                    suggestions = listOf("Use the Terminal (e.g. `less`, `tail`, `grep`) for big files"),
                ),
            ))
        }
        return runCatching { target.readText() }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(FileOpException(ioError("read", target))) },
        )
    }

    /** Writes [content] to [target] (parent dirs are created when missing). */
    fun writeText(target: File, content: String): Result<Unit> {
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content)
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(FileOpException(ioError("write", target))) },
        )
    }

    // ------------------------------------------------------------- SAF bridge

    /**
     * Imports a SAF-picked document into [relDir] of [domain] under its real
     * display name (fallback `imported_<timestamp>`); an existing file with the
     * same name is overwritten (import semantics).
     */
    fun importFromUri(domain: FileDomain, relDir: String, uri: Uri): Result<FileEntry> {
        if (domain == FileDomain.SHARED) return Result.failure(FileOpException(sharedError()))
        val root = rootFor(domain) ?: return Result.failure(FileOpException(unavailableError(domain)))
        val dir = safeResolveOr(root, relDir)
            .getOrElse { return Result.failure(FileOpException(badPath(it.message ?: "Invalid path"))) }
        if (!dir.isDirectory) return Result.failure(FileOpException(notFoundError(relDir)))
        val resolver = context.contentResolver
        val name = queryDisplayName(uri) ?: "imported_${System.currentTimeMillis()}"
        val safe = name.replace('/', '_').ifBlank { "imported_${System.currentTimeMillis()}" }
        val target = File(dir, safe)
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Import failed",
                    detail = "The system picker did not provide the file content.",
                    suggestions = listOf("Pick the file again", "Try a different file"),
                ),
            ))
        } catch (e: Exception) {
            return Result.failure(FileOpException(
                ErrorInfo(
                    title = "Import failed",
                    detail = e.message ?: e.javaClass.simpleName,
                    suggestions = listOf("Pick the file again", "Check free storage"),
                ),
            ))
        }
        return Result.success(entryOf(target, safe, false, domain, joinRel(relDir, safe)))
    }

    /** Copies a real file out to a SAF-picked destination URI. */
    fun exportToUri(file: File, uri: Uri): Result<Unit> {
        if (!file.isFile) return Result.failure(FileOpException(notFoundError(file.name)))
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                Result.success(Unit)
            } ?: Result.failure(FileOpException(
                ErrorInfo(
                    title = "Export failed",
                    detail = "The system picker did not provide a writable destination.",
                    suggestions = listOf("Pick the destination again"),
                ),
            ))
        } catch (e: Exception) {
            Result.failure(FileOpException(
                ErrorInfo(
                    title = "Export failed",
                    detail = e.message ?: e.javaClass.simpleName,
                    suggestions = listOf("Pick the destination again", "Check free storage on the target"),
                ),
            ))
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Recursive stream copy used by [copy] and as the cross-domain fallback of
     * [move] (directories are walked depth-first; file contents are streamed —
     * never renamed across mount points blindly).
     */
    private fun copyRecursive(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            val children = src.listFiles() ?: return true
            var ok = true
            for (child in children) {
                ok = copyRecursive(child, File(dst, child.name)) && ok
            }
            return ok
        }
        dst.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        return runCatching {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    private fun entryOf(file: File, name: String, isDir: Boolean, domain: FileDomain, rel: String): FileEntry =
        FileEntry(
            file = file,
            name = name,
            isDir = isDir,
            size = if (isDir) 0L else runCatching { file.length() }.getOrDefault(0L),
            lastModified = runCatching { file.lastModified() }.getOrDefault(0L),
            domain = domain,
            relativePath = rel,
        )

    private fun safeResolveOr(root: File, rel: String): Result<File> = UbuntuFileSystem.safeResolve(root, rel)

    /** Blank/"/"-containing/dot-segment names are refused. */
    private fun validName(name: String): Result<String> = when {
        name.isBlank() -> Result.failure(FileOpException(badPath("The name must not be empty")))
        name.contains('/') || name.contains('\\') -> Result.failure(FileOpException(badPath("The name must not contain slashes")))
        name == "." || name == ".." -> Result.failure(FileOpException(badPath("“.” and “..” are not valid names")))
        else -> Result.success(name.trim())
    }

    private fun joinRel(relDir: String, name: String): String {
        val dir = relDir.trim().trimStart('/')
        return if (dir.isBlank()) name else "$dir/$name"
    }

    /**
     * Guard for destructive operations (delete/rename/move source) in the rootfs:
     * protected system paths (/bin, /etc, /usr, …) are refused (spec §23).
     */
    private fun destructiveFailure(domain: FileDomain, relPath: String): ErrorInfo? {
        if (domain != FileDomain.UBUNTU_ROOTFS) return null
        if (!UbuntuFileSystem.isProtectedRootfsPath(relPath)) return null
        return ErrorInfo(
            title = "Protected system path",
            detail = "“/$relPath” is part of the Ubuntu system — modifying it could break the userspace.",
            causes = listOf("Least privilege: only /root, /home, /tmp, /var, /opt, /srv are user-writable"),
            suggestions = listOf("Copy the file to /root first if you really need a modified copy"),
        )
    }

    /** Guard for writes into protected rootfs paths (copy/move destination). */
    private fun dstWriteGuard(domain: FileDomain, relPath: String): ErrorInfo? = destructiveFailure(domain, relPath)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------- error shapes

    private fun sharedError(): ErrorInfo = ErrorInfo(
        title = "Shared storage uses the system document picker",
        detail = "Android does not allow apps to browse shared storage directly (scoped storage). " +
            "Open Chat brings files in and out through the system document picker (SAF) instead.",
        suggestions = listOf("Use Import/Export buttons — SAF, no broad storage permissions"),
    )

    private fun unavailableError(domain: FileDomain): ErrorInfo = ErrorInfo(
        title = when (domain) {
            FileDomain.WORKSPACE -> "Workspaces need Ubuntu installed"
            FileDomain.UBUNTU_ROOTFS -> "Ubuntu rootfs is not available"
            else -> "Storage unavailable"
        },
        detail = when (domain) {
            FileDomain.WORKSPACE ->
                "The /root/workspaces root does not exist yet — workspaces live inside the Ubuntu rootfs."
            FileDomain.UBUNTU_ROOTFS ->
                "The Ubuntu rootfs directory does not exist on this device."
            else -> "The ${domain.name.lowercase()} storage does not exist on this device."
        },
        causes = listOf("Ubuntu is not installed yet", "The rootfs was cleared"),
        suggestions = listOf("Install Ubuntu in Settings → Ubuntu first"),
        repairAction = com.openchat.android.core.model.RepairAction.INSTALL_UBUNTU,
    )

    private fun notFoundError(rel: String): ErrorInfo = ErrorInfo(
        title = "Not found",
        detail = "“$rel” does not exist (it may have been deleted).",
        suggestions = listOf("Refresh the file list"),
    )

    private fun badPath(detail: String): ErrorInfo = ErrorInfo(
        title = "Invalid path",
        detail = detail,
        suggestions = listOf("Go back to the storage root"),
    )

    private fun ioError(op: String, f: File): ErrorInfo = ErrorInfo(
        title = "File operation failed",
        detail = "Could not $op ${f.name} (I/O error, storage full or permission denied).",
        suggestions = listOf("Check free storage in Settings → Storage", "Retry the operation"),
    )

    companion object {
        /** Hard editor size limit (2 MB, spec §23). */
        const val MAX_EDITOR_BYTES = 2_000_000L
    }
}
