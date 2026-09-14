package com.openchat.android.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderPreset
import com.openchat.android.ui.components.AppIcons

/**
 * Provider list (spec §8): every configured AI provider with a live enable
 * switch; tapping a row opens the editor. FAB opens the preset picker —
 * one tap adds AgentRouter, NVIDIA NIM, OpenRouter, Poolside, CommandCode,
 * NaraRouter, Zyloo, BigModel/Zhipu or TokenRouter with Base URL, auth header
 * and endpoints pre-filled; "Custom" keeps the manual flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(nav: NavHostController) {
    val providers by AppGraph.providers.providers.collectAsState()
    var showPresetPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = {
                    IconButtonBack { nav.popBackStack() }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showPresetPicker = true }) {
                Icon(AppIcons.Add, contentDescription = "Add provider")
            }
        },
    ) { pad ->
        if (providers.isEmpty()) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No providers yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap + to add a provider from a preset (OpenRouter, NVIDIA NIM, " +
                        "AgentRouter and more) or configure any OpenAI-compatible / Anthropic " +
                        "endpoint manually. Models belong to providers, so add one before " +
                        "configuring models.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
            ) {
                items(providers, key = { it.id }) { p ->
                    ProviderRow(
                        provider = p,
                        onOpen = { nav.navigate("settings/provider/${p.id}") },
                        onToggle = { AppGraph.providers.setEnabled(p.id, it) },
                    )
                }
            }
        }
    }

    if (showPresetPicker) {
        PresetPickerDialog(
            presets = remember { ProviderPreset.loadFromAssets(AppGraph.appContext) },
            onDismiss = { showPresetPicker = false },
            onPick = { preset ->
                showPresetPicker = false
                nav.navigate("settings/provider/new?preset=${preset.id}")
            },
            onCustom = {
                showPresetPicker = false
                nav.navigate("settings/provider/new")
            },
        )
    }
}

@Composable
private fun PresetPickerDialog(
    presets: List<ProviderPreset>,
    onDismiss: () -> Unit,
    onPick: (ProviderPreset) -> Unit,
    onCustom: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCustom) { Text("Custom provider") } },
        title = { Text("Add provider") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                item {
                    Text(
                        "Presets pre-fill Base URL, auth header and endpoints — " +
                            "paste your API key and fetch the model list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                items(presets, key = { it.id }) { preset ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(preset) }
                            .padding(vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                preset.name,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                preset.connections.keys.joinToString(" / ").uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val first = preset.connections.values.firstOrNull()
                        if (first != null && first.baseUrl.isNotBlank()) {
                            Text(
                                first.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                "Custom base URL — set your server address",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
    )
}

@Composable
private fun ProviderRow(provider: Provider, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
            ) {
                Text(provider.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${provider.type.name} · ${provider.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = provider.enabled, onCheckedChange = onToggle)
        }
    }
}

/** Shared back-arrow icon button used by all settings screens. */
@Composable
internal fun IconButtonBack(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(AppIcons.Back, contentDescription = "Back")
    }
}
