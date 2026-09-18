package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.model.ProcState
import com.openchat.android.core.model.ProcessInfo
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.StatusPill
import com.openchat.android.ui.components.humanizeTime
import com.openchat.android.ui.nav.Routes
import kotlinx.coroutines.launch

/**
 * Background process manager (spec §25): long-running commands inside the
 * Ubuntu userspace (started via a real PTY session and kept alive by the
 * foreground service) with stop / restart / live output / attach, plus a
 * start box that uses the current workspace as cwd.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessManagerScreen(nav: NavHostController) {
    val processes by AppGraph.processes.processes.collectAsState()
    val workspaces by AppGraph.workspaces.workspaces.collectAsState()
    val currentId by AppGraph.workspaces.currentId.collectAsState()
    val scope = rememberCoroutineScope()

    val currentWorkspace = workspaces.firstOrNull { it.id == currentId }
    var command by remember { mutableStateOf("") }
    var outputTarget by remember { mutableStateOf<ProcessInfo?>(null) }
    var outputRefresh by remember { mutableIntStateOf(0) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Background processes") },
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
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start a process", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Runs in " +
                            (currentWorkspace?.let { "\"${it.name}\" (${it.path})" } ?: "/root (no workspace selected)"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("Command") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                val cmd = command.trim()
                                if (cmd.isEmpty()) return@Button
                                scope.launch {
                                    AppGraph.processes.start(currentWorkspace, cmd, title = cmd.take(24)).fold(
                                        { p ->
                                            toast("Started \"${p.title}\" (pid ${p.pid})")
                                            command = ""
                                        },
                                        { toast("Start failed: ${it.message ?: "unknown error"}") },
                                    )
                                }
                            },
                            enabled = command.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Run") }
                    }
                }
            }

            Text(
                "Processes (${processes.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            if (processes.isEmpty()) {
                Text(
                    "Nothing running. Start a dev server (e.g. \"python3 -m http.server 8000\"), " +
                        "a watcher or any long-running command above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                processes.forEach { p ->
                    ProcessCard(
                        p = p,
                        onStop = { AppGraph.processes.stop(p.pid) },
                        onRestart = { scope.launch { AppGraph.processes.restart(p.pid) } },
                        onOutput = { outputTarget = p; outputRefresh++ },
                        onAttach = {
                            val session = AppGraph.processes.attach(p.pid)
                            if (session != null) {
                                nav.navigate(Routes.TERMINAL)
                            } else {
                                toast("No live session to attach (process already exited?)")
                            }
                        },
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Processes keep running while the app is alive via a foreground service. " +
                        "If Android kills the app they are terminated — on next start they show as " +
                        "EXITED with an honest note; Open Chat cannot resurrect them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    outputTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { outputTarget = null },
            title = { Text("Output — ${target.title}") },
            text = {
                val snapshot = remember(target, outputRefresh) {
                    AppGraph.processes.outputOf(target.pid).ifBlank { "(no output yet)" }
                }
                Text(
                    snapshot,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { outputRefresh++ }) { Text("Refresh") }
            },
            dismissButton = {
                TextButton(onClick = { outputTarget = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ProcessCard(
    p: ProcessInfo,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOutput: () -> Unit,
    onAttach: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    p.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(p.state.name, ok = p.state == ProcState.RUNNING)
            }
            Text(
                p.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                buildString {
                    append("pid ${p.pid} · started ${humanizeTime(p.startedAt)}")
                    p.exitCode?.let { append(" · exit $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onStop, enabled = p.state == ProcState.RUNNING) { Text("Stop") }
                TextButton(onClick = onRestart) { Text("Restart") }
                TextButton(onClick = onOutput) { Text("Output") }
                TextButton(onClick = onAttach, enabled = p.sessionId.isNotBlank()) { Text("Attach") }
            }
        }
    }
}
