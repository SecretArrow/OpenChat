package com.openchat.android

import android.app.Application
import com.openchat.android.core.util.Redact

/**
 * Application entry: initializes the service graph once and restores the
 * honest background-process states after a possible app kill (spec §25).
 */
class OpenChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // Touch lazy graph members that must restore persisted state early.
        runCatching { AppGraph.processes }
            .onFailure { Redact.w("OpenChat/App", "process restore failed: ${it.message}") }
        runCatching { AppGraph.providers }
        runCatching { AppGraph.models }
        runCatching { AppGraph.settings }
    }
}
