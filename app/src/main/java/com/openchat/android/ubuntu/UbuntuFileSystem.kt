package com.openchat.android.ubuntu

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
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
 *  - `ubuntu/guest-tmp` — bound over the guest's /tmp on every exec/session
 *                         (vendor Android builds ship a host /tmp the app
 *                         cannot write, which broke apt signature checks)
 *  - `ubuntu/guest-shm` — bound over the guest's /dev/shm (same class)
 *  - `ubuntu/staging` — extraction target while importing a userspace backup
 */
object UbuntuFileSystem {

    private const val TAG_FS = "OpenChat/UbuntuFS"

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

    /**
     * Host-side directory bound over the guest's `/tmp` on EVERY proot exec
     * and session (see [ProotRunner.buildSessionSpec] for why the bind is
     * required). Created on demand; idempotent — [buildSessionSpec] relies on
     * that so even a rootfs installed by an older app version gets a working
     * /tmp on the next exec.
     */
    fun guestTmpDir(context: Context): File = File(context.filesDir, "ubuntu/guest-tmp").apply { mkdirs() }

    /**
     * Host-side directory bound over the guest's `/dev/shm` (POSIX shm_open,
     * python multiprocessing). Stock Android has no /dev/shm and vendor
     * builds that do ship one are not app-writable — same failure class as
     * the /tmp bind this pairs with.
     */
    fun guestShmDir(context: Context): File = File(context.filesDir, "ubuntu/guest-shm").apply { mkdirs() }

    /**
     * Host-side guarantee that guest temp-file directories exist and are
     * usable (idempotent, called at configure/import/repair and from the
     * apt temp-file self-healing path):
     *
     *  - the host bind directories ([guestTmpDir], [guestShmDir]) — created
     *    and chmod 01777 so the guest sees a real sticky world-writable /tmp;
     *  - the rootfs's own `/tmp` and `/var/tmp` — mkdirs + chmod 01777 as the
     *    fallback layer (used whenever a bind is skipped, e.g. by an older
     *    app build or a proot that refuses a bind source);
     *  - apt's `partial` download directories, which some minimal bases ship
     *    without (apt then refuses every fetch with a different, equally
     *    confusing error).
     *
     * chmod is best-effort: the app uid owns every directory involved, so a
     * failed chmod never blocks a working setup (mkdirs is the essential part).
     */
    fun ensureGuestTmpDirs(context: Context, rootfs: File) {
        val hostDirs = listOf(guestTmpDir(context), guestShmDir(context))
        val guestDirs = listOf(File(rootfs, "tmp"), File(rootfs, "var/tmp"))
        for (dir in hostDirs + guestDirs) {
            dir.mkdirs()
            try {
                Os.chmod(dir.absolutePath, 1023 /* octal 01777: sticky + rwxrwxrwx */)
            } catch (e: Exception) {
                Log.w(TAG_FS, "chmod 01777 ${dir.absolutePath} failed (non-fatal, app uid owns it): ${e.message}")
            }
        }
        // apt refuses every fetch when its partial dirs are missing.
        for (rel in listOf("var/lib/apt/lists/partial", "var/cache/apt/archives/partial")) {
            File(rootfs, rel).mkdirs()
        }
    }

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
        val rootPath = canonicalRoot.a