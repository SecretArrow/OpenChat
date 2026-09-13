package com.openchat.android.ai.opencode

import com.openchat.android.ai.providers.SseHttp
import com.openchat.android.ai.providers.ProviderException
import com.openchat.android.core.net.Http
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import com.openchat.android.ubuntu.UbuntuRuntime
import kotlinx.coroutines.CancellationException
import okhttp3.Request

/**
 * REAL installation chain for the OpenCode CLI inside the Ubuntu rootfs
 * (contract: ai/opencode/OpenCodeInstaller.kt, spec §29).
 *
 * Steps (every step goes through [UbuntuRuntime.exec]/[UbuntuRuntime.execStream]):
 * 1. apt-get update + install curl, ca-certificates, xz-utils, git, python3,
 *    python3-pip, wget, procps.
 * 2. Node 20 LTS (only if `/opt/node/bin/node` is missing): download the
 *    per-ABI tarball inside the rootfs, verify sha256 against nodejs.org's
 *    SHASUMS256.txt (fetched host-side via OkHttp), extract to /opt/node and
 *    symlink node/npm/npx into /usr/local/bin.
 * 3. `npm install -g opencode-ai pnpm`.
 * 4. `opencode --version` sanity check.
 *
 * Every step logs through [onLog]; failures map to [Errors.ubuntuFailure] or
 * [Errors.opencodeFailure].
 */
class OpenCodeInstaller(
    private val ubuntu: UbuntuRuntime,
    private val onLog: (String) -> Unit,
) {

    /**
     * Runs the full install chain; idempotent (existing Node is reused). Every
     * failure is returned as `Result.failure` — never thrown — except coroutine
     * cancellation, which propagates as [CancellationException].
     */
    suspend fun install(): Result<Unit> {
        return try {
            ubuntu.ensureReady().fold(
                onSuccess = {},
                onFailure = { return Result.failure(ProviderException(Errors.ubuntuNotReady())) },
            )
            log("Installing base packages (apt-get update + install)…")
            val apt = ubuntu.exec(
                "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                    "curl ca-certificates xz-utils git python3 python3-pip wget procps",
                timeoutMs = APT_TIMEOUT_MS,
            )
            apt.fold(
                onSuccess = { out -> tail(out).forEach { log("  $it") } },
                onFailure = { return Result.failure(ubuntuFail("apt-get failed", it)) },
            )

            installNodeIfNeeded()

            log("Installing opencode-ai + pnpm via npm (this can take a few minutes)…")
            val npm = ubuntu.exec("npm install -g opencode-ai pnpm", timeoutMs = NPM_TIMEOUT_MS)
            npm.fold(
                onSuccess = { out -> tail(out).forEach { log("  $it") } },
                onFailure = { return Result.failure(opencodeFail("npm install -g opencode-ai pnpm failed", it)) },
            )

            val version = version()
            version.fold(
                onSuccess = { v -> log("OpenCode installed: opencode $v") },
                onFailure = {
                    return Result.failure(opencodeFail("opencode binary not runnable after install", it))
                },
            )
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(
                (t as? ProviderException)
                    ?: opencodeFail(
                        "OpenCode installation failed: ${t.message ?: t.javaClass.simpleName}",
                        null,
                    )
            )
        }
    }

    /** `opencode --version` (trimmed stdout) or a failure with actionable info. */
    suspend fun version(): Result<String> {
        val r = ubuntu.exec("opencode --version", timeoutMs = VERSION_TIMEOUT_MS)
        return r.fold(
            onSuccess = { out ->
                val v = out.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                if (v.isBlank()) {
                    Result.failure(opencodeFail("opencode --version produced no output", null))
                } else {
                    Result.success(v)
                }
            },
            onFailure = {
                Result.failure(
                    opencodeFail("opencode is not installed or not runnable inside the Ubuntu userspace", it)
                )
            },
        )
    }

    // ------------------------------------------------------------------ internals

    /**
     * Installs Node 20 LTS into /opt/node when missing. The tarball is downloaded
     * INSIDE the rootfs (curl), its sha256 verified against SHASUMS256.txt fetched
     * from the HOST side via OkHttp before extraction.
     */
    private suspend fun installNodeIfNeeded() {
        val nodePresent = ubuntu.exec("test -x /opt/node/bin/node")
        if (nodePresent.isSuccess) {
            log("Node.js already present at /opt/node/bin/node — skipping download")
            return
        }
        val nodeArch = when (ubuntu.abi()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armv7l"
            "x86_64" -> "x64"
            else -> null
        }
        if (nodeArch == null) {
            throw opencodeFail("Unsupported ABI for Node.js: ${ubuntu.abi()}", null)
        }
        val tarballName = "node-v$NODE_VERSION-linux-$nodeArch.tar.xz"
        val tarballUrl = "https://nodejs.org/dist/v$NODE_VERSION/$tarballName"
        log("Downloading Node.js v$NODE_VERSION ($nodeArch) into the rootfs…")
        ubuntu.exec("curl -fsSL -o /tmp/node.tar.xz $tarballUrl", timeoutMs = NODE_DOWNLOAD_TIMEOUT_MS)
            .fold(
                onSuccess = {},
                onFailure = { throw opencodeFail("Node.js download failed ($tarballUrl)", it) },
            )

        val expectedSha = fetchExpectedSha256(tarballName)
            ?: throw opencodeFail(
                "Could not fetch https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt to verify the Node.js tarball",
                null,
            )
        val actualSha = ubuntu.exec("sha256sum /tmp/node.tar.xz")
            .fold(
                onSuccess = { out -> out.trim().lineSequence().firstOrNull()?.substringBefore(' ')?.trim() ?: "" },
                onFailure = { throw opencodeFail("sha256sum failed for /tmp/node.tar.xz", it) },
            )
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw ubuntuFail(
                "Node.js tarball checksum mismatch (expected $expectedSha, got $actualSha)", null
            )
        }
        log("Node.js checksum OK — extracting to /opt/node…")
        ubuntu.exec(
            "mkdir -p /opt/node && tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 && " +
                "ln -sf /opt/node/bin/node /usr/local/bin/node && " +
                "ln -sf /opt/node/bin/npm /usr/local/bin/npm && " +
                "ln -sf /opt/node/bin/npx /usr/local/bin/npx && " +
                "rm -f /tmp/node.tar.xz",
            timeoutMs = EXTRACT_TIMEOUT_MS,
        ).fold(
            onSuccess = {},
            onFailure = { throw ubuntuFail("Node.js extraction/symlinking failed", it) },
        )
        val nodeV = ubuntu.exec("node -v").getOrNull()?.trim()
        log("Node.js ready: ${nodeV ?: "v$NODE_VERSION"}")
    }

    /** Fetches the expected sha256 for [fileName] from nodejs.org (host side). */
    private suspend fun fetchExpectedSha256(fileName: String): String? {
        val request = Request.Builder()
            .url("https://nodejs.org/dist/v$NODE_VERSION/SHASUMS256.txt")
            .header("User-Agent", "OpenChat/0.1 (Android)")
            .build()
        return try {
            SseHttp.withResponse(request, client = Http.client) { response ->
                if (!response.isSuccessful) return@withResponse null
                response.body?.string().orEmpty()
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.endsWith("  $fileName") || it.endsWith(" $fileName") }
                    ?.substringBefore(' ')
                    ?.trim()
                    ?.takeIf { it.length == 64 }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            log("SHASUMS256.txt fetch failed: ${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    private fun ubuntuFail(detail: String, t: Throwable?): ProviderException =
        ProviderException(Errors.ubuntuFailure(appendCause(detail, t)))

    private fun opencodeFail(detail: String, t: Throwable?): ProviderException =
        ProviderException(Errors.opencodeFailure(appendCause(detail, t)))

    private fun appendCause(detail: String, t: Throwable?): String {
        val cause = t?.message?.let { Redact.scrub(it.take(300)) } ?: ""
        return if (cause.isBlank()) detail else "$detail: $cause"
    }

    private fun tail(out: String, lines: Int = 8): List<String> =
        out.trim().lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines)

    private fun log(line: String) = onLog("[opencode] $line")

    private companion object {
        const val NODE_VERSION = "20.18.1"
        const val APT_TIMEOUT_MS = 600_000L
        const val NPM_TIMEOUT_MS = 900_000L
        const val NODE_DOWNLOAD_TIMEOUT_MS = 600_000L
        const val EXTRACT_TIMEOUT_MS = 600_000L
        const val VERSION_TIMEOUT_MS = 60_000L
    }
}
