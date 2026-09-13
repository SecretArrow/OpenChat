package com.openchat.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

/**
 * Chat tab (spec §5): conversation drawer, model picker, backend toggle,
 * message list with markdown + tool blocks, streaming state, input row,
 * error surface with retry. Model switching never clears messages.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(onOpenTerminal: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val conversations by AppGraph.chat.conversations.collectAsState()
    val activeId by AppGraph.chat.activeId.collectAsState()
    val streaming by AppGraph.chat.streaming.collectAsState()
    val lastError by AppGraph.chat.lastError.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val conv = conversations.firstOrNull { it.id == activeId }
    val messages: List<ChatMessage> = conv?.messages ?: emptyList()
    var input by remember { mutableStateOf("") }

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
    LaunchedEffect(messages.size, streaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
            ) {
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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
                                    if (agentMode) "Agent working… ($steps step${if (steps == 1) "" else "s"} so far)"
                                    else "Generating…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { AppGraph.chat.cancel() }) { Text("Cancel") }
                            }
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
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
                            scope.launch { AppGraph.chat.send(text) }
                        },
                        enabled = !streaming && input.isNotBlank() && conv != null,
                        modifier = Modifier.padding(start = 4.dp),
                    ) { Text("Send") }
                }
            }
        }
    }
}

/** Renders one message: user bubble (right) or assistant card (left) with tools. */
@Composable
private fun MessageRow(msg: ChatMessage) {
    val models by AppGraph.models.models.collectAsState()
    if (msg.role == Role.USER) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
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
            msg.modelId?.let { modelId ->
                val name = models.firstOrNull { it.id == modelId }?.displayName
                if (name != null) {
                    Text(
                        "by $name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Tool execution block: label, monospace selectable command, collapsible output. */
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
        }
    }
}
