package com.openchat.android.ai

import com.openchat.android.AppGraph
import com.openchat.android.ai.local.LocalInferenceEngine
import com.openchat.android.ai.local.LocalModelSpec
import com.openchat.android.ai.opencode.OpenCodeController
import com.openchat.android.ai.providers.ChatClients
import com.openchat.android.ai.providers.ChatRequest
import com.openchat.android.ai.providers.ProviderException
import com.openchat.android.core.model.AIModel
import com.openchat.android.core.model.Attachment
import com.openchat.android.core.model.ChatBackend
import com.openchat.android.core.model.ChatMessage
import com.openchat.android.core.model.Conversation
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.ProviderType
import com.openchat.android.core.model.Role
import com.openchat.android.core.model.ToolBlock
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SettingsStore
import com.openchat.android.core.util.Errors
import com.openchat.android.core.util.Redact
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat orchestration (contract: ai/ChatService.kt).
 *
 * Persistence: `conversations/index.json` + `conversations/<id>.json` (atomic
 * writes). While streaming, the assistant message is updated live in the
 * [conversations] StateFlow so the UI sees typing, and the file is re-written at
 * the end plus roughly every second.
 *
 * Backend resolution: DIRECT → provider transport via [ChatClients];
 * OPENCODE → [OpenCodeController.run] with the current workspace from
 * `AppGraph.workspaces` (integration point owned by the integrator's graph).
 */
class ChatService(
    private val json: JsonStore,
    private val providers: ProviderManager,
    private val models: ModelManager,
    private val opencode: OpenCodeController,
    private val settings: SettingsStore,
    private val local: LocalInferenceEngine? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val loaded = loadConversations()

    private val conversationsState = MutableStateFlow(loaded.first)
    val conversations: StateFlow<List<Conversation>> = conversationsState

    private val activeIdState = MutableStateFlow(loaded.second)
    val activeId: StateFlow<String?> = activeIdState

    private val streamingState = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = streamingState

    private val lastErrorState = MutableStateFlow<ErrorInfo?>(null)
    val lastError: StateFlow<ErrorInfo?> = lastErrorState

    private var job: Job? = null

    // ------------------------------------------------------------- conversations

    fun newConversation(
        title: String = "New chat",
        modelId: String? = null,
        backend: ChatBackend = ChatBackend.DIRECT,
    ): Conversation {
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = title,
            modelId = modelId,
            backend = backend,
        )
        conversationsState.update { it + conv }
        activeIdState.value = conv.id
        persistConversation(conv)
        persistIndex()
        return conv
    }

    fun open(id: String) {
        if (conversationsState.value.any { it.id == id }) {
            activeIdState.value = id
            persistIndex()
        }
    }

    fun delete(id: String) {
        conversationsState.update { list -> list.filterNot { it.id == id } }
        File(json.conversationsDir, "$id.json").delete()
        if (activeIdState.value == id) {
            activeIdState.value = conversationsState.value.lastOrNull()?.id
        }
        persistIndex()
    }

    fun rename(id: String, title: String) {
        conversationsState.update { list ->
            list.map { if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it }
        }
        conversationById(id)?.let { persistConversation(it) }
        persistIndex()
    }

    /** Switches the conversation model; messages are never cleared. */
    fun setActiveModel(conversationId: String, modelId: String?) {
        conversationsState.update { list ->
            list.map {
                if (it.id == conversationId) it.copy(modelId = modelId, updatedAt = System.currentTimeMillis()) else it
            }
        }
        conversationById(conversationId)?.let { persistConversation(it) }
    }

    fun setBackend(conversationId: String, backend: ChatBackend) {
        conversationsState.update { list ->
            list.map {
                if (it.id == conversationId) it.copy(backend = backend, updatedAt = System.currentTimeMillis()) else it
            }
        }
        conversationById(conversationId)?.let { persistConversation(it) }
    }

    /** Toggles the per-conversation Agent web-search switch. */
    fun setWebSearch(conversationId: String, enabled: Boolean) {
        conversationsState.update { list ->
            list.map {
                if (it.id == conversationId) it.copy(webSearch = enabled, updatedAt = System.currentTimeMillis()) else it
            }
        }
        conversationById(conversationId)?.let { persistConversation(it) }
    }

    /** Appends a tool block to the last assistant message (or creates one). */
    fun appendToolBlock(conversationId: String, block: ToolBlock) {
        val conv = conversationById(conversationId) ?: return
        val msgs = conv.messages.toMutableList()
        val idx = msgs.indexOfLast { it.role == Role.ASSISTANT }
        if (idx >= 0) {
            val m = msgs[idx]
            msgs[idx] = m.copy(toolBlocks = m.toolBlocks + block)
        } else {
            msgs.add(
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = Role.ASSISTANT,
                    content = "",
                    toolBlocks = listOf(block),
                )
            )
        }
        replaceMessages(conversationId, msgs)
        conversationById(conversationId)?.let { persistConversation(it) }
    }

    // ------------------------------------------------------------------ sending

    /**
     * Appends the user message (with its attachments) and streams the
     * assistant reply into the active conversation. Cancellation (via [cancel]
     * or caller scope) keeps partial content and never marks an error.
     */
    suspend fun send(text: String, attachments: List<Attachment> = emptyList()) {
        if ((text.isBlank() && attachments.isEmpty()) || streamingState.value) return
        val conv = activeConversation() ?: newConversation()
        startExchange(conv.id, text, addUserMessage = true, attachments = attachments)
    }

    /** Cancels the running streaming job; partial content is kept. */
    fun cancel() {
        job?.cancel()
    }

    /** Drops the last assistant message and re-runs the last user message. */
    suspend fun regenerate() = rerunLastAssistant(checkErrorOnly = false)

    /** Re-runs the last exchange only when it failed (assistant message has an error). */
    suspend fun retry() = rerunLastAssistant(checkErrorOnly = true)

    // ------------------------------------------------------------------ internals

    private suspend fun startExchange(
        conversationId: String,
        text: String,
        addUserMessage: Boolean,
        attachments: List<Attachment> = emptyList(),
    ) {
        if (streamingState.value) return
        streamingState.value = true
        val j = scope.launch { exchange(conversationId, text, addUserMessage, attachments) }
        job = j
        try {
            j.join()
        } catch (ce: CancellationException) {
            j.cancel()
            throw ce
        }
    }

    private suspend fun exchange(
        conversationId: String,
        text: String,
        addUserMessage: Boolean,
        attachments: List<Attachment> = emptyList(),
    ) {
        var conv = conversationById(conversationId) ?: return
        try {
            if (addUserMessage) {
                conv = appendMessage(
                    conv,
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = Role.USER,
                        content = Attachments.composeUserContent(text, attachments),
                        attachments = attachments,
                    ),
                )
            }
            // On-device models are routed by their reserved "local:" id prefix —
            // they bypass the provider registry entirely (download state is
            // validated inside the engine, with honest errors).
            val localSpec = conv.modelId
                ?.takeIf { it.startsWith(LocalModelSpec.AI_PREFIX) }
                ?.let { local?.specFor(it) }
            val model = if (localSpec != null) {
                localSpec.toAIModel()
            } else {
                resolveModel(conv)
            }
            if (model == null) {
                failWith(conv, noModelError(), assistantId = null)
                return
            }
            val assistant = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = Role.ASSISTANT,
                content = "",
                modelId = model.id,
            )
            conv = appendMessage(conv, assistant)
            // Agent web search: query BEFORE the model call, attach the
            // results as a tool block (visible sources) and inject the context
            // into the outgoing prompt. A search failure degrades gracefully —
            // the chat proceeds without web context.
            var webContext: String? = null
            if (conv.webSearch && localSpec == null && conv.backend == ChatBackend.DIRECT && text.isNotBlank()) {
                val search = runWebSearch(conv.id, text)
                webContext = search.second
            }
            when {
                localSpec != null && local != null -> runLocal(conv, assistant.id, localSpec, model)
                conv.backend == ChatBackend.OPENCODE -> runOpencode(conv, assistant.id, text, model)
                else -> runDirect(conv, assistant.id, model, webContext)
            }
        } finally {
            streamingState.value = false
            conversationById(conversationId)?.let { persistConversation(it) }
            persistIndex()
        }
    }

    /**
     * Runs the web search for [query], appends its tool block (with sources)
     * to the newest assistant message and returns the prompt context block
     * (null when the search failed — the failure itself stays visible in the
     * tool block output).
     */
    private suspend fun runWebSearch(conversationId: String, query: String): Pair<ToolBlock, String?> {
        val result = WebSearch.search(query)
        val block = result.fold(
            onSuccess = { hits ->
                ToolBlock(
                    id = UUID.randomUUID().toString(),
                    label = "Web search",
                    command = query,
                    output = if (hits.isEmpty()) {
                        "(no results)"
                    } else {
                        hits.mapIndexed { i, h -> "${i + 1}. ${h.title}\n   ${h.url}" }.joinToString("\n")
                    },
                    sources = WebSearch.hitsToSources(hits),
                )
            },
            onFailure = { t ->
                ToolBlock(
                    id = UUID.randomUUID().toString(),
                    label = "Web search",
                    command = query,
                    output = "(search failed — answering without web context: ${t.message ?: t.javaClass.simpleName})",
                )
            },
        )
        appendToolBlock(conversationId, block)
        val context = result.getOrNull()?.let { WebSearchParser.contextBlock(query, it) }
        return block to context
    }

    /** LOCAL backend: on-device GGUF inference via [LocalInferenceEngine]. */
    private suspend fun runLocal(
        conv: Conversation,
        assistantId: String,
        spec: LocalModelSpec,
        model: AIModel,
    ) {
        val engine = local ?: run {
            failWith(
                conv,
                ErrorInfo(
                    title = "On-device engine unavailable",
                    detail = "The local inference engine is not initialized in this build.",
                    suggestions = listOf("Restart the app", "Use a cloud model instead"),
                ),
                assistantId,
            )
            return
        }
        var lastPersist = System.currentTimeMillis()
        val history = conv.messages
            .filter { it.content.isNotBlank() }
            .map { (if (it.role == Role.USER) "user" else "assistant") to it.content }
        engine.generate(
            spec = spec,
            history = history,
            maxTokens = model.maxTokens,
            temperature = model.temperature,
            onDelta = { delta ->
                updateMessage(conv.id, assistantId) { it.copy(content = it.content + delta) }
                val now = System.currentTimeMillis()
                if (now - lastPersist > PERSIST_INTERVAL_MS) {
                    lastPersist = now
                    conversationById(conv.id)?.let { persistConversation(it) }
                }
            },
        ).fold(
            onSuccess = { conversationById(conv.id)?.let { persistConversation(it) } },
            onFailure = { t ->
                if (t is CancellationException) throw t
                failWith(
                    conv,
                    ErrorInfo(
                        title = "Local model error",
                        detail = Redact.scrub(t.message ?: "unknown error"),
                        suggestions = listOf(
                            "Check free RAM (close other apps)",
                            "Settings → Local models → re-download the model",
                        ),
                        retryable = true,
                    ),
                    assistantId,
                )
            },
        )
    }

    /** DIRECT backend: stream deltas from the provider transport into the message. */
    private suspend fun runDirect(
        conv: Conversation,
        assistantId: String,
        model: AIModel,
        webContext: String? = null,
    ) {
        val provider = providers.providers.value.firstOrNull { it.id == model.providerId }
        if (provider == null) {
            failWith(conv, providerMissingError(model), assistantId)
            return
        }
        val apiKey = providers.apiKeyFor(provider).orEmpty()
        if (apiKey.isBlank() && provider.type != ProviderType.OLLAMA) {
            failWith(conv, Errors.providerAuth(provider.name), assistantId)
            return
        }
        // Inject the web-search context into the LAST user message of the
        // outgoing request only — the stored message keeps its clean text.
        val history = conv.messages.filter { it.content.isNotBlank() }
        val messages = if (webContext != null) {
            history.mapIndexed { i, m ->
                if (i == history.lastIndex && m.role == Role.USER) m.copy(content = m.content + "\n\n" + webContext) else m
            }
        } else {
            history
        }
        val request = ChatRequest(
            model = model,
            provider = provider,
            apiKey = apiKey,
            messages = messages,
            maxTokens = model.maxTokens,
            temperature = model.temperature,
        )
        var lastPersist = System.currentTimeMillis()
        try {
            ChatClients.forProvider(provider.type).stream(request) { delta ->
                updateMessage(conv.id, assistantId) { it.copy(content = it.content + delta) }
                val now = System.currentTimeMillis()
                if (now - lastPersist > PERSIST_INTERVAL_MS) {
                    lastPersist = now
                    conversationById(conv.id)?.let { persistConversation(it) }
                }
            }.fold(
                onSuccess = { conversationById(conv.id)?.let { persistConversation(it) } },
                onFailure = { t -> failWith(conv, errorOf(t, apiKey), assistantId) },
            )
        } catch (ce: CancellationException) {
            // Cancelled by the user — keep partial content, no error (spec §25).
        }
    }

    /** OPENCODE backend: run the OpenCode CLI in the current Ubuntu workspace. */
    private suspend fun runOpencode(conv: Conversation, assistantId: String, prompt: String, model: AIModel) {
        // AppGraph is the integrator's singleton; defensive lookup keeps chat usable
        // (cwd falls back to /root) even before workspaces are initialized.
        val workspace = withContext(Dispatchers.IO) {
            runCatching { AppGraph.workspaces.current() }.getOrNull()
        }
        val result = try {
            opencode.run(prompt, workspace, model) { block -> appendToolBlock(conv.id, block) }
        } catch (ce: CancellationException) {
            null // cancelled — keep partial tool blocks/content, no error
        }
        result?.fold(
            onSuccess = { reply ->
                if (reply.isNotBlank()) updateMessage(conv.id, assistantId) { it.copy(content = reply) }
                conversationById(conv.id)?.let { persistConversation(it) }
            },
            onFailure = { t -> failWith(conv, errorOf(t), assistantId) },
        )
    }

    private suspend fun rerunLastAssistant(checkErrorOnly: Boolean) {
        if (streamingState.value) return
        val conv = activeConversation() ?: return
        val last = conv.messages.lastOrNull() ?: return
        if (last.role != Role.ASSISTANT) return
        if (checkErrorOnly && last.error == null) return
        val remaining = conv.messages.dropLast(1)
        val lastUser = remaining.lastOrNull { it.role == Role.USER }?.content ?: return
        replaceMessages(conv.id, remaining)
        startExchange(conv.id, lastUser, addUserMessage = false)
    }

    private fun resolveModel(conv: Conversation): AIModel? {
        val chosen = conv.modelId?.let { id -> models.models.value.firstOrNull { it.id == id } }
        return chosen ?: models.defaultModel()
    }

    /** Marks the assistant message failed and publishes [lastError]. */
    private fun failWith(conv: Conversation, info: ErrorInfo, assistantId: String?) {
        lastErrorState.value = info
        if (assistantId != null && conversationById(conv.id)?.messages?.any { it.id == assistantId } == true) {
            updateMessage(conv.id, assistantId) { it.copy(error = info.title + " — " + info.detail) }
        } else {
            appendMessage(
                conv,
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = Role.ASSISTANT,
                    content = "",
                    error = info.title + " — " + info.detail,
                ),
            )
        }
        conversationById(conv.id)?.let { persistConversation(it) }
    }

    private fun errorOf(t: Throwable, vararg secrets: String?): ErrorInfo =
        (t as? ProviderException)?.info ?: run {
            val info = Errors.network(t, "Chat")
            info.copy(detail = Redact.scrub(info.detail, *secrets))
        }

    private fun noModelError(): ErrorInfo = ErrorInfo(
        title = "No model selected",
        detail = "This conversation has no model and no default model is configured.",
        causes = listOf("No default model set", "The conversation's model was deleted"),
        suggestions = listOf("Open Settings → Models and add or enable a model", "Pick a model in the chat header"),
        retryable = true,
    )

    private fun providerMissingError(model: AIModel): ErrorInfo = ErrorInfo(
        title = "Provider not found",
        detail = "Model '${model.displayName}' references provider '${model.providerId}' which no longer exists.",
        causes = listOf("The provider was deleted", "Wrong providerId on the model"),
        suggestions = listOf("Edit the model in Settings → Models and choose an existing provider"),
        retryable = false,
    )

    // ------------------------------------------------------------------ state

    private fun conversationById(id: String): Conversation? =
        conversationsState.value.firstOrNull { it.id == id }

    private fun activeConversation(): Conversation? =
        activeIdState.value?.let { conversationById(it) }

    private fun appendMessage(conv: Conversation, message: ChatMessage): Conversation {
        val updated = conv.copy(
            messages = (conv.messages + message).toMutableList(),
            updatedAt = System.currentTimeMillis(),
        )
        conversationsState.update { list -> list.map { if (it.id == conv.id) updated else it } }
        persistConversation(updated)
        return updated
    }

    private fun replaceMessages(conversationId: String, messages: List<ChatMessage>) {
        conversationsState.update { list ->
            list.map {
                if (it.id == conversationId) {
                    it.copy(messages = messages.toMutableList(), updatedAt = System.currentTimeMillis())
                } else it
            }
        }
    }

    private fun updateMessage(conversationId: String, messageId: String, transform: (ChatMessage) -> ChatMessage) {
        conversationsState.update { list ->
            list.map { c ->
                if (c.id != conversationId) c
                else {
                    val idx = c.messages.indexOfFirst { it.id == messageId }
                    if (idx < 0) c
                    else {
                        val msgs = c.messages.toMutableList()
                        msgs[idx] = transform(msgs[idx])
                        c.copy(messages = msgs, updatedAt = System.currentTimeMillis())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ backup

    /** Full chat backup as a single JSON document (Settings → Backup & restore). */
    fun exportAllJson(): String = ChatBackup.toJson(conversationsState.value).toString()

    /**
     * Merges conversations from a backup document. Conversations already on
     * device (same id) are skipped — import never destroys local data.
     * @return number of conversations added.
     */
    fun importFromJson(text: String): Result<Int> =
        ChatBackup.parse(text).map { incoming ->
            val existing = conversationsState.value.map { it.id }.toHashSet()
            val fresh = incoming.filter { it.id !in existing }
            if (fresh.isNotEmpty()) {
                conversationsState.update { it + fresh }
                fresh.forEach { persistConversation(it) }
                persistIndex()
            }
            fresh.size
        }

    // ------------------------------------------------------------------ persistence

    private fun persistConversation(c: Conversation) {
        atomicWrite(File(json.conversationsDir, c.id + ".json"), c.toJson().toString())
    }

    private fun persistIndex() {
        val arr = JSONArray()
        conversationsState.value.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("title", c.title).put("updatedAt", c.updatedAt))
        }
        val root = JSONObject()
            .put("items", arr)
            .put("activeId", activeIdState.value ?: JSONObject.NULL)
        json.writeText(INDEX_FILE, root.toString())
    }

    private fun atomicWrite(f: File, content: String) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "atomic rename failed for ${f.name}" }
            }
        } catch (t: Throwable) {
            Redact.w(TAG, "persist failed: ${f.name}: ${t.message}")
        }
    }

    private fun loadConversations(): Pair<List<Conversation>, String?> {
        val index = json.readText(INDEX_FILE)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val ids = ArrayList<String>()
        var active: String? = null
        if (index != null) {
            active = if (index.isNull("activeId")) null else index.optString("activeId").ifEmpty { null }
            index.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).optString("id"))
            }
        }
        if (ids.isEmpty()) {
            json.conversationsDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".json")) ids.add(f.name.removeSuffix(".json"))
            }
        }
        val loadedConversations = ArrayList<Conversation>()
        ids.forEach { id ->
            val f = File(json.conversationsDir, "$id.json")
            if (f.exists()) {
                runCatching { Conversation.fromJson(JSONObject(f.readText())) }
                    .getOrNull()
                    ?.let { loadedConversations.add(it) }
            }
        }
        if (active == null || loadedConversations.none { it.id == active }) {
            active = loadedConversations.lastOrNull()?.id
        }
        return Pair(loadedConversations, active)
    }

    companion object {
        private const val TAG = "OpenChat/ChatService"
        const val INDEX_FILE = "conversations/index.json"
        private const val PERSIST_INTERVAL_MS = 1000L
    }
}
