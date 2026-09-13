package com.openchat.android.ai.opencode

import com.openchat.android.ai.ModelManager
import com.openchat.android.ai.ProviderManager
import com.openchat.android.ai.providers.ProviderException
import com.openchat.android.ai.providers.toProviderError
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.OpenCodeStatus
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ToolBlock
import com.openchat.android.core.model.Workspace
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SettingsStore
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import com.openchat.android.ubuntu.UbuntuRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.UUID

/**
 * OpenCode lifecycle + execution controller (contract: ai/opencode/OpenCodeController.kt).
 *
 * - [status] is persisted to `opencode_status.json`.
 * - [log] keeps a tail (~200 lines) of every install/update/run line, API keys
 *   scrubbed via [Redact.scrub].
 * - [run] streams `opencode run --model <providerId>/<modelName> "<prompt>"` via
 *   [UbuntuRuntime.execStream]; lines beginning with "$ " are parsed into
 *   [ToolBlock]s (command + captured output) surfaced live through [onTool].
 */
class OpenCodeController(
    private val ubuntu: UbuntuRuntime,
    private val providers: ProviderManager,
    private val models: ModelManager,
    private val json: JsonStore,
    private val settings: SettingsStore,
) {

    private val installer = OpenCodeInstaller(ubuntu) { line -> logLine(line) }

    private val statusState = MutableStateFlow(loadStatus())
    val status: StateFlow<OpenCodeStatus> = statusState

    private val logState = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = logState

    /** Installs OpenCode (skips the heavy chain when already installed and runnable). */
    suspend fun install(): Result<Unit> {
        if (statusState.value.installed) {
            val v = installer.version()
            if (v.isSuccess) {
                setStatus(installed = true, version = v.getOrNull(), message = "Already installed")
                logLine("[opencode] already installed: ${v.getOrNull()}")
                return Result.success(Unit)
            }
        }
        return runInstaller()
    }

    /** Force-reinstalls OpenCode (full apt + node + npm chain). */
    suspend fun reinstall(): Result<Unit> {
        logLine("[opencode] reinstall requested")
        return runInstaller()
    }

    /**
     * `npm install -g opencode-ai@latest` inside the rootfs, then refreshes the
     * recorded version.
     */
    suspend fun update(): Result<Unit> {
        ubuntu.ensureReady().fold(
            onSuccess = {},
            onFailure = { return Result.failure(ProviderException(Errors.ubuntuNotReady())) },
        )
        logLine("[opencode] updating opencode-ai to @latest…")
        ubuntu.exec("npm install -g opencode-ai@latest", timeoutMs = NPM_TIMEOUT_MS).fold(
            onSuccess = {},
            onFailure = {
                return Result.failure(toProviderError(it, "OpenCode update").let { e ->
                    if (e is ProviderException) e else ProviderException(Errors.opencodeFailure(scrub(it.message)))
                })
            },
        )
        val v = installer.version()
        setStatus(installed = v.isSuccess, version = v.getOrNull(), message = if (v.isSuccess) "Updated" else "Update finished, but version check failed")
        return if (v.isSuccess) Result.success(Unit) else Result.failure(v.exceptionOrNull()?.let { toProviderError(it, "OpenCode version check") } ?: ProviderException(Errors.opencodeFailure("version check failed")))
    }

    /**
     * Compares the installed version with `npm view opencode-ai version`.
     * An npm/network failure is an honest failure (never a fake "up to date").
     */
    suspend fun checkUpdate(): Result<String> {
        val installedVersion = statusState.value.version ?: installer.version().getOrNull()
            ?: return Result.failure(ProviderException(Errors.opencodeFailure("OpenCode is not installed; run Install first")))
        val latest = ubuntu.exec("npm view opencode-ai version", timeoutMs = CHECK_TIMEOUT_MS).fold(
            onSuccess = { out -> out.trim().lineSequence().firstOrNull { it.isNotBlank() } },
            onFailure = { null },
        )
        if (latest.isNullOrBlank()) {
            return Result.failure(
                ProviderException(
                    Errors.opencodeFailure(
                        "Could not check for updates: 'npm view opencode-ai version' failed " +
                            "(no network inside the Ubuntu userspace or npm registry unreachable)."
                    )
                )
            )
        }
        setStatus(lastChecked = System.currentTimeMillis(), message = "Checked: latest $latest")
        return if (latest == installedVersion) {
            Result.success("Up to date ($installedVersion)")
        } else {
            Result.success("Update available: $installedVersion → $latest")
        }
    }

    /**
     * Runs a prompt through OpenCode.
     *
     * - Fails honestly when Ubuntu or OpenCode is not ready.
     * - Auto-syncs opencode.json when [SettingsStore] `opencodeAutoSyncConfig` is on
     *   and model/provider/key are resolvable.
     * - cwd is the workspace path (or /root); workspace env vars are exported.
     * - Output lines starting with "$ " begin a [ToolBlock]; following lines are its
     *   output until the next "$ " line or end of stream. Everything else is
     *   response text. Blocks are emitted via [onTool] when complete (exitCode null).
     */
    suspend fun run(
        prompt: String,
        workspace: Workspace?,
        model: AIModel?,
        onTool: (ToolBlock) -> Unit,
    ): Result<String> {
        ubuntu.ensureReady().fold(
            onSuccess = {},
            onFailure = { return Result.failure(ProviderException(Errors.ubuntuNotReady())) },
        )
        if (!statusState.value.installed) {
            // Re-check honestly — the persisted flag may be stale after a reset.
            val v = installer.version()
            if (v.isFailure) {
                return Result.failure(
                    ProviderException(Errors.opencodeFailure("OpenCode is not installed. Install it from Settings → OpenCode."))
                )
            }
            setStatus(installed = true, version = v.getOrNull(), message = "Detected install")
        }

        val provider = model?.let { m -> providers.providers.value.firstOrNull { it.id == m.providerId } }
        val apiKey = provider?.let { providers.apiKeyFor(it) }
        if (settings.settings.value.opencodeAutoSyncConfig && model != null && provider != null && !apiKey.isNullOrBlank()) {
            syncConfig(model, provider, apiKey).fold(
                onSuccess = {},
                onFailure = { t ->
                    logLine("[opencode] config sync failed: ${Redact.scrub(t.message ?: "")}")
                },
            )
        }

        val modelRef = model?.let { m ->
            val provId = provider?.id ?: m.providerId
            "${provId}/${m.modelName}"
        }
        val cwd = workspace?.path?.takeIf { it.isNotBlank() } ?: "/root"
        val env = workspace?.envVars ?: emptyMap()

        val cmd = buildString {
            append("opencode run")
            if (modelRef != null) append(" --model \"").append(modelRef).append("\"")
            append(" \"").append(shellEscape(prompt)).append("\"")
        }
        logLine("[opencode] run in $cwd${if (modelRef != null) " ($modelRef)" else ""}")

        val responseText = StringBuilder()
        var currentCommand: String? = null
        var blockOutput = StringBuilder()

        fun flushBlock() {
            val command = currentCommand ?: return
            currentCommand = null
            val block = ToolBlock(
                id = UUID.randomUUID().toString(),
                label = "Tool",
                command = command,
                output = blockOutput.toString().trimEnd('\n'),
                exitCode = null,
            )
            blockOutput = StringBuilder()
            try {
                onTool(block)
            } catch (t: Throwable) {
                logLine("[opencode] onTool callback failed: ${Redact.scrub(t.message ?: "")}")
            }
        }

        val exec = try {
            ubuntu.execStream(cmd, cwd = cwd, env = env) { line ->
                logLine("oc: " + Redact.scrub(line, apiKey))
                if (line.startsWith("\$ ")) {
                    flushBlock()
                    currentCommand = line.substring(2)
                } else if (currentCommand != null) {
                    blockOutput.append(line).append('\n')
                } else {
                    responseText.append(line).append('\n')
                }
            }
        } catch (ce: CancellationException) {
            flushBlock()
            // Cancelled by the user: keep the partial response, no error (spec §25).
            return Result.success(responseText.toString().trim())
        }
        flushBlock()

        if (exec.isFailure) {
            // If the coroutine itself was cancelled, treat as user-cancel.
            if (!currentCoroutineContext().isActive) {
                return Result.success(responseText.toString().trim())
            }
            val t = exec.exceptionOrNull() ?: return Result.success(responseText.toString().trim())
            return Result.failure(toProviderError(t, "OpenCode run"))
        }
        val exitCode = exec.getOrDefault(-1)
        if (exitCode != 0) {
            val tail = responseText.toString().trim().takeLast(400)
            return Result.failure(
                ProviderException(
                    Errors.opencodeFailure(
                        "opencode exited with code $exitCode" +
                            (if (tail.isBlank()) "" else ". Last output: ${Redact.scrub(tail, apiKey)}")
                    )
                )
            )
        }
        return Result.success(responseText.toString().trim())
    }

    /**
     * Writes the official opencode.json into the rootfs via a heredoc with a
     * unique delimiter (the config content can never contain that delimiter).
     */
    suspend fun syncConfig(model: AIModel, provider: Provider, apiKey: String): Result<Unit> {
        ubuntu.ensureReady().fold(
            onSuccess = {},
            onFailure = { return Result.failure(ProviderException(Errors.ubuntuNotReady())) },
        )
        val config = OpenCodeConfigSync.build(provider, model, apiKey)
        val delimiter = "OCEOF_" + UUID.randomUUID().toString().replace("-", "").take(8)
        if (OpenCodeConfigSync.containsLine(config, delimiter)) {
            // Practically impossible (UUID-based delimiter), but stay correct.
            return Result.failure(ProviderException(Errors.opencodeFailure("Config content collides with heredoc delimiter")))
        }
        val mkdir = ubuntu.exec("mkdir -p /root/.config/opencode")
        mkdir.fold(
            onSuccess = {},
            onFailure = { return Result.failure(toProviderError(it, "OpenCode config sync")) },
        )
        val cmd = "cat > $CONFIG_PATH << '$delimiter'\n$config\n$delimiter"
        ubuntu.exec(cmd).fold(
            onSuccess = {},
            onFailure = { return Result.failure(toProviderError(it, "OpenCode config sync")) },
        )
        val verify = ubuntu.exec("test -s $CONFIG_PATH")
        if (verify.isFailure) {
            return Result.failure(ProviderException(Errors.opencodeFailure("opencode.json was not written to $CONFIG_PATH")))
        }
        setStatus(message = "Config synced: ${provider.id}/${model.modelName}")
        logLine("[opencode] config written to $CONFIG_PATH (${provider.id}/${model.modelName})")
        return Result.success(Unit)
    }

    // ------------------------------------------------------------------ internals

    private suspend fun runInstaller(): Result<Unit> {
        val r = installer.install()
        r.fold(
            onSuccess = {
                val v = installer.version().getOrNull()
                setStatus(installed = true, version = v, lastChecked = System.currentTimeMillis(), message = "Installed ${v ?: ""}".trim())
            },
            onFailure = { t ->
                val info = (t as? ProviderException)?.info ?: Errors.opencodeFailure(scrub(t.message))
                setStatus(installed = false, message = info.title + ": " + info.detail)
            },
        )
        return r
    }

    private fun setStatus(
        installed: Boolean = statusState.value.installed,
        version: String? = statusState.value.version,
        lastChecked: Long? = statusState.value.lastChecked,
        message: String? = statusState.value.message,
    ) {
        val next = OpenCodeStatus(
            installed = installed,
            version = version,
            lastChecked = lastChecked,
            message = message?.takeIf { it.isNotBlank() },
        )
        statusState.value = next
        json.writeText(STATUS_FILE, next.toJson().toString())
    }

    private fun loadStatus(): OpenCodeStatus =
        runCatching {
            json.readText(STATUS_FILE)?.let { OpenCodeStatus.fromJson(JSONObject(it)) }
        }.getOrNull() ?: OpenCodeStatus()

    private fun logLine(line: String) {
        logState.value = (logState.value + line).takeLast(LOG_LIMIT)
    }

    private fun scrub(text: String?): String = Redact.scrub(text ?: "", *providersSecrets())

    private fun providersSecrets(): Array<out String?> =
        providers.providers.value.mapNotNull { providers.apiKeyFor(it) }.toTypedArray()

    /** Minimal shell escaping for a double-quoted argument. */
    private fun shellEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")

    private companion object {
        const val STATUS_FILE = "opencode_status.json"
        const val CONFIG_PATH = "/root/.config/opencode/opencode.json"
        const val LOG_LIMIT = 200
        const val NPM_TIMEOUT_MS = 900_000L
        const val CHECK_TIMEOUT_MS = 120_000L
    }
}
