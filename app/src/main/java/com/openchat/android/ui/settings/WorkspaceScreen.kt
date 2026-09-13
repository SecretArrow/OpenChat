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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.openchat.android.AppGraph
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Provider
import com.openchat.android.core.model.Workspace
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.ConfirmDialog
import com.openchat.android.ui.components.KeyValueEditor
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.StatusPill

/**
 * Workspace management (spec §13): CRUD for workspaces inside the Ubuntu
 * userspace (/root/workspaces/…), per-workspace provider/model defaults and
 * environment variables, plus "set current" by tapping a row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(nav: NavHostController) {
    val workspaces by AppGraph.workspaces.workspaces.collectAsState()
    val currentId by AppGraph.workspaces.currentId.collectAsState()
    val providers by AppGraph.providers.providers.collectAsState()
    val models by AppGraph.models.models.collectAsState()

    var editTarget by remember { mutableStateOf<Workspace?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Workspace?>(null) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun summary(w: Workspace): String {
        val p = w.providerId?.let { id -> providers.firstOrNull { it.id == id }?.name }
        val m = w.modelId?.let { id -> models.firstOrNull { it.id == id }?.displayName }
        return (p ?: "no provider") + " · " + (m ?: "no model") +
            if (w.envVars.isEmpty()) "" else " · ${w.envVars.size} env vars"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspaces") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(AppIcons.Add, contentDescription = "New workspace")
            }
        },
    ) { pad ->
        if (workspaces.isEmpty()) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No workspaces yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A workspace is a project directory under /root/workspaces inside the " +
                        "Ubuntu userspace. OpenCode runs inside the current workspace. Tap + to create one.",
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
                items(workspaces, key = { it.id }) { w ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppGraph.workspaces.open(w.id)
                                    toast("Opened \"${w.name}\" — OpenCode will run here")
                                }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    w.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (w.id == currentId) StatusPill("Current", ok = true)
                            }
                            Text(
                                w.path,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                summary(w),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { editTarget = w }) { Text("Edit") }
                                TextButton(onClick = { confirmDelete = w }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        WorkspaceDialog(
            existing = null,
            providers = providers,
            models = models,
            onDismiss = { showCreate = false },
            onSave = { name, providerId, modelId, env ->
                showCreate = false
                AppGraph.workspaces.create(name, providerId, modelId, env).fold(
                    { w ->
                        toast("Workspace \"${w.name}\" created at ${w.path}")
                        AppGraph.workspaces.open(w.id)
                    },
                    { toast("Create failed: ${it.message ?: "unknown error"}") },
                )
            },
        )
    }

    editTarget?.let { target ->
        WorkspaceDialog(
            existing = target,
            providers = providers,
            models = models,
            onDismiss = { editTarget = null },
            onSave = { name, providerId, modelId, env ->
                editTarget = null
                AppGraph.workspaces.update(
                    target.copy(name = name, providerId = providerId, modelId = modelId, envVars = env),
                )
                toast("Workspace saved")
            },
        )
    }

    confirmDelete?.let { target ->
        ConfirmDialog(
            title = "Delete \"${target.name}\"?",
            text = "The workspace entry is removed. The directory under /root/workspaces inside the " +
                "Ubuntu rootfs is deleted as well (Ubuntu must be installed for that part).",
            onConfirm = {
                confirmDelete = null
                AppGraph.workspaces.delete(target.id)
                toast("Workspace deleted")
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

/** Create/edit dialog shared by the FAB and the per-row Edit button. */
@Composable
private fun WorkspaceDialog(
    existing: Workspace?,
    providers: List<Provider>,
    models: List<AIModel>,
    onDismiss: () -> Unit,
    onSave: (name: String, providerId: String?, modelId: String?, env: Map<String, String>) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var providerId by remember { mutableStateOf(existing?.providerId ?: "") }
    var modelId by remember { mutableStateOf(existing?.modelId ?: "") }
    var env by remember { mutableStateOf(existing?.envVars ?: emptyMap()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New workspace" else "Edit \"${existing.name}\"") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LabeledTextField(
                    label = "Name",
                    value = name,
                    onValueChange = { name = it },
                    supportingText = "Directory name under /root/workspaces",
                )
                PickerField(
                    label = "Provider",
                    options = listOf("" to "None") + providers.map { it.id to it.name },
                    selected = providerId,
                    onSelect = { providerId = it },
                )
                PickerField(
                    label = "Model",
                    options = listOf("" to "None") + models.map { it.id to it.displayName },
                    selected = modelId,
                    onSelect = { modelId = it },
                )
                Text(
                    "Environment variables",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                KeyValueEditor(initial = env) { env = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        name.trim(),
                        providerId.ifBlank { null },
                        modelId.ifBlank { null },
                        env.filter { it.key.isNotBlank() },
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Read-only dropdown field over (id, label) options; empty id = None. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerField(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "None",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (id, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}
