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
 * Downloads/verifies the static proot binary (per ABI, pinned v5.3.0 + SHA-256)
 * and builds the real proot argv/env used by every session and exec in the app
 * (spec §4).
 */
class ProotRunner(
    private val context: Context,
    private val settings: SettingsStore,
) {

    /**
     * Ensures the static proot binary exists and is executable, downloading it
     * from the pinned release (or the user's `prootUrlOverride` — in that case
     * the SHA-256 check is skipped and a warning is logged) when missing.
     */
    suspend fun ensureProot(): Result<File> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val bin = UbuntuFileSystem.prootBin(context)
        if (bin.isFile && bin.canExecute()) return@withContext Result.success(bin)

        val abi = Build.SUPPORTED_ABIS[0]
        val (url, sha) = when (abi) {
            "arm64-v8a" -> PROOT_AARCH64_URL to PROOT_AARCH64_SHA256
            "armeabi-v7a" -> PROOT_ARM_URL to PROOT_ARM_SHA256
            "x86_64" -> PROOT_X86_64_URL to PROOT_X86_64_SHA256
            else -> return@withContext Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Unsupported ABI for proot: '$abi' — this APK supports arm64-v8a, armeabi-v7a, x86_64"),
                ),
            )
        }
        val override = settings.settings.value.prootUrlOverride
        val effectiveUrl = override ?: url
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
            Http.client.newCall(Http.newRequest(effectiveUrl).build()).execute().use { response ->
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
        val merged = mergeEnv(baseEnv(UbuntuFileSystem.prootTmpDir(context).absolutePath), env)
        return SessionSpec(argv = argv, cwd = cwd, env = merged)
    }

    companion object {
        const val TAG = "OpenChat/Proot"

        const val PROOT_AARCH64_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
        const val PROOT_AARCH64_SHA256: String =
            "fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45"
        const val PROOT_ARM_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-arm-static"
        const val PROOT_ARM_SHA256: String =
            "bf186a37c7a19621e5bf3cfdf6bce54bfa2e220f91eb7196318e699ac174cc69"
        const val PROOT_X86_64_URL: String =
            "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static"
        const val PROOT_X86_64_SHA256: String =
            "d1eb20cb201e6df08d707023efb000623ff7c10d6574839d7bb42d0adba6b4da"

        /**
         * Base environment for every proot process (pure function — JVM-tested).
         *
         * [prootTmpDir] MUST be a writable host directory: proot creates its
         * temporary files and the glue rootfs there. The default `/tmp` is not
         * writable for Android app processes, which failed every exec with
         * `can't create temporary directory: Permission denied` (exit 255).
         */
        fun baseEnv(prootTmpDir: String): LinkedHashMap<String, String> {
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
            return env
        }

        /** Merges caller extras over the base env (caller wins) — pure, JVM-tested. */
        fun mergeEnv(base: Map<String, String>, extras: Map<String, String>): LinkedHashMap<String, String> {
            val merged = LinkedHashMap<String, String>(base)
            merged.putAll(extras)
            return merged
        }
    }
}
