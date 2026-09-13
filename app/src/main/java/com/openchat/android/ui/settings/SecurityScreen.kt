package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.SectionHeader

/**
 * Security overview (spec §12): Keystore-backed secret storage audit.
 * Shows WHICH secrets exist (ids) with masked values — never the values
 * themselves — and allows adding/removing provider keys and custom secrets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(nav: NavHostController) {
    val providers by AppGraph.providers.providers.collectAsState()
    var keyEpoch by remember { mutableIntStateOf(0) }
    var secretEpoch by remember { mutableIntStateOf(0) }
    var secretIds by remember(secretEpoch) { mutableStateOf(AppGraph.secrets.listIds()) }

    var newSecretId by remember { mutableStateOf("") }
    var newSecretValue by remember { mutableStateOf("") }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
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
                    Text("Secure storage", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "API keys and secrets are stored in an Android Keystore-backed " +
                            "AES-256-GCM encrypted store. Values are never displayed in this " +
                            "screen, never written to preferences/JSON files and never logged. " +
                            "Only secret ids are listed here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            SectionHeader("Provider API keys")
            if (providers.isEmpty()) {
                Text(
                    "No providers configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            providers.forEach { p ->
                // keyEpoch is read so removing a key refreshes this row.
                val epoch = keyEpoch
                val hasKey = epoch >= 0 && AppGraph.providers.apiKeyFor(p) != null
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (hasKey) "••••••••••••  (set)" else "(not set)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (hasKey) {
                        TextButton(onClick = {
                            AppGraph.providers.setApiKey(p, null)
                            keyEpoch++
                            toast("Key for ${p.name} removed")
                        }) { Text("Remove key") }
                    }
                }
            }

            SectionHeader("Custom secrets")
            if (secretIds.isEmpty()) {
                Text(
                    "No custom secrets stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            secretIds.forEach { id ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "••••••••••••",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    IconButton(onClick = {
                        AppGraph.secrets.delete(id)
                        secretEpoch++
                        toast("Secret \"$id\" deleted")
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete secret $id")
                    }
                }
            }

            SectionHeader("Add a secret")
            LabeledTextField(
                label = "Secret id",
                value = newSecretId,
                onValueChange = { newSecretId = it },
                supportingText = "e.g. github_token — referenced by tools, never shown again",
            )
            LabeledTextField(
                label = "Value",
                value = newSecretValue,
                onValueChange = { newSecretValue = it },
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = {
                    val ok = AppGraph.secrets.put(newSecretId.trim(), newSecretValue)
                    if (ok) {
                        toast("Secret \"${newSecretId.trim()}\" stored")
                        newSecretId = ""
                        newSecretValue = ""
                        secretEpoch++
                    } else {
                        toast("Could not store the secret (empty or invalid id?)")
                    }
                },
                enabled = newSecretId.isNotBlank() && newSecretValue.isNotBlank(),
            ) { Text("Add secret") }
        }
    }
}
