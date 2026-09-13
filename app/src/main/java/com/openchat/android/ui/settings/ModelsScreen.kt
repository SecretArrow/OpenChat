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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.StatusPill

/**
 * Model list (spec §9) grouped by provider with a header row per provider.
 * Rows show displayName + technical modelName (monospace), a "Default" badge,
 * and a live enable switch. Tap opens the editor; FAB creates a new model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(nav: NavHostController) {
    val models by AppGraph.models.models.collectAsState()
    val providers by AppGraph.providers.providers.collectAsState()
    val defaultModelId by AppGraph.models.defaultModelId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("settings/model/new") }) {
                Icon(AppIcons.Add, contentDescription = "Add model")
            }
        },
    ) { pad ->
        if (models.isEmpty()) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No models yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap + to add a model (e.g. gpt-4o-mini, claude-sonnet, llama3.1). " +
                        "A model needs a provider — create one under Providers first.",
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
                providers.forEach { provider: Provider ->
                    val group = models.filter { it.providerId == provider.id }
                    if (group.isNotEmpty()) {
                        item(key = "header-${provider.id}") {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                        items(group, key = { it.id }) { m ->
                            ModelRow(
                                model = m,
                                isDefault = m.id == defaultModelId,
                                onOpen = { nav.navigate("settings/model/${m.id}") },
                                onToggle = { AppGraph.models.setEnabled(m.id, it) },
                            )
                        }
                    }
                }
                val orphans = models.filter { m -> providers.none { it.id == m.providerId } }
                if (orphans.isNotEmpty()) {
                    item(key = "header-other") {
                        Text(
                            "Other / missing provider",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    items(orphans, key = { "o-${it.id}" }) { m ->
                        ModelRow(
                            model = m,
                            isDefault = m.id == defaultModelId,
                            onOpen = { nav.navigate("settings/model/${m.id}") },
                            onToggle = { AppGraph.models.setEnabled(m.id, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: AIModel, isDefault: Boolean, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    if (isDefault) StatusPill("Default", ok = null)
                    if (!model.enabled) StatusPill("disabled", ok = false)
                }
                Text(
                    model.modelName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = model.enabled, onCheckedChange = onToggle)
        }
    }
}
