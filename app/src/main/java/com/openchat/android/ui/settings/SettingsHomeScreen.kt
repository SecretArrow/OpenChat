package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.OpenCodeStatus
import com.openchat.android.core.model.UbuntuState
import com.openchat.android.core.model.UbuntuStatus
import com.openchat.android.ui.components.SectionHeader
import com.openchat.android.ui.components.StatusPill
import com.openchat.android.ui.components.formatDate
import com.openchat.android.ui.nav.Routes
import kotlinx.coroutines.launch

/**
 * Settings home (spec §27): live Ubuntu/OpenCode status cards plus entries for
 * every configuration screen. All entries navigate via [Routes].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ubuntuStatus by AppGraph.ubuntu.status.collectAsState()
    val opencodeStatus by AppGraph.opencode.status.collectAsState()
    var ubuntuBusy by remember { mutableStateOf(false) }
    var opencodeBusy by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    val rows = listOf(
        "Providers" to Routes.PROVIDERS,
        "Models" to Routes.MODELS,
        "Ollama" to Routes.OLLAMA,
        "Local models (on-device)" to Routes.LOCAL,
        "OpenCode" to Routes.OPENCODE,
        "Ubuntu userspace" to Routes.UBUNTU,
        "Terminal" to Routes.TERMINAL_SETTINGS,
        "Workspace" to Routes.WORKSPACE,
        "Background processes" to Routes.PROCESSES,
        "Security" to Routes.SECURITY,
        "Storage" to Routes.STORAGE,
        "About" to Routes.ABOUT,
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            item { UbuntuStatusCard(ubuntuStatus, busy = ubuntuStatus.state.busy || ubuntuBusy) { label, action ->
                if (ubuntuBusy || ubuntuStatus.state.busy) return@UbuntuStatusCard
                ubuntuBusy = true
                scope.launch {
                    action().fold(
                        { toast("$label completed") },
                        { toast("$label failed: ${it.message ?: "unknown error"}") },
                    )
                    ubuntuBusy = false
                }
            } }
            item { OpenCodeStatusCard(opencodeStatus, busy = opencodeBusy) { action ->
                if (opencodeBusy) return@OpenCodeStatusCard
                opencodeBusy = true
                scope.launch {
                    action().fold(
                        { toast("OpenCode installed") },
                        { toast("Install failed: ${it.message ?: "unknown error"}") },
                    )
                    opencodeBusy = false
                }
            } }

            item { SectionHeader("Configuration") }
            items(rows) { row ->
                SettingsRow(label = row.first, onClick = { nav.navigate(row.second) })
            }

            item { SectionHeader("Appearance") }
            item {
                val settings by AppGraph.settings.settings.collectAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("dark", "light", "system").forEach { option ->
                        FilterChip(
                            selected = settings.appearance == option,
                            onClick = { AppGraph.settings.update { it.copy(appearance = option) } },
                            label = { Text(option) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Live Ubuntu install/runtime status with Install/Repair actions. */
@Composable
private fun UbuntuStatusCard(
    status: UbuntuStatus,
    busy: Boolean,
    onAction: (String, suspend () -> Result<Unit>) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ubuntu userspace",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(status.state.name, ok = status.state == UbuntuState.READY)
            }
            Text(
                "${status.version ?: "not installed"} · ${status.arch ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Last updated: ${formatDate(status.lastUpdated)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            status.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (status.state.busy) {
                LinearProgressIndicator(
                    progress = { status.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction("Install") { AppGraph.ubuntu.install() } },
                    enabled = !busy && (status.state == UbuntuState.NOT_INSTALLED || status.state == UbuntuState.ERROR),
                ) { Text(if (status.installed) "Reinstall" else "Install") }
                OutlinedButton(
                    onClick = { onAction("Repair") { AppGraph.ubuntu.repair() } },
                    enabled = !busy && status.installed,
                ) { Text("Repair") }
            }
        }
    }
}

/** Live OpenCode CLI status with a direct Install shortcut. */
@Composable
private fun OpenCodeStatusCard(
    status: OpenCodeStatus,
    busy: Boolean,
    onInstall: (suspend () -> Result<Unit>) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "OpenCode CLI",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    if (status.installed) "installed" else "not installed",
                    ok = status.installed,
                )
            }
            Text(
                status.version ?: "version unknown",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            status.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Button(
                onClick = { onInstall { AppGraph.opencode.install() } },
                enabled = !busy,
            ) { Text(if (status.installed) "Reinstall" else "Install") }
        }
    }
}

/** One tappable settings entry row. */
@Composable
private fun SettingsRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
    }
}
