package com.openchat.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.openchat.android.core.storage.AppSettings
import com.openchat.android.ui.nav.AppNav
import com.openchat.android.ui.theme.OpenChatTheme

class MainActivity : ComponentActivity() {

    /** Bumped whenever the activity receives EXTRA_OPEN_UPDATES (notification tap). */
    private var openUpdatesTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeOpenUpdatesIntent(intent)
        val graph = AppGraph
        setContent {
            val settings: AppSettings by graph.settings.settings.collectAsState()
            OpenChatTheme(appearance = settings.appearance) {
                AppNav(openUpdatesTick = openUpdatesTick)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the update-notification tap reuses the existing activity.
        consumeOpenUpdatesIntent(intent)
    }

    private fun consumeOpenUpdatesIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(OpenChatApp.EXTRA_OPEN_UPDATES, false) == true) {
            openUpdatesTick++
        }
    }
}
