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
 *  - x86_64 AND arm64-v8a: **bionic proot** (Termux build, proot-me master
 *    snapshot, NDK r29, min API 24) copied from APK assets with pinned
 *    SHA-256. Static glibc proot builds (5.3.0 and 5.4.1 alike) die with
 *    SIGSYS (exit 159) under Android's zygote seccomp allowlist — proven on
 *    the emulator (x86_64) and reported on real arm64 devices (vendor kernels
 *    enforce the same policy; the app surfaced it as "no known proot
 *    signature"/exit 159 with an empty tail). Their modern libc issues
 *    syscalls at startup (faccessat2, rseq — the 5.4.1 binary literally
 *    contains "glibc.pthread.rseq") that the filter kills or answers with
 *    ENOSYS, while the identical binary+rootfs+env runs perfectly outside
 *    the filter. Bionic never issues those syscalls; this is the same binary
 *    proot-distro uses on Android every day. Support files (ptrace loader,
 *    loader32, libtalloc.so.2, libandroid-shmem.so) ship alongside per ABI.
 *  - armhf (armeabi-v7a primary): static glibc build from proot-me releases
 *    (hash-pinned download) — legacy 32-bit devices, unchanged.
 *  - `prootUrlOverride` (advanced settings) still replaces the main binary on
 *    any ABI (SHA check skipped, warning logged) — the bionic support files
 *    are installed from assets either way and simply go unused by a static
 *    override build.
 *  - Readiness is hash-based: a binary whose SHA-256 does not match the
 *    current ABI's pin (e.g. an old static build left by a previous app
 *    version) is replaced by the pinned bundle on the next ensureProot —
 *    upgrading installs self-heal instead of keeping a possibly-broken proot.
 */
class ProotRunner(
    private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * Ensures the proot binary (and, on bionic ABIs, its support files)
     * exists, matches this app version's pins, and is executable. Bionic ABIs
     * (x86_64, arm64-v8a) install from APK assets; armhf downloads from the
     * pinned release (or the user's `prootUrlOverride` — in that case the
     * SHA-256 check is skipped and a warning is logged).
     */
    suspend fun ensureProot(): Result<File> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val bin = UbuntuFileSystem.prootBin(context)
        val abi = Build.SUPPORTED_ABIS[0]
        val override = settings.settings.value.prootUrlOverride

        // Hash-based readiness: the binary must match THIS app version's pin
        // for the ABI (plus, on bionic ABIs, the support files with theirs).
        // A leftover binary from an older app version (different hash) is
        // replaced below — this is what migrates v0.1.10/11 static installs
        // onto the bionic build without a manual Reset.
        if (bionicBundleFor(abi) != null) {
            if (bionicBundleReady(abi, bin)) return@withContext Result.success(bin)
        } else if (bin.isFile && bin.canExecute() &&
            Checksums.sha256(bin).equals(PROOT_ARM_SHA256, ignoreCase = true)
        ) {
            return@withContext Result.success(bin)
        }

        if (bionicBundleFor(abi) != null) {
            installBionicProot(bin, abi).getOrElse { return@withContext Result.failure(it) }
            if (override == null) return@withContext Result.success(bin)
            // Override set: the download below replaces the asset binary.
        }

        if (bionicBundleFor(abi) == null && abi != "armeabi-v7a") {
            return@withContext Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Unsupported ABI for proot: '$abi' — this APK supports arm64-v8a, armeabi-v7a, x86_64"),
                ),
            )
        }

        // Reached only for the static armhf build, or when an explicit
        // prootUrlOverride replaces the bionic asset binary.
        val (url, sha) = when (abi) {
            "armeabi-v7a" -> PROOT_ARM_URL to PROOT_ARM_SHA256
            else -> override!! to bionicBundleFor(abi)!!.prootSha
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
     * argv = [proot, --kill-on-exit, -0, -w, cwd, -R, rootfs] + identity binds + cmd,
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
        // proot -R bind-mounts the HOST's /etc/passwd, /etc/group and
        // /etc/nsswitch.conf into the guest (its "recommended binds"). On
        // Android those files lack _apt/sudo/ssh, so every guest NSS lookup
        // answers from the host database: apt warns "No sandbox user '_apt'"
        // and dpkg postinsts of sudo and openssh-client die at their
        // getent/adduser/groupadd steps (verified: run 18, run-as probe shows
        // host passwd through -R). Later -b arguments override earlier binds,
        // so re-binding the guest's own files over them restores normal
        // guest NSS behavior (verified locally: _apt visible, getent RC=0).
        for (rel in listOf("etc/passwd", "etc/group", "etc/nsswitch.conf")) {
            argv.add("-b")
            argv.add("${rootfs.absolutePath}/$rel:/$rel")
        }
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

    /** Lib dir passed to [baseEnv] for the bionic builds, null elsewhere. */
    private fun bionicLibDir(): String? =
        if (bionicBundleFor(Build.SUPPORTED_ABIS[0]) != null) {
            UbuntuFileSystem.prootLibDir(context).absolutePath
        } else {
            null
        }

    /**
     * One bionic proot bundle per supported ABI: the APK-asset directory and
     * the pinned SHA-256 of every shipped file. Pure data — JVM-testable.
     */
    data class BionicBundle(
        val assetDir: String,
        val prootSha: String,
        val loaderSha: String,
        val loader32Sha: String,
        val tallocSha: String,
        val shmemSha: String,
    )

    /** The bundle for [abi], or null when the ABI uses the static download. */
    fun bionicBundleFor(abi: String): BionicBundle? = when (abi) {
        "x86_64" -> BIONIC_X86_64
        "arm64-v8a" -> BIONIC_ARM64
        else -> null
    }

    /**
     * True when the on-disk bundle for [abi] is complete AND every file's
     * SHA-256 matches this app version's pins. Anything else (missing file,
     * stale binary from an older app version, corruption) reports false and
     * gets reinstalled by [installBionicProot].
     */
    fun bionicBundleReady(abi: String, bin: File): Boolean {
        val bundle = bionicBundleFor(abi) ?: return false
        // bin = <files>/ubuntu/bin/proot → lib dir = <files>/ubuntu/lib
        // (mirrors UbuntuFileSystem.prootLibDir without needing a Context).
        val libDir = bin.parentFile?.parentFile?.let { File(it, "lib") }
            ?: return false
        val expected = mapOf(
            bin.absolutePath to bundle.prootSha,
            File(libDir, "loader").absolutePath to bundle.loaderSha,
            File(libDir, "loader32").absolutePath to bundle.loader32Sha,
            File(libDir, "libtalloc.so.2").absolutePath to bundle.tallocSha,
            File(libDir, "libandroid-shmem.so").absolutePath to bundle.shmemSha,
        )
        for ((path, sha) in expected) {
            val f = File(path)
            val needsExec = path == bin.absolutePath ||
                path.endsWith("/loader") || path.endsWith("/loader32")
            if (!f.isFile || (needsExec && !f.canExecute())) {
                return false
            }
            if (!Checksums.sha256(f).equals(sha, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * Copies the bionic proot bundle (binary, ptrace loader, loader32,
     * libtalloc, libandroid-shmem) from APK assets into the app's files dir.
     * Every file is verified against its pinned SHA-256 after the copy —
     * bundled bytes are trusted only once verified (§19, same rule as the
     * downloaded rootfs). Files already present with the correct hash are kept
     * (idempotent, cheap re-runs for Repair).
     */
    private fun installBionicProot(bin: File, abi: String): Result<Unit> {
        val bundle = bionicBundleFor(abi)
            ?: return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("no bionic proot bundle for ABI '$abi'")),
            )
        val libDir = UbuntuFileSystem.prootLibDir(context)
        libDir.mkdirs()
        bin.parentFile?.mkdirs()
        val plan = listOf(
            Triple("proot", bin, bundle.prootSha),
            Triple("loader", File(libDir, "loader"), bundle.loaderSha),
            Triple("loader32", File(libDir, "loader32"), bundle.loader32Sha),
            Triple("libtalloc.so.2", File(libDir, "libtalloc.so.2"), bundle.tallocSha),
            Triple("libandroid-shmem.so", File(libDir, "libandroid-shmem.so"), bundle.shmemSha),
        )
        for ((asset, target, sha) in plan) {
            if (target.isFile && Checksums.sha256(target).equals(sha, ignoreCase = true)) continue
            try {
                context.assets.open("ubuntu/proot/${bundle.assetDir}/$asset").use { ins ->
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
        Log.i(TAG, "bionic proot bundle ($abi) ready at ${bin.absolutePath}")
        return Result.success(Unit)
    }

    companion object {
        const val TAG = "OpenChat/Proot"

        /**
         * armhf pins proot v5.3.0 (official static build, SHA-256 from the
         * release SHA256SUMS) — legacy 32-bit devices only.
         *
         * x86_64 and arm64-v8a no longer use static glibc builds: both 5.3.0
         * (faccessat2) and 5.4.1 (glibc.pthread.rseq at startup) die with
         * SIGSYS (exit 159) under Android's zygote seccomp allowlist — proven
         * on the emulator and reported on real arm64 devices. Both ABIs ship
         * the **bionic** Termux build of proot (proot-me master snapshot,
         * NDK r29, min API 24) as APK assets — see the class docs and the
         * BIONIC_* bundles below.
         */
        const val PROOT_ARM_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-arm-static"
        const val PROOT_ARM_SHA256: String =
            "bf186a37c7a19621e5bf3cfdf6bce54bfa2e220f91eb7196318e699ac174cc69"

        /** Escape hatch override source for a custom x86_64 proot (unchecked). */
        const val PROOT_X86_64_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.4.1/proot"

        /** Bionic proot bundle pins (Termux packages proot 5.1.107.92, talloc
         *  2.4.3, libandroid-shmem 0.7 — NDK r29, extracted from the .deb
         *  files; SHA-256 computed over the extracted files). The x86_64
         *  bundle has been green in E2E since v0.1.11 (run 35060182063); the
         *  arm64-v8a bundle is the same upstream build for aarch64 and
         *  replaces the seccomp-killed static 5.3.0 on real devices. */
        val BIONIC_X86_64 = BionicBundle(
            assetDir = "x86_64",
            prootSha = "5c6b99c48ebb87580551afd654e49eb8d178fd45fef0b65397c99a1d901c417b",
            loaderSha = "914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3",
            loader32Sha = "7fb73fa7f1879f7d210db70a0e3961161d0439e892c38bb834e6e17f162fae30",
            tallocSha = "77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da",
            shmemSha = "092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f",
        )
        val BIONIC_ARM64 = BionicBundle(
            assetDir = "arm64-v8a",
            prootSha = "ea47e17da8e6ff4882c169c6508861e5b4be9227e477c6020f4f14facc85c10d",
            loaderSha = "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04",
            loader32Sha = "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34",
            tallocSha = "3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb",
            shmemSha = "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731",
        )

        /**
         * Base environment for every proot process (pure function — JVM-tested).
         *
         * [prootTmpDir] MUST be a writable host directory: proot creates its
         * temporary files and the glue rootfs there. The default `/tmp` is not
         * writable for Android app processes, which failed every exec with
         * `can't create temporary directory: Permission denied` (exit 255).
         *
         * [prootLibDir] (bionic builds: x86_64 and arm64-v8a) puts our
         * support libs on the loader path and points proot at its external
         * ptrace loader — the exec'd process env is fully replaced by the
         * app, so these MUST be explicit. In the guest the path does not
         * exist (the app dir is
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
