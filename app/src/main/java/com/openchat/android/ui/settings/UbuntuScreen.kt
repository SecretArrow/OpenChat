package com.openchat.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import android.net.Uri
import com.openchat.android.AppGraph
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.RepairAction
import com.openchat.android.core.model.UbuntuState
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.ConfirmDialog
import com.openchat.android.ui.components.ErrorCard
import com.openchat.android.ui.components.StatusPill
import com.openchat.android.ui.components.formatDate
import com.openchat.android.ui.nav.Routes
import kotlinx.coroutines.launch

/**
 * Ubuntu userspace management (spec §3–4): full install chain with progress,
 * repair, update, destructive reset (with confirm), userspace export/import
 * (backup/restore via SAF), open terminal shortcut and the installer/runtime
 * log tail (spec §26 honesty).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UbuntuScreen(nav: NavHostController) {
    val status by AppGraph.ubuntu.status.collectAsState()
    val log by AppGraph.ubuntu.log.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var working by remember { mutableStateOf(false) }

    val blocked = status.state.busy || working

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun op(label: String, action: suspend () -> Result<Unit>) {
        if (blocked) return
        scope.launch {
            working = true
            action().fold(
                { toast("$label completed") },
                { toast("$label failed: ${it.message ?: "unknown error"}") },
            )
            working = false
        }
    }

    // Export: user picks the destination (Downloads etc.) — no storage
    // permission needed. Suggested name carries the date for orderly backups.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri: Uri? ->
        if (uri != null) {
            op("Export") { AppGraph.ubuntu.export(uri) }
        }
    }

    // Export of the cached base tarball (the downloaded ubuntu-base archive).
    val exportBaseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri: Uri? ->
        if (uri != null) {
            op("Export base") { AppGraph.ubuntu.exportBaseCache(uri) }
        }
    }

    // Import: user picks an exported .tar.gz (or a full rootfs tarball).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
            confirmImport = true
        }
    }

    fun suggestedExportName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "openchat-ubuntu-$stamp.tar.gz"
    }

    val failureTail by AppGraph.ubuntu.failureTail.collectAsState()
    var diagnosticsReport by remember { mutableStateOf<List<String>?>(null) }
    var runningDiagnostics by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    fun copyReport() {
        val text = buildString {
            appendLine("OpenChat Ubuntu report")
            appendLine("state: ${status.state} · installed=${status.installed} · message=${status.message}")
            appendLine("free space (app data): " + runCatching {
                val s = android.os.StatFs(AppGraph.appContext.filesDir.absolutePath)
                "${s.availableBytes / (1024 * 1024)} MB"
            }.getOrDefault("?"))
            if (failureTail.isNotEmpty()) {
                appendLine("output tail:")
                failureTail.takeLast(20).forEach { appendLine("  | $it") }
            }
            diagnosticsReport?.forEach { appendLine(it) }
        }
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        toast("Report copied — attach it to any bug report")
    }

    val errorInfo: ErrorInfo? = if (status.state == UbuntuState.ERROR) {
        // Honest error: the REAL output tail is shown instead of a guessed
        // cause list (users reported "error code 255" while their storage was
        // fine — the old card wrongly listed storage as a likely cause).
        ErrorInfo(
            title = "Ubuntu operation failed",
            detail = (status.message ?: "The last Ubuntu operation failed.") +
                if (failureTail.isNotEmpty()) {
                    "\n\nOutput tail:\n" + failureTail.takeLast(12).joinToString("\n") { "| $it" }
                } else {
                    ""
                },
            causes = listOf(
                "The exact failing lines are shown above — they name the real cause",
                "Run diagnostics below for free space, proot, DNS and APT source checks",
            ),
            suggestions = listOf(
                "Use Repair to re-verify and re-extract the rootfs (fixes partial apt state)",
                "Use Reset for a clean re-install",
                "Export first if you need a backup of the current userspace",
                "Tap Copy report and include it when asking for help",
            ),
            retryable = true,
            repairAction = RepairAction.UBUNTU_REPAIR,
        )
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ubuntu userspace") },
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
                    Row {
                        Text(
                            "Status",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(status.state.name, ok = status.state == UbuntuState.READY)
                    }
                    Text(
                        "Version: ${status.version ?: "—"} · Arch: ${status.arch ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    status.rootfsPath?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Text(
                        "Last updated: ${formatDate(status.lastUpdated)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    status.message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (status.state.busy) {
                        LinearProgressIndicator(
                            progress = { status.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { op("Install") { AppGraph.ubuntu.install() } },
                    enabled = !blocked &&
                        (status.state == UbuntuState.NOT_INSTALLED || status.state == UbuntuState.ERROR),
                ) { Text("Install") }
                OutlinedButton(
                    onClick = { op("Repair") { AppGraph.ubuntu.repair() } },
                    enabled = !blocked,
                ) { Text("Repair") }
                OutlinedButton(
                    onClick = { op("Update") { AppGraph.ubuntu.update() } },
                    enabled = !blocked && status.installed,
                ) { Text("Update") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { nav.navigate(Routes.TERMINAL) },
                    enabled = status.installed,
                ) {
                    Icon(AppIcons.Terminal, contentDescription = null)
                    Text("  Open Terminal")
                }
                OutlinedButton(
                    onClick = { confirmReset = true },
                    enabled = !blocked,
                ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            }

            // Diagnostics: honest environment report (space, proot, tmp dir,
            // RAM, DNS, apt sources) — the tool for every "why did apt fail".
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runningDiagnostics = true
                                    diagnosticsReport = AppGraph.ubuntu.diagnostics()
                                    runningDiagnostics = false
                                }
                            },
                            enabled = !blocked && !runningDiagnostics,
                        ) { Text(if (runningDiagnostics) "Running…" else "Run diagnostics") }
                    }
                    Text(
                        "Checks free space as seen by the app, the proot binary, " +
                            "PROOT_TMP_DIR writability, RAM, DNS and APT sources inside the rootfs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    diagnosticsReport?.let { report ->
                        Text(
                            report.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(4.dp),
                        )
                        OutlinedButton(onClick = { copyReport() }) { Text("Copy report") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Backup & restore",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Export packs the whole userspace (installed packages, workspaces, " +
                            "configuration) into one .tar.gz. Import restores it on this device " +
                            "— or on a new device with the same architecture — replacing whatever " +
                            "is currently installed. A raw ubuntu-base tarball downloaded from " +
                            "cdimage.ubuntu.com is also accepted: OpenChat detects it and writes " +
                            "DNS + APT sources automatically. Export also works when Ubuntu is " +
                            "broken, so you can back up before a Reset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch(suggestedExportName()) },
                            enabled = !blocked && AppGraph.ubuntu.hasRootfs(),
                        ) { Text("Export") }
                        OutlinedButton(
                            onClick = {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/gzip",
                                        "application/x-gzip",
                                        "application/x-tgz",
                                        "application/x-compressed-tar",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            enabled = !blocked,
                        ) { Text("Import") }
                    }
                    Text(
                        "Export base file copies the downloaded ubuntu-base .tar.gz from the " +
                            "app cache — move it to another device or keep it for offline reinstall " +
                            "(Import accepts it directly, no download needed).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    OutlinedButton(
                        onClick = { exportBaseLauncher.launch("openchat-ubuntu-base.tar.gz") },
                        enabled = !blocked,
                    ) { Text("Export base file") }
                }
            }

            ErrorCard(
                info = errorInfo,
                onRetry = { op("Repair") { AppGraph.ubuntu.repair() } },
                onRepair = { action ->
                    when (action) {
                        RepairAction.UBUNTU_REPAIR -> op("Repair") { AppGraph.ubuntu.repair() }
                        RepairAction.UBUNTU_RESET -> confirmReset = true
                        else -> Unit
                    }
                },
            )

            Text(
                "Log (last 40 lines)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Card(Modifier.fillMaxWidth()) {
                Text(
                    log.takeLast(40).joinToString("\n").ifBlank { "(no log output yet)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset Ubuntu?",
            text = "This deletes the whole Ubuntu rootfs — every package, workspace file and " +
                "configuration inside it. You will have to install again from scratch.",
            onConfirm = {
                confirmReset = false
                op("Reset") { AppGraph.ubuntu.reset() }
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (confirmImport) {
        ConfirmDialog(
            title = "Import this archive?",
            text = "The archive replaces the currently installed Ubuntu userspace. " +
                "Both formats are accepted: an app Export (full backup with your packages " +
                "and files) or a raw ubuntu-base tarball from cdimage.ubuntu.com — raw " +
                "bases are auto-configured with DNS + APT sources. Open terminal sessions " +
                "will be closed. Make sure the archive matches this device architecture " +
                "(arm64/armhf/x86_64).",
            onConfirm = {
                confirmImport = false
                val uri = pendingImportUri
                pendingImportUri = null
                if (uri != null) op("Import") { AppGraph.ubuntu.import(uri) }
            },
            onDismiss = {
                confirmImport = false
                pendingImportUri = null
            },
        )
    }
}
