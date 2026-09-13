package com.openchat.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.openchat.android.core.storage.AppSettings
import com.openchat.android.ui.nav.AppNav
import com.openchat.android.ui.theme.OpenChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AppGraph
        setContent {
            val settings: AppSettings by graph.settings.settings.collectAsState()
            OpenChatTheme(appearance = settings.appearance) {
                AppNav()
            }
        }
    }
}
