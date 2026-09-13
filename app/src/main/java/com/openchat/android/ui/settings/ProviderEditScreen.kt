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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.ProviderType
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(nav: NavHostController, providerId: String) {
    val isNew = providerId == "new"
    val providers by AppGraph.providers.providers.collectAsState()
    val existing: Provider? = providers.firstOrNull { it.id == providerId }
    val scope = rememberCoroutineScope()

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: ProviderType.OPENAI) }
    var baseUrl by remember(existing) {
        mutableStateOf(existing?.baseUrl ?: defaultBaseUrls.getValue(ProviderType.OPENAI))
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

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun save() {
        if (name.isBlank()) {
            toast("Name is required")
            return
        }
        if (baseUrl.isBlank()) {
            toast("Base URL is required")
            return
        }
        val p = Provider(
            id = existing?.id ?: UUID.randomUUID().toString(),
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
        }
        nav.popBackStack()
    }

    fun test() {
        val draft = Provider(
            id = existing?.id ?: "",
            name = name.trim().ifBlank { "draft" },
            type = type,
            baseUrl = baseUrl.trim(),
            apiKeyRef = existing?.apiKeyRef,
            headers = headers.filter { it.key.isNotBlank() },
            enabled = true,
        )
        testing = true
        testResult = null
        scope.launch {
            val r = AppGraph.providers.testConnection(draft, keyField.ifBlank { null })
            testing = false
            testResult = r
        }
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
            LabeledTextField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                supportingText = "Shown in pickers, e.g. \"OpenAI main\" or \"Home Ollama\"",
            )

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
                supportingText = defaultBaseUrls.getValue(type),
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
                if (!isNew) {
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
