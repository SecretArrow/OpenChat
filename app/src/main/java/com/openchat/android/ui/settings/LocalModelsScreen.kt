package com.openchat.android.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.ai.local.DownloadState
import com.openchat.android.ai.local.LlamaBridge
import com.openchat.android.ai.local.LocalModelSpec
import com.openchat.android.ui.components.ConfirmDialog
import com.openchat.android.ui.components.humanizeBytes

/**
 * Local models screen (Settings → Local models): the curated on-device GGUF
 * catalog with resumable downloads (pause / resume / cancel), SAF import
 * (phone → app) and export (app → phone), plus delete. Inference behavior and
 * limits are stated honestly (CPU-only, RAM guidance, per-ABI speed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val specs by AppGraph.localModels.models.collectAsState()
    val downloads by AppGraph.localModels.downloads.collectAsState()
    var deleteTarget by remember { mutableStateOf<LocalModelSpec?>(null) }
    var exportTarget by remember { mutableStateOf<LocalModelSpec?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val r = AppGraph.localModels.importFromUri(uri)
            r.fold(
                { Toast.makeText(context, "Imported ${it.name}", Toast.LENGTH_SHORT).show() },
                { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() },
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            val r = AppGraph.localModels.exportToUri(target.id, uri)
            r.fold(
                { Toast.makeText(context, "Exported ${target.name}", Toast.LENGTH_SHORT).show() },
                { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() },
            )
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Local models") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Run AI fully on-device (GGUF)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Text("Import .gguf")
                }
            }

            if (!LlamaBridge.isAvailable()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "This APK build does not include the on-device engine " +
                            "(libllamajni). Download, import and export still work — " +
                            "inference needs an APK built with the pinned engine.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val settings by AppGraph.settings.settings.collectAsState()
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("GPU acceleration (Vulkan)", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Offload model layers to the GPU when this device has a " +
                                        "Vulkan driver (64-bit builds). Without Vulkan the model " +
                                        "runs on CPU automatically. Applies the next time a " +
                                        "model loads — unload happens when you switch models or " +
                                        "free memory.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings.localGpu,
                                onCheckedChange = { on ->
                                    AppGraph.settings.update { it.copy(localGpu = on) }
                                },
                            )
                        }
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "How it works: download a model once (Wi-Fi recommended), " +
                                "then pick it in the chat model selector under " +
                                "\"Local (on-device)\". Everything runs on this phone — " +
                                "no account, no internet needed afterwards.\n\n" +
                                "Honest limits: CPU inference always available (speed " +
                                "depends on your chip; 1–2B models feel responsive, 3B+ is " +
                                "slower); GPU speedup only where a Vulkan 1.1+ driver " +
                                "exists. Keep ≈model size + 350 MB RAM free. The 32-bit " +
                                "build (armeabi-v7a) cannot address models over ≈1.5 GB.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                items(specs, key = { it.id }) { spec ->
                    val st = downloads[spec.id]
                    val done = AppGraph.localModels.isDownloaded(spec.id)
                    ModelCard(
                        spec = spec,
                        state = st,
                        done = done,
                        onDownload = { AppGraph.localModels.download(spec.id) },
                        onPause = { AppGraph.localModels.pause(spec.id) },
                        onResume = { AppGraph.localModels.download(spec.id) },
                        onCancel = { AppGraph.localModels.cancel(spec.id) },
                        onExport = {
                            exportTarget = spec
                            exportLauncher.launch("${spec.name.replace(' ', '-')}.gguf")
                        },
                        onDelete = { deleteTarget = spec },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.name}?",
            text = "The downloaded model file (" +
                humanizeBytes(target.sizeBytes) + ") will be removed from the device. " +
                "You can download or import it again later.",
            onConfirm = {
                AppGraph.localModels.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ModelCard(
    spec: LocalModelSpec,
    state: DownloadState?,
    done: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (spec.source == LocalModelSpec.SOURCE_IMPORTED) {
                    StatusChipLite("imported")
                } else {
                    StatusChipLite("${spec.params} · ${spec.quant}")
                }
            }
            Text(
                "≈" + humanizeBytes(spec.sizeBytes) + " · RAM hint ≈" +
                    spec.ramHintMb + " MB" +
                    (if (spec.repo != null) " · " + spec.repo else ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(spec.notes, style = MaterialTheme.typography.bodySmall)

            when (state) {
                is DownloadState.Running -> {
                    val frac = if (state.total > 0) state.received.toFloat() / state.total else 0f
                    LinearProgressIndicator(
                        progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        humanizeBytes(state.received) + " / " +
                            humanizeBytes(state.total) +
                            " · " + humanizeBytes(state.bytesPerSec) + "/s",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPause) { Text("Pause") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is DownloadState.Paused -> {
                    val frac = if (state.total > 0) state.received.toFloat() / state.total else 0f
                    LinearProgressIndicator(
                        progress = { frac.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Paused at " + humanizeBytes(state.received) + " / " +
                            humanizeBytes(state.total) + " — resume anytime " +
                            "(also after closing the app)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onResume) { Text("Resume") }
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }
                is DownloadState.Failed -> {
                    Text(
                        "Download failed: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onDownload) { Text("Retry") }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (done) {
                            StatusChipLite("downloaded")
                            Spacer(Modifier.size(4.dp))
                            TextButton(onClick = onExport) { Text("Export") }
                            TextButton(onClick = onDelete) { Text("Delete") }
                        } else if (state !is DownloadState.Running) {
                            OutlinedButton(onClick = onDownload) { Text("Download") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChipLite(text: String) {
    Card {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
