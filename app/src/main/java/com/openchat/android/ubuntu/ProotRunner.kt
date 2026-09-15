package com.openchat.android.ubuntu

import android.content.Context
import android.os.Build
import android.util.Log
import com.openchat.android.core.net.Http
import com.openchat.android.core.storage.SettingsStore
import com.openchat.android.core.util.Errors
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Shared checksum helpers for the runtime layer (streaming SHA-256, hex encoding).
 */
internal object Checksums {

    /** SHA-256 of a file, streamed (no full in-memory read). */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }
}

/** Lowercase hex encoding of a digest (top-level so every runtime file can use it). */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Provides the proot binary used by every session and exec in the app (spec §4)
 * and builds the real proot argv/env.
 *
 * ABI policy:
 *  - x86_64: **bionic proot** (Termux build, proot-me master snapshot, NDK)
 *    copied from APK assets with pinned SHA-256. Static glibc proot builds
 *    (5.3.0 and 5.4.1 alike) die with SIGSYS (exit 159) on Android emulators:
 *    their modern libc makes syscalls at startup (faccessat2, rseq — the
 *    5.4.1 binary literally contains "glibc.pthread.rseq") that the zygote
 *    seccomp allowlist answers with SIGKILL_THREAD, while the identical
 *    binary+rootfs+env runs perfectly outside the filter (run-as: apt fetches
 *    27.3 MB, RC=0). Bionic never issues those syscalls; this is the same
 *    binary proot-distro uses on Android every day. Support files (ptrace
 *    loader, loader32, libtalloc.so.2, libandroid-shmem.so) ship alongside.
 *  - arm64/armhf: static glibc builds from proot-me releases (hash-pinned
 *    download) — verified working on real devices; 5.3.0-aarch64 contains no
 *    rseq and modern platform policies allow the rest.
 *  - `prootUrlOverride` (advanced settings) still replaces the main binary on
 *    any ABI (SHA check skipped, warning logged) — the x86_64 support files
 *    are installed from assets either way and simply go unused by a static
 *    override build.
 */
class ProotRunner(
    private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * Ensures the proot binary (and, on x86_64, its bionic support files)
     * exists and is executable. x86_64 installs from APK assets; other ABIs
     * download from the pinned release (or the user's `prootUrlOverride` —
     * in that case the SHA-256 check is skipped and a warning is logged).
     */
    suspend fun ensureProot(): Result<File> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val bin = UbuntuFileSystem.prootBin(context)
        if (UbuntuFileSystem.prootComplete(context) && bin.canExecute()) {
            return@withContext Result.success(bin)
        }

        val abi = Build.SUPPORTED_ABIS[0]
        val override = settings.settings.value.prootUrlOverride

        if (abi == "x86_64") {
            installBionicProot(bin).getOrElse { return@withContext Result.failure(it) }
            if (override == null) return@withContext Result.success(bin)
            // Override set: the download below replaces the asset binary.
        }

        val (url, sha) = when (abi) {
            "arm64-v8a" -> PROOT_AARCH64_URL to PROOT_AARCH64_SHA256
            "armeabi-v7a" -> PROOT_ARM_URL to PROOT_ARM_SHA256
            "x86_64" -> override!! to PROOT_X86_64_SHA256
            else -> return@withContext Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Unsupported ABI for proot: '$abi' — this APK supports arm64-v8a, armeabi-v7a, x86_64"),
                ),
            )
        }
        val verify = override == null
        if (!verify) {
            Log.w(TAG, "proot URL override active — SHA-256 verification skipped: $override")
        }

        val cache = UbuntuFileSystem.cacheDir(context).apply { mkdirs() }
        val tmp = File(cache, "proot.bin")
        val binDir = bin.parentFile
        if (binDir != null && !binDir.isDirectory) binDir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            Http.client.newCall(Http.newRequest(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} while downloading proot")
                val body = response.body ?: throw IOException("Empty response body while downloading proot")
                body.byteStream().use { ins ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            digest.update(buf, 0, n)
                        }
                    }
                }
            }
            if (verify) {
                val actual = digest.digest().toHex()
                if (!actual.equals(sha, ignoreCase = true)) {
                    throw IOException(
                        "proot SHA-256 mismatch — expected $sha, got $actual " +
                            "(download corrupt or tampered); deleting the partial file",
                    )
                }
            }
            bin.delete()
            if (!tmp.renameTo(bin)) {
                tmp.copyTo(bin, overwrite = true)
                tmp.delete()
            }
            bin.setExecutable(true)
            Log.i(TAG, "proot ready at ${bin.absolutePath}")
            Result.success(bin)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("proot download failed: ${e.message ?: e.javaClass.simpleName}")),
            )
        }
    }

    /**
     * Builds the real proot session/exec spec:
     * argv = [proot, --kill-on-exit, -0, -w, cwd, -R, rootfs] + cmd,
     * env  = [baseEnv] + caller extras (caller overrides win).
     */
    fun buildSessionSpec(
        cwd: String,
        cmd: List<String>,
        env: Map<String, String>,
        rootfs: File,
    ): SessionSpec {
        val argv = mutableListOf(
            UbuntuFileSystem.prootBin(context).absolutePath,
            "--kill-on-exit",
            "-0",
            "-w",
            cwd,
            "-R",
            rootfs.absolutePath,
        )
        argv.addAll(cmd)
        val merged = mergeEnv(
            baseEnv(UbuntuFileSystem.prootTmpDir(context).absolutePath, bionicLibDir()),
            env,
        )
        return SessionSpec(argv = argv, cwd = cwd, env = merged)
    }

    /**
     * Environment needed to run the proot binary **directly on the host**
     * (diagnostics). The bionic x86_64 build resolves libtalloc/libandroid-shmem
     * from our lib dir — the app process env does not carry it.
     */
    fun hostEnvExtras(): Map<String, String> = bionicEnvExtras(bionicLibDir())

    /** Lib dir passed to [baseEnv] for the bionic build, null elsewhere. */
    private fun bionicLibDir(): String? =
        if (Build.SUPPORTED_ABIS[0] == "x86_64") {
            UbuntuFileSystem.prootLibDir(context).absolutePath
        } else {
            null
        }

    /**
     * Copies the bionic proot bundle (binary, ptrace loader, loader32,
     * libtalloc, libandroid-shmem) from APK assets into the app's files dir.
     * Every file is verified against its pinned SHA-256 after the copy —
     * bundled bytes are trusted only once verified (§19, same rule as the
     * downloaded rootfs). Files already present with the correct hash are kept
     * (idempotent, cheap re-runs for Repair).
     */
    private fun installBionicProot(bin: File): Result<Unit> {
        val libDir = UbuntuFileSystem.prootLibDir(context)
        libDir.mkdirs()
        bin.parentFile?.mkdirs()
        val plan = listOf(
            Triple("proot", bin, PROOT_X86_64_SHA256),
            Triple("loader", File(libDir, "loader"), PROOT_LOADER_SHA256),
            Triple("loader32", File(libDir, "loader32"), PROOT_LOADER32_SHA256),
            Triple("libtalloc.so.2", File(libDir, "libtalloc.so.2"), PROOT_LIBTALLOC_SHA256),
            Triple("libandroid-shmem.so", File(libDir, "libandroid-shmem.so"), PROOT_LIBSHMEM_SHA256),
        )
        for ((asset, target, sha) in plan) {
            if (target.isFile && Checksums.sha256(target).equals(sha, ignoreCase = true)) continue
            try {
                context.assets.open("ubuntu/proot/x86_64/$asset").use { ins ->
                    if (target.exists()) target.delete()
                    FileOutputStream(target).use { fos -> ins.copyTo(fos, 64 * 1024) }
                }
                val actual = Checksums.sha256(target)
                if (!actual.equals(sha, ignoreCase = true)) {
                    target.delete()
                    return Result.failure(
                        ErrorInfoException(
                            Errors.ubuntuFailure(
                                "proot asset '$asset' SHA-256 mismatch — expected $sha, got $actual",
                            ),
                        ),
                    )
                }
                if (asset != "libtalloc.so.2" && asset != "libandroid-shmem.so") {
                    target.setExecutable(true)
                }
            } catch (e: Exception) {
                target.delete()
                return Result.failure(
                    ErrorInfoException(
                        Errors.ubuntuFailure(
                            "proot asset '$asset' could not be installed: ${e.message ?: e.javaClass.simpleName}",
                        ),
                    ),
                )
            }
        }
        Log.i(TAG, "bionic proot bundle ready at ${bin.absolutePath}")
        return Result.success(Unit)
    }

    companion object {
        const val TAG = "OpenChat/Proot"

        /**
         * arm64/armhf pin proot v5.3.0 (official static builds, SHA-256 from
         * the release SHA256SUMS) — verified working on real Android devices
         * (the 5.3.0 aarch64 binary contains no glibc rseq registration, and
         * the device platform policies are newer/looser than the emulator's).
         *
         * x86_64 no longer uses a static glibc build: both 5.3.0 (faccessat2)
         * and 5.4.1 (glibc.pthread.rseq at startup) were killed by SIGSYS
         * (exit 159) under the emulator's zygote seccomp allowlist. It now
         * ships the **bionic** Termux build of proot (proot-me master
         * snapshot, NDK r29, min API 24) as APK assets — see the class docs.
         * PROOT_X86_64_SHA256 below pins the asset binary; the proot-me
         * release URL is kept only as a `prootUrlOverride` escape hatch.
         */
        const val PROOT_AARCH64_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
        const val PROOT_AARCH64_SHA256: String =
            "fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45"
        const val PROOT_ARM_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-arm-static"
        const val PROOT_ARM_SHA256: String =
            "bf186a37c7a19621e5bf3cfdf6bce54bfa2e220f91eb7196318e699ac174cc69"

        /** Escape hatch override source for a custom x86_64 proot (unchecked). */
        const val PROOT_X86_64_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.4.1/proot"

        /** Bionic proot bundle pins (Termux packages proot 5.1.107.92, talloc
         *  2.4.3, libandroid-shmem 0.7 — x86_64, NDK r29, extracted from the
         *  .deb files; SHA-256 computed over the extracted files). */
        const val PROOT_X86_64_SHA256: String =
            "5c6b99c48ebb87580551afd654e49eb8d178fd45fef0b65397c99a1d901c417b"
        const val PROOT_LOADER_SHA256: String =
            "914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3"
        const val PROOT_LOADER32_SHA256: String =
            "7fb73fa7f1879f7d210db70a0e3961161d0439e892c38bb834e6e17f162fae30"
        const val PROOT_LIBTALLOC_SHA256: String =
            "77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da"
        const val PROOT_LIBSHMEM_SHA256: String =
            "092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f"

        /**
         * Base environment for every proot process (pure function — JVM-tested).
         *
         * [prootTmpDir] MUST be a writable host directory: proot creates its
         * temporary files and the glue rootfs there. The default `/tmp` is not
         * writable for Android app processes, which failed every exec with
         * `can't create temporary directory: Permission denied` (exit 255).
         *
         * [prootLibDir] (bionic x86_64 build only) puts our support libs on
         * the loader path and points proot at its external ptrace loader —
         * the exec'd process env is fully replaced by the app, so these MUST
         * be explicit. In the guest the path does not exist (the app dir is
         * not bound into the rootfs), so leaking it to guest commands is
         * harmless.
         */
        fun baseEnv(prootTmpDir: String, prootLibDir: String? = null): LinkedHashMap<String, String> {
            val env = LinkedHashMap<String, String>()
            env["HOME"] = "/root"
            env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin"
            env["TERM"] = "xterm-256color"
            env["LANG"] = "C.UTF-8"
            env["DEBIAN_FRONTEND"] = "noninteractive"
            env["TMPDIR"] = "/tmp"
            // proot's seccomp filter is unreliable under Android kernels — the official
            // Termux builds disable it the same way.
            env["PROOT_NO_SECCOMP"] = "1"
            // proot's own scratch space on the HOST side (guest /tmp is separate).
            env["PROOT_TMP_DIR"] = prootTmpDir
            env.putAll(bionicEnvExtras(prootLibDir))
            return env
        }

        /**
         * Loader/lib env for the bionic proot build (pure function —
         * JVM-tested): LD_LIBRARY_PATH for libtalloc/libandroid-shmem,
         * PROOT_LOADER/PROOT_LOADER_32 for the external ptrace loaders (the
         * Termux fallback paths point at the Termux prefix, which does not
         * exist in this app). Empty for the static glibc builds.
         */
        fun bionicEnvExtras(prootLibDir: String?): Map<String, String> {
            if (prootLibDir == null) return emptyMap()
            val dir = prootLibDir.trimEnd('/')
            return linkedMapOf(
                "LD_LIBRARY_PATH" to dir,
                "PROOT_LOADER" to "$dir/loader",
                "PROOT_LOADER_32" to "$dir/loader32",
            )
        }

        /** Merges caller extras over the base env (caller wins) — pure, JVM-tested. */
        fun mergeEnv(base: Map<String, String>, extras: Map<String, String>): LinkedHashMap<String, String> {
            val merged = LinkedHashMap<String, String>(base)
            merged.putAll(extras)
            return merged
        }
    }
}
