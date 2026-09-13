package com.openchat.android.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.BuildConfig
import com.openchat.android.ui.components.CopyIconButton
import com.openchat.android.ui.components.SectionHeader

/**
 * About screen: real build/device facts (BuildConfig version, device ABI,
 * Android release), the feature checklist and the deliberate targetSdk 28
 * rationale (spec §2) plus the upstream repository URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Open Chat", style = MaterialTheme.typography.headlineSmall)
            Text(
                "An AI coding environment: a real Ubuntu userspace with the OpenCode " +
                    "CLI agent and a multi-provider chat client — all on-device.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} · ${Build.SUPPORTED_ABIS[0]} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            SectionHeader("What works")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FeatureBullet("Ubuntu 22.04/24.04 base rootfs (checksum-verified) via proot — apt, git, python3, Node 20")
                    FeatureBullet("OpenCode CLI coding agent running inside the userspace with tool output in chat")
                    FeatureBullet("Chat with OpenAI, Anthropic, Gemini, OpenRouter, custom OpenAI-compatible endpoints and LAN Ollama")
                    FeatureBullet("Streaming responses, markdown rendering, copyable code blocks, tool blocks")
                    FeatureBullet("VT100/xterm terminal: 256 colors, scrollback, extra keys, copy/paste, multiple sessions")
                    FeatureBullet("File manager + editor across app data, rootfs and workspaces, SAF import/export")
                    FeatureBullet("Workspace manager and background process supervisor with foreground keep-alive")
                    FeatureBullet("Keystore-backed secret storage; keys are never displayed or logged")
                }
            }

            SectionHeader("Why targetSdk 28?")
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "The app intentionally targets SDK 28. Newer target levels forbid executing " +
                        "binaries stored in app-private data (W^X exec restrictions), which proot and " +
                        "the Ubuntu userspace require. This is a deliberate trade-off: no broad storage " +
                        "permissions are requested — shared files always go through the system document " +
                        "picker (SAF), and network access uses the normal INTERNET permission only.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            SectionHeader("Source")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "https://github.com/SecretArrow/OpenChat",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                CopyIconButton("https://github.com/SecretArrow/OpenChat")
            }
        }
    }
}

@Composable
private fun FeatureBullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodySmall)
}
