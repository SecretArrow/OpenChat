package com.openchat.android

import android.content.Context
import com.openchat.android.ai.ChatService
import com.openchat.android.ai.ModelManager
import com.openchat.android.ai.OllamaManager
import com.openchat.android.ai.ProviderManager
import com.openchat.android.ai.opencode.OpenCodeController
import com.openchat.android.core.net.Http
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SecretStore
import com.openchat.android.core.storage.SettingsStore
import com.openchat.android.terminal.TerminalManager
import com.openchat.android.ubuntu.ProotRunner
import com.openchat.android.ubuntu.UbuntuInstaller
import com.openchat.android.ubuntu.UbuntuProcessManager
import com.openchat.android.ubuntu.UbuntuRuntime
import com.openchat.android.workspace.FileManagerService
import com.openchat.android.workspace.WorkspaceManager
import java.io.File

/**
 * The single service graph (spec §17): UI talks ONLY to these singletons.
 * All services are lazy so construction order can never create cycles; the
 * cross-service hooks (postInstallHook / onReset / workspaceProvider) are
 * wired once at first access.
 */
object AppGraph {

    lateinit var appContext: Context
        private set

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            // Warm the shared HTTP client + load persisted stores off the main path.
            Http.client
            initialized = true
        }
    }

    val secrets: SecretStore by lazy { SecretStore(File(appContext.filesDir, "secrets.bin")) }

    val json: JsonStore by lazy { JsonStore(appContext.filesDir) }

    val settings: SettingsStore by lazy { SettingsStore(json) }

    val providers: ProviderManager by lazy { ProviderManager(secrets, json) }

    val models: ModelManager by lazy { ModelManager(json, providers) }

    val ollama: OllamaManager by lazy { OllamaManager(json, secrets) }

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

    val chat: ChatService by lazy { ChatService(json, providers, models, opencode, settings) }

    val files: FileManagerService by lazy { FileManagerService(appContext) }
}
