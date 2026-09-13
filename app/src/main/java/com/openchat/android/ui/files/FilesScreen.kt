package com.openchat.android.ui.files

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.openchat.android.AppGraph
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.ConfirmDialog
import com.openchat.android.ui.components.ErrorCard
import com.openchat.android.ui.components.LabeledTextField
import com.openchat.android.ui.components.StatusPill
import com.openchat.android.ui.components.formatDate
import com.openchat.android.ui.components.humanizeBytes
import com.openchat.android.workspace.FileDomain
import com.openchat.android.workspace.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * Files tab (spec §7): browse App data / Ubuntu rootfs / Workspaces via
 * FileManagerService, with full file ops (create/rename/delete/copy/move),
 * SAF import/export, and opening text files in the editor.
 *
 * The FilesScreen composable takes no NavHostController (AppNav contract), so
 * the text editor is pushed on a nested NavHost owned by this screen. It uses
 * the same route format as AppNav.Routes.EDITOR; EditorScreen receives this
 * screen's nested controller and popBackStack() returns to the browser.
 */

private const val BROWSE_ROUTE = "files/browse"
private const val EDITOR_ROUTE = "files/editor?path={path}&domain={domain}&isNew={isNew}"

/** In-memory copy/cut source, valid while the app process lives. */
private object PasteBuffer {
    var domain: FileDomain? = null
    var rel: String? = null
    var mode: String = "COPY" // COPY | MOVE
}

/** Extensions treated as text for the editor; anything < 512 KB is also editable. */
private val textExts = setOf(
    "txt", "md", "json", "kt", "java", "py", "js", "ts", "sh", "yaml", "yml",
    "xml", "gradle", "properties", "env", "gitignore", "conf", "cfg", "log",
    "csv", "toml", "ini", "c", "cpp", "h", "hpp", "go", "rs", "rb", "php",
    "html", "css", "sql", "lock",
)

private val specialTextNames = setOf(".env", ".gitignore", ".bashrc", ".profile", ".gitconfig")

/** True if the entry should open in the text editor. */
fun isTextCandidate(e: FileEntry): Boolean {
    val ext = e.name.substringAfterLast('.', "").lowercase()
    return ext in textExts || e.name in specialTextNames || e.size < 512 * 1024
}

/** URL-encodes a relative path for a navigation route (spaces → %20, not +). */
fun encodeForRoute(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Small document icon (rect with folded corner). */
private val DocIcon: ImageVector = ImageVector.Builder(
    name = "DocIcon",
    defaultWidth = androidx.compose.ui.unit.Dp(20f),
    defaultHeight = androidx.compose.ui.unit.Dp(20f),
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(androidx.compose.ui.graphics.Color.White), pathFillType = PathFillType.EvenOdd) {
        moveTo(6f, 2f)
        lineTo(14f, 2f)
        lineTo(20f, 8f)
        lineTo(20f, 22f)
        lineTo(6f, 22f)
        close()
        moveTo(14f, 4f)
        lineTo(14f, 8f)
        lineTo(18f, 8f)
        close()
    }
}.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Nested controller for pushing the text editor (see class KDoc).
    val editorNav: NavHostController = rememberNavController()
    val backStackEntry by editorNav.currentBackStackEntryAsState()
    val browsing = backStackEntry?.destination?.route == BROWSE_ROUTE

    fun openEditor(path: String, domainName: String, newFile: Boolean) {
        val enc = encodeForRoute(path)
        editorNav.navigate("files/editor?path=$enc&domain=$domainName&isNew=$newFile")
    }

    // Per-domain relative paths (rememberSaveable per domain).
    var appDataPath by rememberSaveable { mutableStateOf("") }
    var rootfsPath by rememberSaveable { mutableStateOf("") }
    var workspacePath by rememberSaveable { mutableStateOf("") }

    val domain: FileDomain = when (tab) {
        0 -> FileDomain.APP_DATA
        1 -> FileDomain.UBUNTU_ROOTFS
        2 -> FileDomain.WORKSPACE
        else -> FileDomain.SHARED
    }
    val rel: String = when (tab) {
        0 -> appDataPath
        1 -> rootfsPath
        2 -> workspacePath
        else -> ""
    }
    fun setRel(v: String) {
        when (tab) {
            0 -> appDataPath = v
            1 -> rootfsPath = v
            2 -> workspacePath = v
        }
    }

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var listError by remember { mutableStateOf<ErrorInfo?>(null) }

    // Listing.
    LaunchedEffect(domain, rel, refresh) {
        if (domain == FileDomain.SHARED) return@LaunchedEffect
        loading = true
        val result = withContext(Dispatchers.IO) { AppGraph.files.list(domain, rel) }
        loading = false
        result.fold(
            { list ->
                entries = list.sortedWith(
                    compareBy({ !it.isDir }, { it.name.lowercase() })
                )
                listError = null
            },
            { t ->
                entries = emptyList()
                listError = ErrorInfo(
                    title = "Cannot list ${domain.name.lowercase().replace('_', ' ')} files",
                    detail = t.message ?: "Unknown listing error",
                    causes = listOf("Directory missing or unreadable", "Ubuntu userspace not ready"),
                    suggestions = listOf("Go up one level", "Refresh", "Check Settings → Ubuntu"),
                )
            },
        )
    }

    // Dialog / action state.
    var newFileDialog by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var exportTarget by remember { mutableStateOf<FileEntry?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    AppGraph.files.importFromUri(domain, rel, uri)
                }
                r.fold(
                    { e ->
                        Toast.makeText(context, "Uploaded ${e.name}", Toast.LENGTH_SHORT).show()
                        refresh++
                    },
                    {
                        Toast.makeText(
                            context, "Upload failed: ${it.message}", Toast.LENGTH_LONG
                        ).show()
                    },
                )
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target?.file != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    AppGraph.files.exportToUri(target.file!!, uri)
                }
                r.fold(
                    { Toast.makeText(context, "Downloaded to phone", Toast.LENGTH_SHORT).show() },
                    {
                        Toast.makeText(
                            context, "Download failed: ${it.message}", Toast.LENGTH_LONG
                        ).show()
                    },
                )
            }
        }
    }

    // Shared tab: the system picker hands us the file, we copy it into the
    // workspace root inside the Ubuntu userspace (SAF bridge, no storage perms).
    val workspaceImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    AppGraph.files.importFromUri(FileDomain.WORKSPACE, "", uri)
                }
                r.fold(
                    { e ->
                        Toast.makeText(context, "Uploaded ${e.name} into Workspaces", Toast.LENGTH_SHORT).show()
                        refresh++
                    },
                    {
                        Toast.makeText(
                            context, "Upload failed: ${it.message}", Toast.LENGTH_LONG
                        ).show()
                    },
                )
            }
        }
    }

    fun openInEditor(entry: FileEntry) {
        openEditor(entry.relativePath, entry.domain.name, false)
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDir) {
            setRel(entry.relativePath)
        } else if (isTextCandidate(entry)) {
            openInEditor(entry)
        } else {
            Toast.makeText(
                context,
                "Binary file (${humanizeBytes(entry.size)}) — use Export to share it",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun paste() {
        val srcDomain = PasteBuffer.domain
        val srcRel = PasteBuffer.rel
        if (srcDomain == null || srcRel == null) return
        val move = PasteBuffer.mode == "MOVE"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (move) AppGraph.files.move(srcDomain, srcRel, domain, rel)
                else AppGraph.files.copy(srcDomain, srcRel, domain, rel)
            }
            r.fold(
                {
                    PasteBuffer.domain = null
                    PasteBuffer.rel = null
                    Toast.makeText(context, "Pasted", Toast.LENGTH_SHORT).show()
                    refresh++
                },
                {
                    Toast.makeText(
                        context, "Paste failed: ${it.message}", Toast.LENGTH_LONG
                    ).show()
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(AppIcons.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (browsing) {
                TabRow(selectedTabIndex = tab) {
                    listOf("App data", "Ubuntu rootfs", "Workspaces", "Shared").forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                    }
                }
            }

            if (domain == FileDomain.SHARED) {
                SharedTabContent(
                    onImport = { workspaceImportLauncher.launch(arrayOf("*/*")) },
                )
            } else {
                NavHost(
                    navController = editorNav,
                    startDestination = BROWSE_ROUTE,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(BROWSE_ROUTE) {
                        Column(Modifier.fillMaxSize()) {
                            // Breadcrumb + up + actions row.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { setRel(if (rel.contains('/')) rel.substringBeforeLast('/') else "") },
                                    enabled = rel.isNotEmpty(),
                                ) { Text("↑ Up") }
                                Text(
                                    "/" + rel,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TextButton(onClick = { newName = ""; newFileDialog = true }) { Text("+ File") }
                                TextButton(onClick = { newName = ""; newFolderDialog = true }) { Text("+ Folder") }
                                TextButton(
                                    onClick = { paste() },
                                    enabled = PasteBuffer.domain != null,
                                ) { Text("Paste") }
                                TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Upload") }
                            }

                            if (listError != null) {
                                ErrorCard(
                                    listError,
                                    onRetry = { refresh++ },
                                )
                            }

                            if (loading) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) { CircularProgressIndicator() }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                if (!loading && listError == null && entries.isEmpty()) {
                                    item {
                                        Text(
                                            "Empty",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(16.dp),
                                        )
                                    }
                                }
                                items(entries, key = { it.relativePath }) { entry ->
                                    FileRow(
                                        entry = entry,
                                        expanded = menuFor?.relativePath == entry.relativePath,
                                        onOpen = { openEntry(entry) },
                                        onLongPress = { menuFor = entry },
                                        onMenu = { menuFor = entry },
                                        onDismissMenu = { menuFor = null },
                                        onOpenMenu = {
                                            menuFor = null
                                            openEntry(entry)
                                        },
                                        onRename = {
                                            menuFor = null
                                            newName = entry.name
                                            renameTarget = entry
                                        },
                                        onDelete = {
                                            menuFor = null
                                            deleteTarget = entry
                                        },
                                        onCopy = {
                                            menuFor = null
                                            PasteBuffer.domain = entry.domain
                                            PasteBuffer.rel = entry.relativePath
                                            PasteBuffer.mode = "COPY"
                                            Toast.makeText(context, "Copied to paste buffer", Toast.LENGTH_SHORT).show()
                                        },
                                        onCut = {
                                            menuFor = null
                                            PasteBuffer.domain = entry.domain
                                            PasteBuffer.rel = entry.relativePath
                                            PasteBuffer.mode = "MOVE"
                                            Toast.makeText(context, "Cut to paste buffer", Toast.LENGTH_SHORT).show()
                                        },
                                        onExport = {
                                            menuFor = null
                                            exportTarget = entry
                                            exportLauncher.launch(entry.name)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    composable(
                        EDITOR_ROUTE,
                        arguments = listOf(
                            navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null },
                            navArgument("domain") { type = NavType.StringType; defaultValue = "APP_DATA" },
                            navArgument("isNew") { type = NavType.StringType; defaultValue = "false" },
                        ),
                    ) { entry ->
                        EditorScreen(
                            editorNav,
                            entry.arguments?.getString("path"),
                            entry.arguments?.getString("domain") ?: "APP_DATA",
                            entry.arguments?.getString("isNew") == "true",
                        )
                    }
                }
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------
    if (newFileDialog) {
        AlertDialog(
            onDismissRequest = { newFileDialog = false },
            title = { Text("New file") },
            text = {
                LabeledTextField(
                    label = "File name",
                    value = newName,
                    onValueChange = { newName = it },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    newFileDialog = false
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                AppGraph.files.createFile(domain, rel, name)
                            }
                            r.fold(
                                { entry ->
                                    refresh++
                                    openEditor(entry.relativePath, domain.name, false)
                                },
                                {
                                    Toast.makeText(
                                        context, "Create failed: ${it.message}", Toast.LENGTH_LONG
                                    ).show()
                                },
                            )
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newFileDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (newFolderDialog) {
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("New folder") },
            text = {
                LabeledTextField(
                    label = "Folder name",
                    value = newName,
                    onValueChange = { newName = it },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    newFolderDialog = false
                    val name = newName.trim()
                    if (name.isNotEmpty()) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                AppGraph.files.createDirectory(domain, rel, name)
                            }
                            r.fold(
                                { refresh++ },
                                {
                                    Toast.makeText(
                                        context, "Create failed: ${it.message}", Toast.LENGTH_LONG
                                    ).show()
                                },
                            )
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { newFolderDialog = false }) { Text("Cancel") }
            },
        )
    }
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename ${target.name}") },
            text = {
                LabeledTextField(
                    label = "New name",
                    value = newName,
                    onValueChange = { newName = it },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    renameTarget = null
                    val name = newName.trim()
                    if (name.isNotEmpty() && name != t.name) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                AppGraph.files.rename(t.domain, t.relativePath, name)
                            }
                            r.fold(
                                {
                                    if (PasteBuffer.rel == t.relativePath) {
                                        PasteBuffer.domain = null
                                        PasteBuffer.rel = null
                                    }
                                    refresh++
                                },
                                {
                                    Toast.makeText(
                                        context, "Rename failed: ${it.message}", Toast.LENGTH_LONG
                                    ).show()
                                },
                            )
                        }
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete ${target.name}?",
            text = if (target.isDir) {
                "The folder and everything inside it will be deleted permanently."
            } else {
                "This file will be deleted permanently."
            },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        AppGraph.files.delete(target.domain, target.relativePath)
                    }
                    r.fold(
                        {
                            if (PasteBuffer.rel == target.relativePath) {
                                PasteBuffer.domain = null
                                PasteBuffer.rel = null
                            }
                            refresh++
                        },
                        {
                            Toast.makeText(
                                context, "Delete failed: ${it.message}", Toast.LENGTH_LONG
                            ).show()
                        },
                    )
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** One file/folder row with overflow menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    expanded: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenMenu: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (entry.isDir) AppIcons.Folder else DocIcon,
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isDir) "folder" else "${humanizeBytes(entry.size)} · ${formatDate(entry.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("Open") }, onClick = onOpenMenu)
            DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
            DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
            DropdownMenuItem(text = { Text("Copy") }, onClick = onCopy)
            DropdownMenuItem(text = { Text("Cut") }, onClick = onCut)
            if (!entry.isDir) {
                DropdownMenuItem(text = { Text("Download to phone…") }, onClick = onExport)
            }
        }
    }
}

/** SHARED tab: honest explanation + SAF import into the workspace root. */
@Composable
private fun SharedTabContent(onImport: () -> Unit) {
    Column(Modifier.padding(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Android shared storage", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android shared storage uses the system document picker (SAF). " +
                        "This app has no direct write access to it — import a file with the " +
                        "picker and it lands in the workspace root inside the Ubuntu userspace.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport) { Text("Upload file from phone") }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "Tip: exported files from other tabs use the system save dialog " +
                            "(row menu → Export…).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        StatusPill("SAF only", ok = null, modifier = Modifier.padding(top = 12.dp))
    }
}
