package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.StatusPill
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Model create/edit screen (spec §9): all model fields (name, provider,
 * baseUrl override, context/max tokens, temperature, reasoning, enabled) with
 * real Test request, Duplicate, Set-as-default and Delete actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditScreen(nav: NavHostController, modelId: String) {
    val isNew = modelId == "new"
    val models by AppGraph.models.models.collectAsState()
    val providers by AppGraph.providers.providers.collectAsState()
    val existing: AIModel? = models.firstOrNull { it.id == modelId }
    val scope = rememberCoroutineScope()

    var displayName by remember(existing) { mutableStateOf(existing?.displayName ?: "") }
    var modelName by remember(existing) { mutableStateOf(existing?.modelName ?: "") }
    var providerId by remember(existing) { mutableStateOf(existing?.providerId ?: "") }
    var providerError by remember { mutableStateOf(false) }
    var baseUrl by remember(existing) { mutableStateOf(existing?.baseUrl ?: "") }
    var contextLength by remember(existing) { mutableStateOf((existing?.contextLength ?: 8192).toString()) }
    var maxTokens by remember(existing) { mutableStateOf((existing?.maxTokens ?: 2048).toString()) }
    var temperature by remember(existing) { mutableFloatStateOf(((existing?.temperature ?: 0.7)).toFloat()) }
    var reasoning by remember(existing) { mutableStateOf(existing?.reasoning ?: false) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }

    var providerMenuOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun draft(): AIModel = AIModel(
        id = existing?.id ?: "",
        displayName = displayName.trim().ifBlank { "model" },
        providerId = providerId,
        modelName = modelName.trim().ifBlank { "model" },
        baseUrl = baseUrl.trim().ifBlank { null },
        contextLength = contextLength.toIntOrNull() ?: 8192,
        maxTokens = maxTokens.toIntOrNull() ?: 2048,
        temperature = temperature.toDouble(),
        reasoning = reasoning,
        enabled = enabled,
    )

    fun save() {
        if (displayName.isBlank() || modelName.isBlank()) {
            toast("Display name and model name are required")
            return
        }
        if (providerId.isBlank()) {
            providerError = true
            toast("Select a provider first")
            return
        }
        AppGraph.models.upsert(draft().copy(id = existing?.id ?: UUID.randomUUID().toString()))
        nav.popBackStack()
    }

    fun test() {
        testing = true
        testResult = null
        scope.launch {
            val r = AppGraph.models.testModel(draft())
            testing = false
            testResult = r
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New model" else "Edit model") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
                actions = {
                    TextButton(onClick = { save() }) { Text("Save") }
                },
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
            LabeledTextField(
                label = "Display name",
                value = displayName,
                onValueChange = { displayName = it },
                supportingText = "Shown in pickers, e.g. \"GPT-4o mini\"",
            )
            LabeledTextField(
                label = "Model name (API id)",
                value = modelName,
                onValueChange = { modelName = it },
                supportingText = "Sent to the API, e.g. gpt-4o-mini, claude-3-5-sonnet-20240620, llama3.1",
            )

            // Provider dropdown (required).
            ExposedDropdownMenuBox(
                expanded = providerMenuOpen,
                onExpandedChange = { providerMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = providers.firstOrNull { it.id == providerId }?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    placeholder = { Text("Select provider…") },
                    isError = providerError,
                    supportingText = if (providerError) {
                        { Text("Provider is required — create one under Providers") }
                    } else null,
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = providerMenuOpen,
                    onDismissRequest = { providerMenuOpen = false },
                ) {
                    if (providers.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No providers yet — add one under Providers") },
                            onClick = { providerMenuOpen = false },
                        )
                    }
                    providers.forEach { p: Provider ->
                        DropdownMenuItem(
                            text = { Text("${p.name} (${p.type.name})") },
                            onClick = {
                                providerId = p.id
                                providerError = false
                                providerMenuOpen = false
                            },
                        )
                    }
                }
            }

            LabeledTextField(
                label = "Base URL override (optional)",
                value = baseUrl,
                onValueChange = { baseUrl = it },
                supportingText = "Empty = use the provider's Base URL",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledTextField(
                    label = "Context length",
                    value = contextLength,
                    onValueChange = { contextLength = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    label = "Max tokens",
                    value = maxTokens,
                    onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            Column {
                Text(
                    "Temperature: " + String.format(java.util.Locale.US, "%.1f", temperature),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = (it * 10).toInt() / 10f },
                    valueRange = 0f..2f,
                    steps = 20,
                )
            }

            SwitchRow("Reasoning model (sends as normal chat, keeps defaults)", reasoning) { reasoning = it }
            SwitchRow("Enabled", enabled) { enabled = it }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save() }) { Text("Save") }
                OutlinedButton(onClick = { test() }, enabled = !testing) {
                    Text(if (testing) "Testing…" else "Test")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isNew) {
                    OutlinedButton(onClick = {
                        AppGraph.models.duplicate(modelId).fold(
                            { copy ->
                                toast("Duplicated as \"${copy.displayName}\"")
                                nav.navigate("settings/model/${copy.id}")
                            },
                            { toast("Duplicate failed: ${it.message ?: "unknown error"}") },
                        )
                    }) { Text("Duplicate") }
                    OutlinedButton(onClick = {
                        AppGraph.models.setDefault(modelId)
                        toast("Default model set")
                    }) { Text("Set as default") }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            testResult?.let { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusPill(if (r.isSuccess) "✓ model responded" else "✕ failed", ok = r.isSuccess)
                    Text(
                        r.fold({ it }, { it.message ?: "Test failed" }),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${displayName}\"?") },
            text = { Text("The model configuration is removed. Conversations keep their history.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    AppGraph.models.remove(modelId)
                    nav.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** Labeled switch row used by several settings screens. */
@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
