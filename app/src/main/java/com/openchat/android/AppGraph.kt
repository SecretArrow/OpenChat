package com.openchat.android

import android.content.Context
import com.openchat.android.ai.ChatService
import com.openchat.android.ai.ModelManager
import com.openchat.android.ai.OllamaManager
import com.openchat.android.ai.ProviderManager
import com.openchat.android.ai.local.LocalInferenceEngine
import com.openchat.android.ai.local.LocalModelManager
import com.openchat.android.ai.opencode.OpenCodeController
import com.openchat.android.bg.RuntimeServiceController
import com.openchat.android.core.net.Http
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SecretStore
import com.openchat.android.core.storage.SettingsStore
import com.openchat.android.core.util.ErrorReport
import com.openchat.android.terminal.TerminalManager
import com.openchat.android.ubuntu.ProotRunner
import com.openchat.android.ubuntu.UbuntuInstaller
import com.openchat.android.ubuntu.UbuntuProcessManager
import com.openchat.android.ubuntu.UbuntuRuntime
import com.openchat.android.workspace.FileManagerService
import com.openchat.android.workspace.WorkspaceManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single service graph (spec §17): UI talks ONLY to these singletons.
 * All services are lazy so construction order can never create cycles; the
 * cross-service hooks (postInstallHook / onReset / workspaceProvider) are
 * wired once at first access.
 */
object AppGraph {

    lateinit var appContext: Context
        private set

    /** Graph-wide scope for fire-and-forget wiring (survives as long as the process). */
    val graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            // Warm the shared HTTP client + load persisted stores off the main path.
            Http.client
            // Every copied error report carries the Ubuntu runtime evidence
            // (install/apt/proot log + the failing command's output tail) so a
            // pasted bug report names the real cause (v0.1.13).
            ErrorReport.registerProvider("Ubuntu runtime log (last 120 lines)") {
                ubuntu.log.value.takeLast(120)
            }
            ErrorReport.registerProvider("Ubuntu failure output tail") {
                ubuntu.failureTail.value
            }
            initialized = true
        }
    }

    val secrets: SecretStore by lazy { SecretStore(File(appContext.filesDir, "secrets.bin")) }

    val json: JsonStore by lazy { JsonStore(appContext.filesDir) }

    val settings: SettingsStore by lazy { SettingsStore(json) }

    val providers: ProviderManager by lazy { ProviderManager(secrets, json) }

    val models: ModelManager by lazy { ModelManager(json, providers) }

    val ollama: OllamaManager by lazy { OllamaManager(json, secrets) }

    /** On-device GGUF models: catalog + downloads + imports (files only). */
    val localModels: LocalModelManager by lazy {
        LocalModelManager(json, File(appContext.filesDir, "localmodels"), appContext.contentResolver)
    }

    /** The single on-device inference slot (one loaded model at a time). */
    val localEngine: LocalInferenceEngine by lazy {
        LocalInferenceEngine(appContext, localModels).also { engine ->
            // Protect the process while generating (§14/§25); release the
            // keep-alive afterwards when no terminal sessions need it.
            engine.onGenerationActive = { active ->
                if (active) {
                    RuntimeServiceController.start(appContext)
                } else if (AppGraph.terminal.sessions.value.isEmpty()) {
                    RuntimeServiceController.stop(appContext)
                }
            }
            // Vulkan offload follows the Local-models GPU setting; the toggle
            // takes effect on the next model load (honest CPU fallback inside
            // llama.cpp when the device has no Vulkan driver).
            engine.gpuLayers =
                if (settings.settings.value.localGpu) LocalInferenceEngine.GPU_LAYERS_MAX else 0
            graphScope.launch {
                settings.settings.collect { s ->
                    engine.gpuLayers =
                        if (s.localGpu) LocalInferenceEngine.GPU_LAYERS_MAX else 0
                }
            }
            // A deleted model must not keep weights resident.
            localModels.onDeleteHook = { spec -> engine.unloadIfLoaded(spec.id) }
        }
    }

    val ubuntu: UbuntuRuntime by lazy {
        val runtime = UbuntuRuntime(
            appContext,
            json,
            settings,
            ProotRunner(appContext, settings),
            UbuntuInstaller(appContext),
        )
        // §3–4: after Ubuntu install completes, best-effort install OpenCode CLI.
        runtime.postInstallHook = { opencode.install() }
        // Reset must close live sessions before the rootfs is deleted.
        runtime.onReset = { terminal.closeAll() }
        runtime
    }

    val terminal: TerminalManager by lazy { TerminalManager(ubuntu, appContext) }

    val processes: UbuntuProcessManager by lazy {
        UbuntuProcessManager(terminal, ubuntu, json).also { pm ->
            pm.workspaceProvider = { id ->
                workspaces.workspaces.value.firstOrNull { it.id == id }
            }
        }
    }

    val workspaces: WorkspaceManager by lazy { WorkspaceManager(appContext, ubuntu, json) }

    val opencode: OpenCodeController by lazy {
        OpenCodeController(ubuntu, providers, models, json, settings)
    }

    val chat: ChatService by lazy { ChatService(json, providers, models, opencode, settings, localEngine) }

    val files: FileManagerService by lazy { FileManagerService(appContext) }
}
