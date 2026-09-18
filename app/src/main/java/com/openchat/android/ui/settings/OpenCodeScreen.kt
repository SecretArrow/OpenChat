package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.OpenCodeStatus
import com.openchat.android.ui.components.SectionHeader
import com.openchat.android.ui.components.StatusPill
import kotlinx.coroutines.launch

/**
 * OpenCode management (spec §28): install / reinstall / update / check-update
 * against the Ubuntu userspace, live log tail, and config sync that writes
 * Open Chat's selected model into the official OpenCode config (spec §29).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeScreen(nav: NavHostController) {
    val status by AppGraph.opencode.status.collectAsState()
    val log by AppGraph.opencode.log.collectAsState()
    val scope = rememberCoroutineScope()

    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun runOp(label: String, op: suspend () -> Result<*>) {
        if (working) return
        scope.launch {
            working = true
            val r = op()
            working = false
            r.fold(
                { v ->
                    feedback = when (v) {
                        is String -> true to "$label: $v"
                        else -> true to "$label completed"
                    }
                },
                { feedback = false to "$label failed: ${it.message ?: "unknown error"}" },
            )
        }
    }

    fun syncCurrentModel() {
        if (working) return
        scope.launch {
            val m = AppGraph.models.defaultModel()
            if (m == null) {
                toast("No default model — set one in Models")
                return@launch
            }
            val p = AppGraph.providers.providers.value.firstOrNull { it.id == m.providerId }
            if (p == null) {
                toast("Provider for \"${m.displayName}\" not found")
                return@launch
            }
            val k = AppGraph.providers.apiKeyFor(p)
            if (k == null) {
                toast("No API key stored for ${p.name} — add it in Providers")
                return@launch
            }
            working = true
            AppGraph.opencode.syncConfig(m, p, k).fold(
                { toast("Config synced: ${p.id}/${m.modelName} → opencode.json") },
                { toast("Sync failed: ${it.message ?: "unknown error"}") },
            )
            working = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenCode") },
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Text(
                            "opencode-ai CLI",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            if (status.installed) "installed" else "not installed",
                            ok = status.installed,
                        )
                    }
                    Text(
                        "Version: ${status.version ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    status.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { runOp("Install") { AppGraph.opencode.install() } },
                    enabled = !working,
                ) { Text(if (status.installed) "Reinstall" else "Install") }
                OutlinedButton(
                    onClick = { runOp("Reinstall") { AppGraph.opencode.reinstall() } },
                    enabled = !working,
                ) { Text("Reinstall") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { runOp("Update") { AppGraph.opencode.update() } },
                    enabled = !working,
                ) { Text("Update") }
                OutlinedButton(
                    onClick = { runOp("Check update") { AppGraph.opencode.checkUpdate() } },
                    enabled = !working,
                ) { Text("Check update") }
            }

            feedback?.let { (ok, msg) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(if (ok) "✓" else "✕", ok = ok)
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionHeader("Log (last 30 lines)")
            Card(Modifier.fillMaxWidth()) {
                Text(
                    log.takeLast(30).joinToString("\n").ifBlank { "(no log output yet)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }

            SectionHeader("Config sync")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Writes Open Chat's selected model into the official OpenCode config " +
                            "(/root/.config/opencode/opencode.json): model id, provider npm package, " +
                            "base URL and API key. Run this after changing providers or keys.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { syncCurrentModel() }, enabled = !working) {
                        Text("Sync current model")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
