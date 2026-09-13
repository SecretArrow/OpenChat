package com.openchat.android.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.workspace.FileDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plain text editor for files in any [FileDomain] with a real filesystem root
 * (spec §7). Opened from the Files tab with the target relative path, or with
 * isNew=true to create a file (the name is asked on first save).
 *
 * Real behavior:
 *  - loads the file content through FileManagerService.readText (IO dispatcher)
 *  - Save writes the buffer; for a new file it first creates it in [path]'s
 *    directory (or the domain root) and then writes the content
 *  - Save & Close saves and pops the back stack
 *  - every result is reported via a Toast; failures never lose the buffer
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(nav: NavHostController, path: String?, domain: String, isNew: Boolean) {
    val domainEnum: FileDomain = remember(domain) {
        runCatching { FileDomain.valueOf(domain) }.getOrDefault(FileDomain.APP_DATA)
    }
    val root: File? = remember(domainEnum) { AppGraph.files.rootFor(domainEnum) }
    val file: File? = remember(root, path, isNew) {
        if (!isNew && root != null && !path.isNullOrBlank()) File(root, path) else null
    }

    val scope = rememberCoroutineScope()
    var text by remember(file) { mutableStateOf("") }
    var dirty by remember(file) { mutableStateOf(false) }
    var loadFailed by remember(file) { mutableStateOf<String?>(null) }
    var loaded by remember(file, isNew) { mutableStateOf(isNew || file == null) }
    var reloadKey by remember(file) { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(file, isNew, reloadKey) {
        if (file != null && !isNew) {
            loadFailed = null
            loaded = false
            val r = withContext(Dispatchers.IO) { AppGraph.files.readText(file) }
            r.fold(
                { text = it },
                { loadFailed = it.message ?: "Unknown read error" },
            )
            loaded = true
        }
    }

    // New-file name dialog state.
    var nameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var closeAfterSave by remember { mutableStateOf(false) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    fun writeExisting(target: File, thenClose: Boolean) {
        saving = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { AppGraph.files.writeText(target, text) }
            saving = false
            r.fold(
                {
                    dirty = false
                    toast("Saved ${target.name}")
                    if (thenClose) nav.popBackStack()
                },
                { toast("Save failed: ${it.message ?: "unknown error"}") },
            )
        }
    }

    fun createAndWrite(name: String, thenClose: Boolean) {
        val rootDir = root
        if (rootDir == null) {
            toast("Cannot resolve the ${domainEnum.name} storage root — is the Ubuntu userspace ready?")
            return
        }
        val relDir = path ?: ""
        saving = true
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                AppGraph.files.createFile(domainEnum, relDir, name)
            }
            created.fold(
                { entry ->
                    val target: File = entry.file ?: File(rootDir, entry.relativePath)
                    val written = withContext(Dispatchers.IO) {
                        AppGraph.files.writeText(target, text)
                    }
                    saving = false
                    written.fold(
                        {
                            dirty = false
                            toast("Saved ${entry.name}")
                            if (thenClose) nav.popBackStack()
                        },
                        { toast("Create ok, but writing failed: ${it.message ?: "unknown error"}") },
                    )
                },
                {
                    saving = false
                    toast("Create failed: ${it.message ?: "unknown error"}")
                },
            )
        }
    }

    fun onSaveClick(close: Boolean) {
        if (saving) return
        closeAfterSave = close
        if (file != null && !isNew) {
            writeExisting(file, close)
        } else {
            newName = ""
            nameDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (if (isNew) "New file" else file?.name ?: "File") + if (dirty) " •" else "",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(AppIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { onSaveClick(false) }, enabled = !saving && loaded) {
                        Text("Save")
                    }
                    TextButton(onClick = { onSaveClick(true) }, enabled = !saving && loaded) {
                        Text("Save & Close")
                    }
                },
            )
        },
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            when {
                !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loadFailed != null -> Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Could not read the file",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        loadFailed ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Button(onClick = { reloadKey++ }) { Text("Retry") }
                }
                else -> OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        dirty = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    placeholder = { Text(if (isNew) "Type the file content…" else "(empty file)") },
                )
            }
        }
    }

    if (nameDialog) {
        AlertDialog(
            onDismissRequest = { nameDialog = false },
            title = { Text("Name the new file") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LabeledTextField(
                        label = "File name",
                        value = newName,
                        onValueChange = { newName = it },
                        supportingText = "Created in " + (
                            path?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: "the ${domainEnum.name} root"
                            ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        nameDialog = false
                        createAndWrite(newName.trim(), closeAfterSave)
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { nameDialog = false }) { Text("Cancel") }
            },
        )
    }
}
