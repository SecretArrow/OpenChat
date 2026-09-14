package com.openchat.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.ai.ModelDiscovery
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderPreset
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.model.PresetMapper
import com.openchat.android.ui.components.ConfirmDialog
import com.openchat.android.ui.components.KeyValueEditor
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.StatusPill
import kotlinx.coroutines.launch
import java.util.UUID

/** Sensible default base URLs per provider type (spec §8). */
private val defaultBaseUrls: Map<ProviderType, String> = mapOf(
    ProviderType.OPENAI to "https://api.openai.com/v1",
    ProviderType.ANTHROPIC to "https://api.anthropic.com",
    ProviderType.GEMINI to "https://generativelanguage.googleapis.com",
    ProviderType.OPENROUTER to "https://openrouter.ai/api/v1",
    ProviderType.OLLAMA to "http://192.168.1.10:11434",
    ProviderType.CUSTOM_OPENAI to "https://",
)

/**
 * Provider create/edit screen (spec §8). The stored API key is NEVER shown —
 * only its presence ("•••• (saved)"). A new key typed into the field replaces
 * the stored one on Save; "Remove stored key" deletes it immediately.
 * Test Connection performs a real request against the endpoint.
 *
 * Presets: launched with a [presetId] (Providers → + → preset), Base URL,
 * auth header and endpoints come from the bundled catalog; multi-protocol
 * presets (e.g. AgentRouter, OpenRouter, NaraRouter) offer a protocol chip
 * row that re-maps Base URL + transport live.
 *
 * "Fetch models" performs real model discovery (GET /models) and lets the
 * user tick which discovered models to add — no more typing model ids by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(nav: NavHostController, providerId: String, presetId: String? = null) {
    val isNew = providerId == "new"
    val providers by AppGraph.providers.providers.collectAsState()
    val existing: Provider? = providers.firstOrNull { it.id == providerId }
    val scope = rememberCoroutineScope()

    val preset: ProviderPreset? = remember(presetId) {
        presetId?.takeIf { it.isNotBlank() }?.let { id ->
            ProviderPreset.loadFromAssets(AppGraph.appContext).firstOrNull { it.id == id }
        }
    }
    // Concrete protocol for multi-protocol presets ("openai" | "anthropic" | "auto").
    var protocol by remember(preset) {
        mutableStateOf(preset?.let { PresetMapper.resolveProtocol(it, null) } ?: "")
    }

    fun presetProvider(): Provider? = preset?.let { PresetMapper.toProvider(it, protocol) }

    var name by remember(existing, preset) {
        mutableStateOf(existing?.name ?: presetProvider()?.name ?: "")
    }
    var type by remember(existing, preset) {
        mutableStateOf(existing?.type ?: presetProvider()?.type ?: ProviderType.OPENAI)
    }
    var baseUrl by remember(existing, preset) {
        mutableStateOf(
            existing?.baseUrl
                ?: presetProvider()?.baseUrl?.takeIf { it.isNotBlank() }
                ?: defaultBaseUrls.getValue(ProviderType.OPENAI)
        )
    }
    var baseUrlTouched by remember(existing) { mutableStateOf(existing != null) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var headers by remember(existing) { mutableStateOf(existing?.headers ?: emptyMap()) }
    var keyField by remember { mutableStateOf("") }

    // Presence of a stored key only — the value is never read into the UI.
    var storedKeyPresent by remember(existing) {
        mutableStateOf(existing != null && AppGraph.providers.apiKeyFor(existing) != null)
    }

    var typeMenuOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Result<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var fetching by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<String>?>(null) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Draft provider reflecting the current fields (no id committed yet). */
    fun draft(): Provider = Provider(
        id = existing?.id ?: "",
        name = name.trim().ifBlank { "draft" },
        type = type,
        baseUrl = baseUrl.trim(),
        apiKeyRef = existing?.apiKeyRef,
        headers = headers.filter { it.key.isNotBlank() },
        enabled = true,
    )

    /**
     * Persists the current form WITHOUT leaving the screen; returns the
     * provider id (null on validation failure). Used before attaching
     * discovered models and by the regular Save (which pops afterwards).
     */
    fun saveInPlace(): String? {
        if (name.isBlank()) {
            toast("Name is required")
            return null
        }
        if (baseUrl.isBlank()) {
            toast("Base URL is required")
            return null
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val p = Provider(
            id = id,
            name = name.trim(),
            type = type,
            baseUrl = baseUrl.trim(),
            apiKeyRef = existing?.apiKeyRef,
            headers = headers.filter { it.key.isNotBlank() },
            enabled = enabled,
        )
        AppGraph.providers.upsert(p)
        if (keyField.isNotBlank()) {
            AppGraph.providers.setApiKey(p, keyField)
            storedKeyPresent = true
            keyField = ""
        }
        return id
    }

    fun save() {
        if (saveInPlace() != null) nav.popBackStack()
    }

    fun test() {
        testing = true
        testResult = null
        scope.launch {
            val r = AppGraph.providers.testConnection(draft(), keyField.ifBlank { null })
            testing = false
            testResult = r
        }
    }

    fun fetchModels() {
        val key = keyField.ifBlank { existing?.let { AppGraph.providers.apiKeyFor(it) } }
        if (key.isNullOrBlank() && existing == null) {
            toast("Paste your API key first — providers need it to list models")
            return
        }
        fetching = true
        scope.launch {
            val headerOverride = if (preset != null && PresetMapper.usesApiKeyHeader(preset, protocol)) {
                PresetMapper.apiKeyHeaderName(preset, protocol) to (key ?: "")
            } else {
                null
            }
            val path = preset?.let { PresetMapper.modelsPath(it, protocol) }
            val r = ModelDiscovery.fetch(draft(), key, path, headerOverride)
            fetching = false
            r.fold(
                { ids ->
                    if (ids.isEmpty()) {
                        toast("Endpoint returned no models")
                    } else {
                        discoveredModels = ids
                        selectedModels = emptySet()
                    }
                },
                { toast("Fetch failed: ${it.message ?: "unknown error"}") },
            )
        }
    }

    /** Adds the ticked discovered models to the (just-saved) provider. */
    fun addSelectedModels() {
        val ids = discoveredModels ?: return
        val pid = saveInPlace() ?: return
        val current = AppGraph.models.models.value
        val existingNames = current.filter { it.providerId == pid }.map { it.modelName }.toSet()
        var added = 0
        var skipped = 0
        ids.filter { it in selectedModels }.forEach { mid ->
            if (mid in existingNames) {
                skipped++
            } else {
                AppGraph.models.upsert(
                    AIModel(
                        id = UUID.randomUUID().toString(),
                        displayName = mid,
                        providerId = pid,
                        modelName = mid,
                    )
                )
                added++
            }
        }
        discoveredModels = null
        toast(
            when {
                skipped == 0 -> "Added $added model(s)"
                else -> "Added $added model(s), $skipped already existed"
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New provider" else "Edit provider") },
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
            if (preset != null) {
                StatusPill("Preset: ${preset.name}", ok = null)
            }

            LabeledTextField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                supportingText = "Shown in pickers, e.g. \"OpenAI main\" or \"Home Ollama\"",
            )

            // Protocol chips for multi-protocol presets (AgentRouter, OpenRouter, NaraRouter).
            if (preset != null && preset.isMultiProtocol()) {
                Text(
                    "Protocol",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    preset.connections.keys.filter { it != "auto" }.forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = {
                                protocol = proto
                                val mapped = PresetMapper.toProvider(preset, proto)
                                type = mapped.type
                                if (!baseUrlTouched) baseUrl = mapped.baseUrl
                            },
                            label = { Text(proto.uppercase()) },
                        )
                    }
                }
            }

            // Type dropdown.
            ExposedDropdownMenuBox(
                expanded = typeMenuOpen,
                onExpandedChange = { typeMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = type.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = typeMenuOpen,
                    onDismissRequest = { typeMenuOpen = false },
                ) {
                    ProviderType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.name) },
                            onClick = {
                                type = t
                                typeMenuOpen = false
                                if (!baseUrlTouched) {
                                    baseUrl = defaultBaseUrls.getValue(t)
                                }
                            },
                        )
                    }
                }
            }

            LabeledTextField(
                label = "Base URL",
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    baseUrlTouched = true
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = presetProvider()?.baseUrl?.takeIf { it.isNotBlank() }
                    ?: defaultBaseUrls.getValue(type),
            )

            LabeledTextField(
                label = if (storedKeyPresent) "API key (saved — type to replace)" else "API key",
                value = keyField,
                onValueChange = { keyField = it },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (storedKeyPresent) "••••••••••••  (saved — never displayed)" else null,
            )
            if (storedKeyPresent && existing != null) {
                TextButton(onClick = {
                    AppGraph.providers.setApiKey(existing, null)
                    storedKeyPresent = false
                    toast("Stored API key removed")
                }) { Text("Remove stored key") }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Enabled",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Text(
                "Extra HTTP headers",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            KeyValueEditor(initial = headers) { headers = it }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save() }) { Text("Save") }
                OutlinedButton(onClick = { test() }, enabled = !testing && baseUrl.isNotBlank()) {
                    Text(if (testing) "Testing…" else "Test Connection")
                }
            }
            OutlinedButton(
                onClick = { fetchModels() },
                enabled = !fetching && baseUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (fetching) "Fetching models…" else "Fetch model list")
            }
            Text(
                "Lists every model the endpoint offers (GET /models) so you can tick " +
                    "the ones you want — no manual model ids needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            if (!isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            testResult?.let { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusPill(if (r.isSuccess) "✓ connected" else "✕ failed", ok = r.isSuccess)
                    Text(
                        r.fold(
                            { it },
                            { it.message ?: "Connection failed" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    // Model picker after a successful discovery round-trip.
    discoveredModels?.let { ids ->
        ModelPickerDialog(
            models = ids,
            selected = selectedModels,
            onToggle = { mid ->
                selectedModels = if (mid in selectedModels) selectedModels - mid else selectedModels + mid
            },
            onSelectAll = { selectedModels = ids.toSet() },
            onSelectNone = { selectedModels = emptySet() },
            onConfirm = { addSelectedModels() },
            onDismiss = { discoveredModels = null },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${name.ifBlank { "provider" }}?",
            text = "The provider configuration is removed. A stored API key is deleted too. " +
                "Models that reference it will stop working until reassigned.",
            onConfirm = {
                confirmDelete = false
                AppGraph.providers.remove(providerId)
                nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discovered ${models.size} models") },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onSelectAll) { Text("All") }
                    TextButton(onClick = onSelectNone) { Text("None") }
                    Text(
                        "${selected.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
                HorizontalDivider()
                LazyColumn(Modifier.height(380.dp)) {
                    items(models, key = { it }) { mid ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(mid) },
                        ) {
                            Checkbox(checked = mid in selected, onCheckedChange = { onToggle(mid) })
                            Text(
                                mid,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selected.isNotEmpty()) {
                Text("Add ${selected.size} selected")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
