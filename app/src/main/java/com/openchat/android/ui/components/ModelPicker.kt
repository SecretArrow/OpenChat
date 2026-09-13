package com.openchat.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openchat.android.AppGraph
import com.openchat.android.core.model.AIModel

/**
 * Dropdown picker for enabled AI models, grouped by provider name.
 * Selecting a model only changes which model is used — it must never
 * clear the current conversation (ChatService.setActiveModel handles that).
 *
 * @param selectedModelId current conversation model id (may be null → the default model is shown).
 * @param onSelect invoked with the chosen enabled model.
 * @param modifier layout modifier.
 * @param compact compact rendering for toolbars (text button instead of a full text field).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    selectedModelId: String?,
    onSelect: (AIModel) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val models by AppGraph.models.models.collectAsState()
    val providers by AppGraph.providers.providers.collectAsState()
    val defaultModelId by AppGraph.models.defaultModelId.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val enabledModels = models.filter { it.enabled }
    val selected = enabledModels.firstOrNull { it.id == selectedModelId }
        ?: enabledModels.firstOrNull { it.id == defaultModelId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        if (compact) {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.menuAnchor(),
            ) {
                Text(
                    selected?.displayName ?: "Model",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Select model")
            }
        } else {
            OutlinedTextField(
                value = selected?.displayName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                placeholder = { Text(if (enabledModels.isEmpty()) "No models yet" else "Select model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (enabledModels.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No models yet → Settings → Models") },
                    onClick = { expanded = false },
                )
            } else {
                var shownAny = false
                providers.forEach { p ->
                    val group = enabledModels.filter { it.providerId == p.id }
                    if (group.isNotEmpty()) {
                        shownAny = true
                        DropdownMenuItem(
                            text = {
                                Text(
                                    p.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                        group.forEach { m -> ModelMenuItem(m, p.name, m.id == selected?.id) { picked ->
                            expanded = false
                            onSelect(picked)
                        } }
                    }
                }
                // Models whose provider no longer exists still deserve an entry.
                val orphans = enabledModels.filter { m -> providers.none { it.id == m.providerId } }
                if (orphans.isNotEmpty()) {
                    if (shownAny) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Other",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    orphans.forEach { m -> ModelMenuItem(m, "unknown provider", m.id == selected?.id) { picked ->
                        expanded = false
                        onSelect(picked)
                    } }
                }
            }
        }
    }
}

@Composable
private fun ModelMenuItem(
    model: AIModel,
    providerName: String,
    isSelected: Boolean,
    onSelect: (AIModel) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${model.modelName} · $providerName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        trailingIcon = if (isSelected) {
            { Icon(AppIcons.Check, contentDescription = "Selected") }
        } else null,
        onClick = { onSelect(model) },
        modifier = Modifier.padding(vertical = 0.dp),
    )
}
