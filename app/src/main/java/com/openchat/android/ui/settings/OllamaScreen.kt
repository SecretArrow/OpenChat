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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.OllamaModel
import com.openchat.android.core.model.OllamaServer
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.ConfirmDialog
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.StatusPill
import com.openchat.android.ui.components.humanizeBytes
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Ollama manager (spec §11): add/test/remove Ollama servers (local or LAN),
 * list models per server, pull with live progress, run and delete models.
 * Ollama runs as a first-class remote — install is NOT required on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OllamaScreen(nav: NavHostController) {
    val servers by AppGraph.ollama.servers.collectAsState()
    val modelCache by AppGraph.ollama.modelCache.collectAsState()
    val busy by AppGraph.ollama.busy.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedServerId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected: OllamaServer? =
        servers.firstOrNull { it.id == selectedServerId } ?: servers.firstOrNull()

    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<Pair<String, Result<String>>?>(null) }
    var confirmRemove by remember { mutableStateOf<OllamaServer?>(null) }
    var showPull by remember { mutableStateOf(false) }
    var pullName by remember { mutableStateOf("") }
    var pullProgress by remember { mutableStateOf<String?>(null) }
    var confirmDeleteModel by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun testServer(s: OllamaServer) {
        scope.launch {
            val r = AppGraph.ollama.testServer(s)
            testResult = s.id to r
        }
    }

    fun refresh(s: OllamaServer) {
        scope.launch {
            AppGraph.ollama.refreshModels(s).fold(
                { list -> toast("Found ${list.size} models on ${s.name}") },
                { toast("Refresh failed: ${it.message ?: "unknown error"}") },
            )
        }
    }

    fun pull(s: OllamaServer, name: String) {
        pullProgress = "Starting pull of $name…"
        scope.launch {
            AppGraph.ollama.pullModel(s, name) { progress -> pullProgress = progress }.fold(
                {
                    pullProgress = null
                    toast("Pulled $name")
                },
                {
                    pullProgress = null
                    toast("Pull failed: ${it.message ?: "unknown error"}")
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ollama") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { newName = ""; newUrl = ""; showAdd = true }) {
                Icon(AppIcons.Add, contentDescription = "Add Ollama server")
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            item {
                Text(
                    "Ollama servers",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            if (servers.isEmpty()) {
                item {
                    Text(
                        "No Ollama servers yet — tap + to add one. Ollama can run on another " +
                            "machine on your LAN; installing it on the phone is not required.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(servers, key = { it.id }) { s ->
                ServerCard(
                    server = s,
                    selected = s.id == selected?.id,
                    testResult = testResult?.takeIf { it.first == s.id }?.second,
                    onSelect = { selectedServerId = s.id },
                    onTest = { testServer(s) },
                    onRemove = { confirmRemove = s },
                )
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Models on ${selected?.name ?: "—"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected != null) {
                        IconButton(onClick = { refresh(selected) }) {
                            Icon(AppIcons.Refresh, contentDescription = "Refresh model list")
                        }
                    }
                }
            }
            if (busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            }
            pullProgress?.let { progress ->
                item {
                    Text(
                        progress,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            val sel = selected
            if (sel == null) {
                item {
                    Text(
                        "Select a server above to manage its models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                val ollamaModels: List<OllamaModel> = modelCache[sel.id].orEmpty()
                if (ollamaModels.isEmpty()) {
                    item {
                        Text(
                            "No cached model list — tap ↻ to load it from the server, or pull a new model below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(ollamaModels, key = { it.name }) { m ->
                    OllamaModelRow(
                        model = m,
                        onRun = {
                            scope.launch {
                                AppGraph.ollama.runModel(sel, m.name).fold(
                                    { msg -> toast("Model '${m.name}' ready — ${msg.take(120)}") },
                                    { toast("Run failed: ${it.message ?: "unknown error"}") },
                                )
                            }
                        },
                        onPull = { pullName = m.name; showPull = true },
                        onDelete = { confirmDeleteModel = m.name },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { pullName = ""; showPull = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text("＋ Pull a model (e.g. llama3.2:1b)") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ---- dialogs --------------------------------------------------------------
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add Ollama server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledTextField(
                        label = "Name",
                        value = newName,
                        onValueChange = { newName = it },
                        supportingText = "e.g. \"Desktop PC\"",
                    )
                    LabeledTextField(
                        label = "Base URL",
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        supportingText = "http://192.168.1.10:11434",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newUrl.isNotBlank(),
                    onClick = {
                        showAdd = false
                        val s = OllamaServer(
                            id = UUID.randomUUID().toString(),
                            name = newName.trim(),
                            baseUrl = newUrl.trim(),
                        )
                        AppGraph.ollama.upsertServer(s)
                        selectedServerId = s.id
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }

    confirmRemove?.let { s ->
        ConfirmDialog(
            title = "Remove ${s.name}?",
            text = "Only the server entry in Open Chat is removed — the Ollama service itself is untouched.",
            onConfirm = {
                confirmRemove = null
                AppGraph.ollama.removeServer(s.id)
                if (selectedServerId == s.id) selectedServerId = null
            },
            onDismiss = { confirmRemove = null },
        )
    }

    if (showPull) {
        AlertDialog(
            onDismissRequest = { if (!busy) showPull = false },
            title = { Text("Pull model") },
            text = {
                LabeledTextField(
                    label = "Model tag",
                    value = pullName,
                    onValueChange = { pullName = it },
                    supportingText = "Any tag from ollama.com/library, e.g. llama3.2:1b, qwen2.5-coder:7b",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pullName.isNotBlank() && selected != null && !busy,
                    onClick = {
                        showPull = false
                        selected?.let { pull(it, pullName.trim()) }
                    },
                ) { Text("Pull") }
            },
            dismissButton = {
                TextButton(onClick = { showPull = false }) { Text("Cancel") }
            },
        )
    }

    confirmDeleteModel?.let { name ->
        ConfirmDialog(
            title = "Delete $name on ${selected?.name ?: "server"}?",
            text = "The model is removed from the Ollama server's disk. You can pull it again later.",
            onConfirm = {
                confirmDeleteModel = null
                val s = selected
                if (s != null) {
                    scope.launch {
                        AppGraph.ollama.deleteModel(s, name).fold(
                            { toast("Deleted $name") },
                            { toast("Delete failed: ${it.message ?: "unknown error"}") },
                        )
                    }
                }
            },
            onDismiss = { confirmDeleteModel = null },
        )
    }
}

@Composable
private fun ServerCard(
    server: OllamaServer,
    selected: Boolean,
    testResult: Result<String>?,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    server.name + if (selected) "  (selected)" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onTest) { Text("Test") }
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                server.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            testResult?.let { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(if (r.isSuccess) "✓ reachable" else "✕ failed", ok = r.isSuccess)
                    Text(
                        r.fold({ it }, { it.message ?: "failed" }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun OllamaModelRow(model: OllamaModel, onRun: () -> Unit, onPull: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Text(
                buildString {
                    append(humanizeBytes(model.sizeBytes))
                    model.modifiedAt?.let { append(" · updated ").append(it.take(10)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRun) { Text("Run") }
                TextButton(onClick = onPull) { Text("Pull") }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
