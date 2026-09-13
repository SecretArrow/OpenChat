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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.Provider
import com.openchat.android.ui.components.AppIcons

/**
 * Provider list (spec §8): every configured AI provider with a live enable
 * switch; tapping a row opens the editor. FAB adds a new provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(nav: NavHostController) {
    val providers by AppGraph.providers.providers.collectAsState()

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
            FloatingActionButton(onClick = { nav.navigate("settings/provider/new") }) {
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
                    "Tap + to add OpenAI, Anthropic, Gemini, OpenRouter, a LAN Ollama server " +
                        "or any OpenAI-compatible endpoint. Models belong to providers, so add " +
                        "one before configuring models.",
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
