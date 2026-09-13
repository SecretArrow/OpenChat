package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.ui.components.humanizeBytes
import com.openchat.android.workspace.FileDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage audit (spec §23): lazily computed per-domain sizes (computed on
 * button press, never on composition) plus a one-tap clear of the download
 * cache used by the Ubuntu/OpenCode installers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()

    // key → computed size in bytes (absent = not computed yet)
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var computing by remember { mutableStateOf<Set<String>>(emptySet()) }

    val appDataRoot: File? = AppGraph.files.rootFor(FileDomain.APP_DATA)
    val rootfsRoot: File? = AppGraph.files.rootFor(FileDomain.UBUNTU_ROOTFS)
    val workspaceRoot: File? = AppGraph.files.rootFor(FileDomain.WORKSPACE)
    val cacheDir: File = File(AppGraph.appContext.filesDir, "ubuntu/cache")

    fun dirSize(root: File?): Long =
        if (root == null || !root.exists()) 0L
        else root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun compute(key: String, root: File?) {
        if (key in computing) return
        computing = computing + key
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { dirSize(root) }
            sizes = sizes + (key to bytes)
            computing = computing - key
        }
    }

    fun clearCache() {
        scope.launch {
            val freed = withContext(Dispatchers.IO) {
                val before = dirSize(cacheDir)
                cacheDir.deleteRecursively()
                before
            }
            sizes = sizes + ("cache" to 0L)
            android.widget.Toast.makeText(
                AppGraph.appContext,
                "Cleared ${humanizeBytes(freed)} of download cache",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StorageRow(
                title = "App data",
                hint = "Configs, chats, secrets metadata — private to Open Chat",
                busy = "app" in computing,
                size = sizes["app"],
                onCompute = { compute("app", appDataRoot) },
            )
            StorageRow(
                title = "Ubuntu rootfs",
                hint = "typically 300–800 MB after packages; lives in app-private storage",
                busy = "rootfs" in computing,
                size = sizes["rootfs"],
                onCompute = { compute("rootfs", rootfsRoot) },
            )
            StorageRow(
                title = "Workspaces",
                hint = "/root/workspaces inside the rootfs — your project files",
                busy = "ws" in computing,
                size = sizes["ws"],
                onCompute = { compute("ws", workspaceRoot) },
            )
            StorageRow(
                title = "Download cache",
                hint = "Temporary rootfs/proot/Node tarballs — safe to clear anytime",
                busy = "cache" in computing,
                size = sizes["cache"],
                onCompute = { compute("cache", cacheDir) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { clearCache() }) { Text("Clear download cache") }
            }
        }
    }
}

@Composable
private fun StorageRow(
    title: String,
    hint: String,
    busy: Boolean,
    size: Long?,
    onCompute: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    size?.let { humanizeBytes(it) } ?: "tap Compute to measure",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(start = 8.dp))
            } else {
                OutlinedButton(onClick = onCompute) { Text("Compute") }
            }
        }
    }
}
