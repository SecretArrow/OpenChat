package com.openchat.android.ubuntu

import android.content.Context
import java.io.File

/**
 * Filesystem layout of the Ubuntu userspace on the host (spec §3, §23) plus
 * the anti path-traversal resolver used by every file operation.
 *
 * Layout under the app's private storage (`context.filesDir`):
 *  - `ubuntu/rootfs`  — extracted Ubuntu base rootfs (bash, apt, /root/workspaces…)
 *  - `ubuntu/bin/proot` — static proot binary downloaded per ABI
 *  - `ubuntu/cache`   — downloaded tarballs (reused by Repair)
 */
object UbuntuFileSystem {

    fun rootfsDir(context: Context): File = File(context.filesDir, "ubuntu/rootfs")

    fun prootBin(context: Context): File = File(context.filesDir, "ubuntu/bin/proot")

    fun cacheDir(context: Context): File = File(context.filesDir, "ubuntu/cache")

    /** Where all workspace directories live inside the rootfs. */
    fun workspaceRoot(rootfs: File): File = File(rootfs, "root/workspaces")

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
