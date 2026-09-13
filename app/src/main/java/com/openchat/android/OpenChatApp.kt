package com.openchat.android

import android.app.Application
import com.openchat.android.core.upd.AppUpdater
import com.openchat.android.core.util.CrashLog
import com.openchat.android.core.util.Redact
import kotlinx.coroutines.launch

/**
 * Application entry: initializes the service graph once and restores the
 * honest background-process states after a possible app kill (spec §25).
 */
class OpenChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Crash diagnostics FIRST: any failure below this line is recorded on
        // device (Settings → About → Crash log) instead of being a mystery.
        CrashLog.install(this)
        AppGraph.init(this)
        // Touch lazy graph members that must restore persisted state early.
        runCatching { AppGraph.processes }
            .onFailure { Redact.w("OpenChat/App", "process restore failed: ${it.message}") }
        runCatching { AppGraph.providers }
        runCatching { AppGraph.models }
        runCatching { AppGraph.settings }

        // Silent update check: at most once per 24 h, never blocks the launch
        // path (async on the graph scope), user-opt-out in Settings → About.
        AppGraph.graphScope.launch {
            runCatching {
                val settings = AppGraph.settings.settings.value
                if (!settings.updateAutoCheck) return@launch
                val outcome = AppUpdater.autoCheck(this@OpenChatApp, AppGraph.json)
                if (outcome.release != null) {
                    AppUpdater.notifyUpdate(this@OpenChatApp, outcome.release)
                }
            }.onFailure {
                Redact.w("OpenChat/App", "update auto-check skipped: ${it.message}")
            }
        }
    }

    /** RAM pressure: free on-device model weights when the system asks (§24). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { AppGraph.localEngine.onTrimMemory(level) }
            .onFailure { Redact.w("OpenChat/App", "trim skip: ${it.message}") }
    }

    companion object {
        /** Intent extra: open Settings → About → Updates (update notification tap). */
        const val EXTRA_OPEN_UPDATES = "openchat.extra.OPEN_UPDATES"
    }
}
