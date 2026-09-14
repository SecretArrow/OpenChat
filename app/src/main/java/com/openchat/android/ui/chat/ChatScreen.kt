package com.openchat.android.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import com.openchat.android.AppGraph
import com.openchat.android.ai.local.LocalEngineState
import com.openchat.android.core.model.Attachment
import com.openchat.android.core.model.AttachmentKind
import com.openchat.android.core.model.ChatBackend
import com.openchat.android.core.model.ChatMessage
import com.openchat.android.core.model.Role
import com.openchat.android.core.model.ToolBlock
import com.openchat.android.ui.components.AppIcons
import com.openchat.android.ui.components.CopyIconButton
import com.openchat.android.ui.components.ErrorCard
import com.openchat.android.ui.components.MarkdownText
import com.openchat.android.ui.components.ModelPicker
import com.openchat.android.ui.components.StatusPill
import com.openchat.android.ui.components.humanizeBytes
import kotlinx.coroutines.launch

/**
 * Chat tab (spec §5) — Agent-first:
 *  - streaming auto-scrolls ONLY while the user sits at the bottom; scrolling
 *    up pauses it instantly, returning to the bottom (or tapping
 *    "Scroll to latest") resumes it — the list never fights the user;
 *  - every assistant reply carries a "By <model> [Copy]" footer (copy takes
 *    the reply body only, right-aligned on the same row);
 *  - the composer is [+ attach] [Web] [message] [Send]: attachments
 *    (text/code inlined, images sent as vision input, binaries named) and a
 *    per-conversation web-search switch whose sources render as tappable
 *    chips under the reply.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(onOpenTerminal: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val conversations by AppGraph.chat.conversations.collectAsState()
    val activeId by AppGraph.chat.activeId.collectAsState()
    val streaming by AppGraph.chat.streaming.collectAsState()
    val lastError by AppGraph.chat.lastError.collectAsState()
    val localState by AppGraph.localEngine.engineState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val conv = conversations.firstOrNull { it.id == activeId }
    val messages: List<ChatMessage> = conv?.messages ?: emptyList()
    var input by remember { mutableStateOf("") }
    val pendingAttachments = remember { mutableStateListOf<Attachment>() }

    fun toast(msg: String) {
        android.widget.Toast.makeText(AppGraph.appContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    // Attachment picking: images (vision), text/code (inlined), other docs
    // (named). Every unreadable file degrades to a toast — never a crash.
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                uris.forEach { uri ->
                    com.openchat.android.ai.AttachmentFiles.fromUri(AppGraph.appContext, uri).fold(
                        { att ->
                            if (pendingAttachments.size < 6) {
                                pendingAttachments.add(att)
                            } else {
                                toast("Up to 6 attachments per message")
                            }
                        },
                        { t -> toast(t.message ?: "Could not attach the file") },
                    )
                }
            }
        }
    }

    // Plain-language starter prompts (z.ai-style guidance for non-CLI users).
    val suggestions = listOf(
        "List the files in this workspace and explain the project",
        "Write and run a Python script that builds a small sales report",
        "Create a Node.js script that counts word frequencies in a text",
        "Make a bash script that backs up a folder with a timestamp",
    )

    // Ensure there is always an active conversation on first composition.
    LaunchedEffect(Unit) {
        val existing = AppGraph.chat.conversations.value
        val active = AppGraph.chat.activeId.value
        when {
            existing.none { it.id == active } && existing.isNotEmpty() ->
                AppGraph.chat.open(existing.maxByOrNull { it.updatedAt }!!.id)
            existing.isEmpty() -> {
                val c = AppGraph.chat.newConversation()
                AppGraph.chat.open(c.id)
            }
        }
    }

    val listState = rememberLazyListState()

    // ---- Auto-scroll that never fights the user -----------------------------
    // atBottom is derived from the layout info: the user is "at the bottom"
    // while the last (or streaming) item is visible. Any scroll up flips it
    // off; scrolling back (or the button) flips it on and streaming resumes.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 2
        }
    }
    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0

    // New message appeared → smooth scroll (only when the user allows it).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && atBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    // Streaming delta → instant re-pin (no animation spam while typing).
    LaunchedEffect(lastContentLength, streaming) {
        if (streaming && messages.isNotEmpty() && atBottom) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Conversations",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        scope.launch {
                            val c = AppGraph.chat.newConversation()
                            AppGraph.chat.open(c.id)
                            drawerState.close()
                        }
                    }) { Text("New chat") }
                }
                LazyColumn {
                    val sorted = conversations.sortedByDescending { it.updatedAt }
                    items(sorted, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        AppGraph.chat.open(c.id)
                                        drawerState.close()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    c.title,
                                    maxLines = 1,
                                    color = if (c.id == activeId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(onClick = { AppGraph.chat.delete(c.id) }) {
                                Icon(AppIcons.Delete, contentDescription = "Delete conversation")
                            }
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(conv?.title ?: "Chat", maxLines = 1)
                            ModelPicker(
                                selectedModelId = conv?.modelId,
                                onSelect = { m ->
                                    conv?.let { AppGraph.chat.setActiveModel(it.id, m.id) }
                                },
                                compact = true,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "History")
                        }
                    },
                    actions = {
                        FilterChip(
                            selected = conv?.backend == ChatBackend.OPENCODE,
                            onClick = {
                                conv?.let { c ->
                                    val next =
                                        if (c.backend == ChatBackend.OPENCODE) ChatBackend.DIRECT
                                        else ChatBackend.OPENCODE
                                    AppGraph.chat.setBackend(c.id, next)
                                }
                            },
                            label = { Text(if (conv?.backend == ChatBackend.OPENCODE) "Agent" else "Chat") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            },
        ) { pad ->
            Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (conv?.backend == ChatBackend.OPENCODE) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Agent mode — the AI runs commands inside the Ubuntu workspace. " +
                                    "Executed steps appear below as tool cards.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f),
                            )
                            if (onOpenTerminal != null) {
                                TextButton(onClick = onOpenTerminal) { Text("Terminal") }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = 12.dp, vertical = 8.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (messages.isEmpty()) {
                                item(key = "empty") {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 48.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text("Start coding with AI\u2026", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            "Pick a model above, then send your first prompt. " +
                                                "Switch to Agent mode to let the AI run commands " +
                                                "inside the Ubuntu workspace for you.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                        Spacer(Modifier.size(10.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        ) {
                                            suggestions.forEach { s ->
                                                SuggestionChip(
                                                    onClick = { input = s },
                                                    label = { Text(s, style = MaterialTheme.typography.bodySmall) },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            items(messages, key = { it.id }) { msg ->
                                MessageRow(msg)
                            }
                            if (streaming) {
                                item(key = "streaming") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        val agentMode = conv?.backend == ChatBackend.OPENCODE
                                        val steps =
                                            messages.lastOrNull { it.role == Role.ASSISTANT }?.toolBlocks?.size ?: 0
                                        Text(
                                            when {
                                                localState is LocalEngineState.Loading ->
                                                    "Loading local model… (first load can take a while)"
                                                agentMode -> "Agent working… ($steps step${if (steps == 1) "" else "s"} so far)"
                                                else -> "Generating…"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        TextButton(onClick = { AppGraph.chat.cancel() }) { Text("Cancel") }
                                    }
                                }
                            }
                        }

                        // "Scroll to latest" — appears only when the user has
                        // scrolled away from the live bottom.
                        if (!atBottom && messages.isNotEmpty()) {
                            androidx.compose.material3.SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem(messages.size - 1)
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(12.dp),
                            ) {
                                Text("↓ Latest", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    ErrorCard(
                        info = lastError,
                        onRetry = { scope.launch { AppGraph.chat.retry() } },
                    )

                    if (!streaming && messages.any { it.role == Role.ASSISTANT }) {
                        TextButton(
                            onClick = { scope.launch { AppGraph.chat.regenerate() } },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("Regenerate") }
                    }

                    if (pendingAttachments.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            pendingAttachments.forEach { att ->
                                SuggestionChip(
                                    onClick = { pendingAttachments.remove(att) },
                                    label = {
                                        Text(
                                            "${att.name} · ${humanizeBytes(att.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                )
                            }
                            SuggestionChip(
                                onClick = { pendingAttachments.clear() },
                                label = { Text("Clear all", style = MaterialTheme.typography.bodySmall) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        IconButton(
                            onClick = {
                                attachLauncher.launch(
                                    arrayOf(
                                        "image/*",
                                        "text/*",
                                        "application/json",
                                        "application/xml",
                                        "application/pdf",
                                        "application/zip",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            enabled = !streaming,
                        ) {
                            Icon(AppIcons.Add, contentDescription = "Attach files")
                        }
                        FilterChip(
                            selected = conv?.webSearch == true,
                            onClick = {
                                conv?.let { AppGraph.chat.setWebSearch(it.id, !(it.webSearch)) }
                            },
                            label = { Text("Web") },
                            enabled = !streaming,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message…") },
                            maxLines = 5,
                            enabled = !streaming,
                        )
                        TextButton(
                            onClick = {
                                val text = input
                                input = ""
                                val atts = pendingAttachments.toList()
                                pendingAttachments.clear()
                                scope.launch { AppGraph.chat.send(text, atts) }
                            },
                            enabled = !streaming &&
                                (input.isNotBlank() || pendingAttachments.isNotEmpty()) &&
                                conv != null,
                            modifier = Modifier.padding(start = 4.dp),
                        ) { Text("Send") }
                    }
                }
            }
        }
    }
}

/** Renders one message: user bubble (right) or assistant card (left) with tools. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageRow(msg: ChatMessage) {
    val models by AppGraph.models.models.collectAsState()
    if (msg.role == Role.USER) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(10.dp),
                )
            }
            if (msg.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    msg.attachments.forEach { att ->
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    when (att.kind) {
                                        AttachmentKind.IMAGE -> "🖼 ${att.name}"
                                        AttachmentKind.TEXT -> "📄 ${att.name}"
                                        AttachmentKind.BINARY -> "📦 ${att.name}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    if (msg.error != null) {
                        Text(
                            msg.error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (msg.content.isNotEmpty()) {
                        MarkdownText(msg.content)
                    }
                    msg.toolBlocks.forEach { block -> ToolBlockCard(block) }
                }
            }
            // Footer: "By <model>" on the left, [Copy] right-aligned on the
            // same row — copies the reply BODY only, never metadata.
            if (msg.error == null && msg.content.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name = msg.modelId?.let { id ->
                        models.firstOrNull { it.id == id }?.displayName
                    }
                    Text(
                        name?.let { "By $it" } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    CopyIconButton(msg.content)
                }
            }
        }
    }
}

/** Tool execution block: label, monospace selectable command, collapsible output. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolBlockCard(block: ToolBlock) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    block.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                block.exitCode?.let { code -> StatusPill("exit $code", ok = code == 0) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        block.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                CopyIconButton(block.command)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide output" else "Show output")
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        block.output.ifBlank { "(no output)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            // Web-search sources: tappable chips that open in the browser.
            if (block.sources.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    block.sources.forEach { url ->
                        AssistChip(
                            onClick = { openUrl(url) },
                            label = {
                                Text(
                                    hostOf(url),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun openUrl(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppGraph.appContext.startActivity(intent)
    }
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host ?: url }.getOrDefault(url).removePrefix("www.")
