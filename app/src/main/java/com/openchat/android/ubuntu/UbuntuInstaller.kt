package com.openchat.android.ubuntu

import android.content.Context
import android.os.Build
import android.util.Log
import com.openchat.android.core.net.Http
import com.openchat.android.core.util.Errors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Real Ubuntu installer plumbing (spec §3–4): pinned-URL download with
 * streaming SHA-256 verification, hardened tar extraction (path-traversal
 * rejection, symlink + mode restoration via android.system.Os), rootfs
 * configuration (DNS, per-arch APT sources, hosts) and in-rootfs tool
 * installation (apt packages + verified Node.js v20).
 *
 * Constructed with just the [Context]; every command runs through the `exec`
 * lambda provided by [UbuntuRuntime] (strict: non-zero exit → failure), so this
 * class stays free of runtime state.
 */
class UbuntuInstaller(private val context: Context) {

    /**
     * Streams [url] into the Ubuntu cache directory while computing SHA-256,
     * then verifies against [sha256] (blank → verification skipped with a
     * warning, used for the advanced `rootfsUrlOverride` path).
     *
     * Reuses an already-cached file when its hash matches.
     *
     * Transient network failures (timeout, connection reset, HTTP 5xx) are
     * retried ONCE after a short backoff (spec §19 retry policy). Checksum
     * mismatches are NOT retried — a wrong hash means a corrupt/tampered
     * artifact and a retry would only repeat it.
     *
     * @param onProgress (bytesRead, totalBytes — total is -1 when unknown)
     */
    suspend fun downloadAndVerify(
        url: String,
        sha256: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> {
        val first = attemptDownload(url, sha256, onProgress)
        if (first.isSuccess) return first
        val err = first.exceptionOrNull() ?: return first
        val transient = err.message?.contains("SHA-256 mismatch") != true
        if (transient && err.message?.contains("could not open") != true) {
            kotlinx.coroutines.delay(3000)
            Log.w(TAG, "transient download failure — retrying once after 3s: ${err.message?.take(160)}")
            return attemptDownload(url, sha256, onProgress)
        }
        return first
    }

    private suspend fun attemptDownload(
        url: String,
        sha256: String,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val cache = UbuntuFileSystem.cacheDir(context).apply { mkdirs() }
        val name = url.substringAfterLast('/').ifBlank { "rootfs.tar.gz" }
        val target = File(cache, name)
        val verify = sha256.isNotBlank()

        if (target.isFile && target.length() > 0) {
            val cachedOk = if (verify) Checksums.sha256(target).equals(sha256, ignoreCase = true) else true
            if (cachedOk) {
                if (!verify) Log.w(TAG, "SHA-256 check skipped for override URL: $url")
                onProgress(target.length(), target.length())
                return@withContext Result.success(target)
            }
            Log.w(TAG, "cached '$name' has a different hash — re-downloading")
        }

        val tmp = File(cache, "$name.part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            Http.client.newCall(Http.newRequest(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while downloading $name")
                }
                val body = response.body ?: throw IOException("Empty response body for $url")
                val total = body.contentLength()
                body.byteStream().use { ins ->
                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (verify) {
                val actual = digest.digest().toHex()
                if (!actual.equals(sha256, ignoreCase = true)) {
                    throw IOException(
                        "SHA-256 mismatch for $name — expected $sha256, got $actual " +
                            "(download is corrupt or the mirror was tampered with)",
                    )
                }
            } else {
                Log.w(TAG, "SHA-256 check skipped for override URL: $url")
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Result.success(target)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("Download of $name failed: ${e.message ?: e.javaClass.simpleName}")),
            )
        }
    }

    /**
     * Extracts a .tar.gz rootfs into [targetDir] — hardened streaming logic
     * lives in [UbuntuArchive] (path-traversal rejection, symlink + permission
     * restoration), shared with the userspace Import feature.
     */
    suspend fun extract(tarGz: File, targetDir: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            FileInputStream(tarGz).use { ins -> UbuntuArchive.extract(ins, targetDir) }
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Malicious archive entry rejected: ${e.message} — use Reset, then Install again"),
                ),
            )
        } catch (e: Exception) {
            Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("Extraction failed: ${e.message ?: e.javaClass.simpleName}")),
            )
        }
    }

    /**
     * Configures a freshly extracted rootfs: DNS resolvers, per-arch APT sources
     * (ports.ubuntu.com for arm64/armhf, archive.ubuntu.com for amd64 — including
     * the deb822 `ubuntu.sources` file present in newer bases, which is rewritten
     * in place so the suites are never configured twice) and /etc/hosts.
     * The sources writing itself lives in the shared, JVM-tested [AptSources].
     */
    suspend fun configure(rootfs: File, variant: RootfsVariant): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val arch = archFromUrl(variant.url)
            val etc = File(rootfs, "etc").apply { mkdirs() }
            File(etc, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(etc, "hosts").writeText("127.0.0.1 localhost\n")
            AptSources.writeFor(File(etc, "apt"), arch, variant.codename).getOrThrow()
            restoreAptTrust(rootfs)
            installSyscallCompat(rootfs)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("Rootfs configuration failed: ${e.message ?: e.javaClass.simpleName}")),
            )
        }
    }

    /**
     * Public wrapper for the import path ([UbuntuRuntime.import] re-asserts
     * the host-side config on the swapped-in rootfs). Same idempotent,
     * hash-pinned install as [configure]; device-ABI gated internally.
     */
    fun installSyscallCompatForImport(rootfs: File) = installSyscallCompat(rootfs)

    /**
     * Provisions APT trust material into the rootfs from the APK's pinned
     * assets (idempotent, every ABI, safe to re-run any time):
     *
     *  1. **Ubuntu archive keyring** → `/usr/share/keyrings/ubuntu-archive-keyring.gpg`
     *     AND `/etc/apt/trusted.gpg.d/ubuntu-archive-keyring.gpg`. The first
     *     path is what deb822 `Signed-By:` references; the second is what
     *     classic focal `sources.list` verification uses. Both are rewritten
     *     whenever the on-disk hash differs from the pinned asset — this heals
     *     rootfs bases whose keyring predates the 2018 archive signing key
     *     (the exact `NO_PUBKEY 871920D1991BC93C` → `E: The repository … is
     *     not signed` failure reported on real devices in v0.1.12).
     *  2. **Mozilla CA bundle** → `/etc/ssl/certs/ca-certificates.crt`, only
     *     when the rootfs has none (ubuntu-base ships without it): HTTPS apt
     *     sources need a trust store, and apt 2.0 (focal) carries the https
     *     transport natively. When the rootfs already has a CA bundle it is
     *     left untouched — the authoritative copy belongs to the package
     *     manager, which keeps it current via `ca-certificates` upgrades.
     *
     * Every copied file is verified against its pinned SHA-256 (same trust
     * rule as every other artifact); a mismatch is a hard failure.
     *
     * Provenance of the pinned assets (both fetched from the official pool,
     * via HTTPS):
     *  - keyring: ubuntu-keyring 2020.02.11.4 (focal) —
     *    /usr/share/keyrings/ubuntu-archive-keyring.gpg, contains the 2012
     *    AND 2018 archive signing keys (871920D1991BC93C verified).
     *  - CA bundle: ca-certificates 20240203~20.04.1 (focal-updates) — the
     *    146 mozilla/*.crt sources concatenated, equivalent to what
     *    update-ca-certificates writes to /etc/ssl/certs/ca-certificates.crt.
     */
    fun restoreAptTrust(rootfs: File) {
        val keyringTargets = listOf(
            File(rootfs, "usr/share/keyrings/ubuntu-archive-keyring.gpg"),
            File(rootfs, "etc/apt/trusted.gpg.d/ubuntu-archive-keyring.gpg"),
        )
        keyringTargets.forEach { target ->
            copyAssetIfHashDiffers(KEYRING_ASSET, target, KEYRING_SHA256)
        }
        val ca = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (!ca.isFile || ca.length() == 0L) {
            copyAssetIfHashDiffers(CA_ASSET, ca, CA_BUNDLE_SHA256)
        }
    }

    /**
     * Copies [assetPath] from the APK into [target] unless the file already
     * matches [sha256]. The written copy is re-verified; a mismatch deletes
     * the file and fails hard (never leaves an untrusted artifact in place).
     */
    private fun copyAssetIfHashDiffers(assetPath: String, target: File, sha256: String) {
        if (target.isFile && Checksums.sha256(target).equals(sha256, ignoreCase = true)) return
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { ins ->
            if (target.exists()) target.delete()
            FileOutputStream(target).use { fos -> ins.copyTo(fos, 64 * 1024) }
        }
        val actual = Checksums.sha256(target)
        if (!actual.equals(sha256, ignoreCase = true)) {
            target.delete()
            throw IOException("$assetPath SHA-256 mismatch after copy — expected $sha256, got $actual")
        }
        target.setReadable(true, false)
    }

    /**
     * Installs the guest syscall-compat preload into the rootfs (x86_64
     * devices only): Android's zygote seccomp policy answers the legacy file
     * syscalls (rename, unlink, mkdir, stat, …) with ENOSYS — bionic never
     * issues them, but glibc still does, so apt/dpkg fail with
     * "rename failed: Function not implemented" (errno 38) inside proot.
     * (arm64 has no legacy rename at all — real arm64 devices are unaffected.)
     * The tiny preload library re-implements the legacy wrappers on top of
     * the *at() syscalls bionic itself uses, and /etc/ld.so.preload activates
     * it for every dynamic guest process.
     *
     * Idempotent: the library is re-copied when its hash differs, the
     * preload line is appended only when missing. The copy is verified
     * against the pinned SHA-256 (same trust rule as every other artifact).
     */
    private fun installSyscallCompat(rootfs: File) {
        if (Build.SUPPORTED_ABIS[0] != "x86_64") return
        val libDir = File(rootfs, "usr/local/lib").apply { mkdirs() }
        val lib = File(libDir, "libopenchat_compat.so")
        if (!lib.isFile || !Checksums.sha256(lib).equals(COMPAT_LIB_SHA256, ignoreCase = true)) {
            context.assets.open("ubuntu/compat/x86_64/libopenchat_compat.so").use { ins ->
                if (lib.exists()) lib.delete()
                FileOutputStream(lib).use { fos -> ins.copyTo(fos, 64 * 1024) }
            }
            val actual = Checksums.sha256(lib)
            if (!actual.equals(COMPAT_LIB_SHA256, ignoreCase = true)) {
                lib.delete()
                throw IOException(
                    "syscall-compat preload SHA-256 mismatch — expected $COMPAT_LIB_SHA256, got $actual",
                )
            }
            lib.setReadable(true, false)
            lib.setWritable(false, false)
        }
        val preload = File(rootfs, "etc/ld.so.preload")
        val line = "/usr/local/lib/libopenchat_compat.so"
        val existing = if (preload.isFile) preload.readText() else ""
        if (existing.lineSequence().none { it.trim() == line }) {
            preload.writeText(if (existing.isBlank()) "$line\n" else existing.trimEnd() + "\n$line\n")
        }
    }

    /**
     * Installs the base tool set inside the rootfs via apt
     * (curl, ca-certificates, xz-utils, git, python3, python3-pip, wget, procps, sudo).
     *
     * [execStreamFn] is provided by [UbuntuRuntime] — it routes every command
     * through `proot … bash -lc <cmd>` via execStream (line callback may be
     * null; the runtime always mirrors output into its own log) and the
     * success value is the real exit code: 0 is required, anything else is a
     * hard failure.
     */
    suspend fun installTools(
        execStreamFn: suspend (String, ((String) -> Unit)?) -> Result<Int>,
        onLog: (String) -> Unit,
    ): Result<Unit> {
        onLog("apt-get update…")
        runStep(execStreamFn, "apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 update")
            .getOrElse { return Result.failure(it) }
        onLog("Installing curl, ca-certificates, xz-utils, git, python3, python3-pip, wget, procps, sudo…")
        runStep(
            execStreamFn,
            // Acquire::Retries rides out transient connection failures (flaky
            // mobile networks); per-fetch, so a mirror blip does not kill the
            // whole install. Genuine errors still fail the step honestly.
            "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                "-o Acquire::Retries=3 -o Acquire::http::Timeout=60 -o Acquire::https::Timeout=60 " +
                "curl ca-certificates xz-utils git python3 python3-pip wget procps sudo",
        ).getOrElse { return Result.failure(it) }
        onLog("Base tools installed")
        return Result.success(Unit)
    }

    /**
     * Installs Node.js v20.18.1 into /opt/node inside the rootfs (skipped when
     * /opt/node/bin/node already exists). The tarball is downloaded in-rootfs via
     * curl, then verified host-side against nodejs.org's SHASUMS256.txt (fetched
     * live) — a hash mismatch is an honest hard failure with both hashes.
     *
     * [execStreamFn] streams command output (see [installTools]); [execFn] is
     * the output-capturing exec used for probes and hashing.
     */
    suspend fun installNode(
        execStreamFn: suspend (String, ((String) -> Unit)?) -> Result<Int>,
        execFn: suspend (String) -> Result<String>,
        onLog: (String) -> Unit,
        abi: String,
    ): Result<Unit> {
        val nodeArch = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            "x86_64" -> "x64"
            else -> return fail("Unsupported ABI for Node.js: '$abi'", IllegalArgumentException(abi))
        }
        val fileName = "node-$NODE_VERSION-linux-$nodeArch.tar.xz"
        val tarballUrl = "https://nodejs.org/dist/$NODE_VERSION/$fileName"

        val probe = execFn("test -x /opt/node/bin/node")
        if (probe.isSuccess) {
            onLog("Node.js already present at /opt/node — skipping download")
            return Result.success(Unit)
        }

        onLog("Downloading Node.js $NODE_VERSION ($nodeArch) into the rootfs…")
        runStep(execStreamFn, "curl -fsSL -o /tmp/node.tar.xz $tarballUrl")
            .getOrElse { return Result.failure(it) }

        onLog("Verifying against nodejs.org SHASUMS256.txt…")
        val shasums = fetchText("https://nodejs.org/dist/$NODE_VERSION/SHASUMS256.txt")
            .getOrElse { return fail("Could not fetch SHASUMS256.txt from nodejs.org", it) }
        val expected = shasums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it[1] == fileName }
            ?.firstOrNull()
        if (expected == null) {
            return fail("SHASUMS256.txt has no entry for $fileName", IllegalStateException("missing checksum entry"))
        }
        val actualOutput = execFn("sha256sum /tmp/node.tar.xz")
            .getOrElse { return fail("Could not hash the downloaded Node.js tarball", it) }
        val actual = actualOutput.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (!actual.equals(expected, ignoreCase = true)) {
            return fail(
                "Node.js tarball SHA-256 mismatch — expected $expected, got $actual " +
                    "(download corrupt or tampered); nothing was installed",
                IllegalStateException("checksum mismatch"),
            )
        }

        onLog("Extracting Node.js to /opt/node and symlinking…")
        val extractCmd = "mkdir -p /opt/node && tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 && " +
            "ln -sf /opt/node/bin/node /usr/local/bin/node && " +
            "ln -sf /opt/node/bin/npm /usr/local/bin/npm && " +
            "ln -sf /opt/node/bin/npx /usr/local/bin/npx && " +
            "rm -f /tmp/node.tar.xz"
        runStep(execStreamFn, extractCmd).getOrElse { return Result.failure(it) }

        val versionOut = execFn("node -v").getOrNull()?.trim()
        onLog("Node.js installed: ${versionOut?.ifBlank { null } ?: "(version check failed)"}")
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ helpers

    /**
     * One checked command through [execStreamFn]: a start failure or a
     * non-zero exit code both become a failure with an actionable [ErrorInfo].
     */
    private suspend fun runStep(
        execStreamFn: suspend (String, ((String) -> Unit)?) -> Result<Int>,
        cmd: String,
    ): Result<Unit> {
        val code = execStreamFn(cmd, null).getOrElse {
            return Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Command failed to start — ${cmd.take(120)}: ${it.message ?: it.javaClass.simpleName}"),
                ),
            )
        }
        if (code != 0) {
            return Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("'${cmd.take(120)}' exited with code $code (see the Ubuntu log)"),
                ),
            )
        }
        return Result.success(Unit)
    }

    private suspend fun fetchText(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(Http.newRequest(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                val body = response.body ?: throw IOException("Empty body for $url")
                Result.success(body.string())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun archFromUrl(url: String): String = when {
        "-arm64." in url -> "arm64"
        "-armhf." in url -> "armhf"
        "-amd64." in url -> "amd64"
        else -> throw IllegalArgumentException("Cannot determine the Ubuntu arch from URL: $url")
    }

    private fun fail(what: String, cause: Throwable): Result<Unit> =
        Result.failure(
            ErrorInfoException(
                Errors.ubuntuFailure("$what: ${cause.message?.take(500) ?: cause.javaClass.simpleName}"),
            ),
        )

    private companion object {
        const val TAG = "OpenChat/Installer"
        const val NODE_VERSION = "v20.18.1"

        /** Pinned SHA-256 of assets/ubuntu/compat/x86_64/libopenchat_compat.so
         *  (build: scripts/guest-compat/build.sh, source: compat.c). The v2
         *  build adds the hardlink copy-fallback: dpkg backup links get
         *  EACCES under the emulator's seccomp/proot layers even though the
         *  rest of the *at() family is allowed (E2E run 34936568395). The v3
         *  build adds link-fallback bookkeeping: shadow-utils' lock protocol
         *  (groupadd/useradd/adduser) verifies lock acquisition with a
         *  st_nlink==2 check, which a content copy cannot satisfy — the
         *  stat family now reports nlink 2 for fallback-linked base paths,
         *  fixing groupadd exit 10 (E2E run 34988589844, proven locally
         *  with a linkat-blocking seccomp filter: old shim RC=10, new RC=0). */
        const val COMPAT_LIB_SHA256 =
            "d700b02fd90775188ce8099ab3ebee225e3bb83f0f10a48aa47b2f32949a55cf"

        /** assets/ubuntu/keyrings/ubuntu-archive-keyring.gpg — ubuntu-keyring
         *  2020.02.11.4 (focal), /usr/share/keyrings/ubuntu-archive-keyring.gpg;
         *  contains the 2012 + 2018 Ubuntu archive signing keys. */
        const val KEYRING_ASSET = "ubuntu/keyrings/ubuntu-archive-keyring.gpg"
        const val KEYRING_SHA256 =
            "1a4dd63e5c76728960a2edddae22e2e0fc53df8e8b87806deb971030ac704eb0"

        /** assets/ubuntu/ca/ca-certificates.crt — 146 mozilla certs from
         *  ca-certificates 20240203~20.04.1 (focal-updates), concatenated
         *  exactly as update-ca-certificates would write the bundle. */
        const val CA_ASSET = "ubuntu/ca/ca-certificates.crt"
        const val CA_BUNDLE_SHA256 =
            "6d84ab71cb726c0641b0af84303c316e3fa50db941dc8507d09045eb2fa5d238"
    }
}
