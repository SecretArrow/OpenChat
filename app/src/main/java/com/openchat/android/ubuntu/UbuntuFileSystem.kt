package com.openchat.android.ubuntu

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Filesystem layout of the Ubuntu userspace on the host (spec §3, §23) plus
 * the anti path-traversal resolver used by every file operation.
 *
 * Layout under the app's private storage (`context.filesDir`):
 *  - `ubuntu/rootfs`  — extracted Ubuntu base rootfs (bash, apt, /root/workspaces…)
 *  - `ubuntu/bin/proot` — proot binary (bionic bundle from APK assets on
 *                         x86_64, hash-pinned download on other ABIs)
 *  - `ubuntu/lib`     — proot support files for the bionic build: ptrace
 *                       loader, loader32, libtalloc.so.2, libandroid-shmem.so
 *  - `ubuntu/cache`   — downloaded tarballs (reused by Repair)
 *  - `ubuntu/tmp`     — PROOT_TMP_DIR: proot's own scratch space (glue rootfs,
 *                       temporary files). /tmp is not writable for apps on
 *                       Android, so proot MUST be pointed here explicitly.
 *  - `ubuntu/staging` — extraction target while importing a userspace backup
 */
object UbuntuFileSystem {

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu/rootfs")

    fun prootBin(context: Context): File = File(context.filesDir, "ubuntu/bin/proot")

    /**
     * Support files for the bionic (Termux-built) proot used on x86_64: the
     * ptrace loader, loader32, libtalloc.so.2 and libandroid-shmem.so. All
     * copied from APK assets by [ProotRunner] with SHA-256 verification.
     */
    fun prootLibDir(context: Context): File = File(context.filesDir, "ubuntu/lib")

    fun cacheDir(context: Context): File = File(context.filesDir, "ubuntu/cache")

    /**
     * proot's host-side scratch directory (`PROOT_TMP_DIR`). proot creates its
     * temporary files and the glue rootfs here at startup — the default `/tmp`
     * is not writable for app processes on Android, which used to fail every
     * exec with `can't create temporary directory: Permission denied`.
     * Created on demand; idempotent.
     */
    fun prootTmpDir(context: Context): File = File(context.filesDir, "ubuntu/tmp").apply { mkdirs() }

    /** Staging directory an imported rootfs is extracted into before validation. */
    fun importStagingDir(context: Context): File = File(context.filesDir, "ubuntu/staging")

    /** Where all workspace directories live inside the rootfs. */
    fun workspaceRoot(rootfs: File): File = File(rootfs, "root/workspaces")

    /**
     * True when the proot binary is complete for the device ABI. The bionic
     * x86_64 build additionally needs libtalloc and the loader (its DT_NEEDED
     * entries); the static glibc builds on other ABIs are single files. A
     * persisted READY without these files is not "ready" (§ no fake ready).
     */
    fun prootComplete(context: Context): Boolean {
        if (!prootBin(context).isFile) return false
        // Bionic-bundle ABIs (x86_64 since v0.1.11, arm64-v8a since v0.1.12)
        // also need the ptrace loaders + support libs on disk.
        if (Build.SUPPORTED_ABIS[0] == "x86_64" || Build.SUPPORTED_ABIS[0] == "arm64-v8a") {
            val lib = prootLibDir(context)
            return File(lib, "libtalloc.so.2").isFile && File(lib, "loader").isFile
        }
        return true
    }

    /**
     * True when a usable rootfs exists on disk (bash is present) — the gate
     * for Export. Deliberately independent of the persisted state so a broken
     * installation can still be backed up before a Reset.
     */
    fun hasRootfs(context: Context): Boolean = File(rootfsDir(context), "bin/bash").isFile

    /**
     * Resolves [rel] below [root] (anti path-traversal, §23).
     *
     * - "" / "." → [root] itself
     * - leading "/" rejected (absolute paths are never allowed)
     * - any ".." segment that would escape [root] rejected; "a/../b" → "b"
     * - final containment check on canonical paths (catches symlink escapes
     *   when the target already exists on disk)
     *
     * @return the resolved file/directory, or a failure with the reason.
     */
    fun safeResolve(root: File, rel: String): Result<File> {
        if (root.path.isBlank()) {
            // File("").absolutePath silently resolves to the CWD — must not pass.
            return Result.failure(IllegalArgumentException("Root path must not be blank"))
        }
        val trimmed = rel.trim()
        if (trimmed.startsWith("/")) {
            return Result.failure(
                IllegalArgumentException("Absolute paths are not allowed (got '$rel')"),
            )
        }
        val stack = ArrayDeque<String>()
        for (segment in trimmed.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." ->
                    if (stack.isEmpty()) {
                        return Result.failure(
                            IllegalArgumentException("Path escapes the allowed root (got '$rel')"),
                        )
                    } else {
                        stack.removeLast()
                    }
                else -> stack.addLast(segment)
            }
        }
        var resolved = root
        for (part in stack) resolved = File(resolved, part)

        // Canonical containment check (existing symlinks must not escape the root).
        val canonicalRoot = runCatching { root.canonicalFile }.getOrDefault(root)
        val canonicalResolved = runCatching { resolved.canonicalFile }.getOrDefault(resolved)
        val rootPath = canonicalRoot.absolutePath.trimEnd('/')
        val resolvedPath = canonicalResolved.absolutePath
        if (resolvedPath != rootPath && !resolvedPath.startsWith("$rootPath/")) {
            return Result.failure(
                IllegalArgumentException("Path resolves outside the allowed root (got '$rel')"),
            )
        }
        return Result.success(resolved)
    }

    /**
     * True when a destructive Files-screen operation on this rootfs-relative path
     * must be refused (least privilege, spec §23): everything outside the user
     * areas (/root, /home, /tmp, /var, /opt, /srv) is protected, i.e. /bin /boot
     * /dev /etc /lib /lib64 /media /mnt /proc /run /sbin /sys /usr.
     */
    fun isProtectedRootfsPath(rel: String): Boolean {
        val first = rel.trim().trimStart('/')
            .split('/')
            .firstOrNull { it.isNotEmpty() }
            ?.lowercase()
            ?: return false
        return first in PROTECTED_ROOTFS_DIRS
    }

    private val PROTECTED_ROOTFS_DIRS: Set<String> = setOf(
        "bin", "boot", "dev", "etc", "lib", "lib64",
        "media", "mnt", "proc", "run", "sbin", "sys", "usr",
    )
}
