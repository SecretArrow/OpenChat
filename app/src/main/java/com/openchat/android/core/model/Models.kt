package com.openchat.android.core.model

import org.json.JSONObject

// ---------------------------------------------------------------------------
// Core domain models for Open Chat.
// These are the single source of truth shared by every layer (spec §17).
// ---------------------------------------------------------------------------

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI, OPENROUTER, OLLAMA, CUSTOM_OPENAI;

    companion object {
        fun from(raw: String?): ProviderType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CUSTOM_OPENAI
    }
}

data class Provider(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    /** Reference id into SecretStore — the raw key is NEVER stored here. */
    val apiKeyRef: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type.name)
        .put("baseUrl", baseUrl)
        .put("apiKeyRef", apiKeyRef ?: JSONObject.NULL)
        .put("headers", JSONObject(headers))
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): Provider = Provider(
            id = o.optString("id"),
            name = o.optString("name", "Provider"),
            type = ProviderType.from(o.optString("type")),
            baseUrl = o.optString("baseUrl"),
            apiKeyRef = if (o.isNull("apiKeyRef")) null else o.optString("apiKeyRef"),
            headers = o.optJSONObject("headers")?.let { jo ->
                buildMap { jo.keys().forEach { k -> put(k, jo.optString(k)) } }
            } ?: emptyMap(),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

data class AIModel(
    val id: String,
    val displayName: String,
    val providerId: String,
    val modelName: String,
    val baseUrl: String? = null,
    val contextLength: Int = 8192,
    val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
    val reasoning: Boolean = false,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("providerId", providerId)
        .put("modelName", modelName)
        .put("baseUrl", baseUrl ?: JSONObject.NULL)
        .put("contextLength", contextLength)
        .put("maxTokens", maxTokens)
        .put("temperature", temperature)
        .put("reasoning", reasoning)
        .put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject): AIModel = AIModel(
            id = o.optString("id"),
            displayName = o.optString("displayName", "Model"),
            providerId = o.optString("providerId"),
            modelName = o.optString("modelName"),
            baseUrl = if (o.isNull("baseUrl")) null else o.optString("baseUrl"),
            contextLength = o.optInt("contextLength", 8192),
            maxTokens = o.optInt("maxTokens", 2048),
            temperature = o.optDouble("temperature", 0.7),
            reasoning = o.optBoolean("reasoning", false),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** Attachment kind — drives how the composer preview and the transports treat it. */
enum class AttachmentKind { TEXT, IMAGE, BINARY }

/**
 * A file attached to a chat message. TEXT files carry their decoded content
 * (injected into the prompt as a fenced block); IMAGE files carry a local
 * path (base64-encoded at send time for vision-capable providers); BINARY
 * files are named only (content is never sent).
 */
data class Attachment(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    val textContent: String? = null,
    val localPath: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("mime", mime)
        .put("sizeBytes", sizeBytes)
        .put("kind", kind.name)
        .put("textContent", textContent ?: JSONObject.NULL)
        .put("localPath", localPath ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Attachment = Attachment(
            name = o.optString("name", "file"),
            mime = o.optString("mime", "application/octet-stream"),
            sizeBytes = o.optLong("sizeBytes", 0L),
            kind = AttachmentKind.entries.firstOrNull { it.name == o.optString("kind") }
                ?: AttachmentKind.BINARY,
            textContent = if (o.isNull("textContent")) null else o.optString("textContent"),
            localPath = if (o.isNull("localPath")) null else o.optString("localPath"),
        )
    }
}

/** A tool-execution block shown in chat (command + captured output, spec §5, §28). */
data class ToolBlock(
    val id: String,
    val label: String,
    val command: String,
    val output: String,
    val exitCode: Int? = null,
    /** Source URLs (web search results) rendered as tappable chips. */
    val sources: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("command", command)
        .put("output", output)
        .put("exitCode", exitCode ?: JSONObject.NULL)
        .put("sources", org.json.JSONArray(sources))

    companion object {
        fun fromJson(o: JSONObject): ToolBlock = ToolBlock(
            id = o.optString("id"),
            label = o.optString("label", "Tool"),
            command = o.optString("command"),
            output = o.optString("output"),
            exitCode = if (o.isNull("exitCode")) null else o.optInt("exitCode"),
            sources = o.optJSONArray("sources")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            } ?: emptyList(),
        )
    }
}

data class ChatMessage(
    val id: String,
    val role: Role,
    val content: String,
    val toolBlocks: List<ToolBlock> = emptyList(),
    val modelId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val error: String? = null,
    val attachments: List<Attachment> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("content", content)
        .put("toolBlocks", org.json.JSONArray(toolBlocks.map { it.toJson() }))
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("timestamp", timestamp)
        .put("error", error ?: JSONObject.NULL)
        .put("attachments", org.json.JSONArray(attachments.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): ChatMessage = ChatMessage(
            id = o.optString("id"),
            role = Role.entries.firstOrNull { it.name == o.optString("role") } ?: Role.USER,
            content = o.optString("content"),
            toolBlocks = o.optJSONArray("toolBlocks")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { ToolBlock.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            } ?: emptyList(),
            modelId = if (o.isNull("modelId")) null else o.optString("modelId"),
            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
            error = if (o.isNull("error")) null else o.optString("error"),
            attachments = o.optJSONArray("attachments")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { Attachment.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            } ?: emptyList(),
        )
    }
}

data class Conversation(
    val id: String,
    var title: String,
    val modelId: String? = null,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var backend: ChatBackend = ChatBackend.DIRECT,
    var webSearch: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("messages", org.json.JSONArray(messages.map { it.toJson() }))
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("backend", backend.name)
        .put("webSearch", webSearch)

    companion object {
        fun fromJson(o: JSONObject): Conversation = Conversation(
            id = o.optString("id"),
            title = o.optString("title", "Conversation"),
            modelId = if (o.isNull("modelId")) null else o.optString("modelId"),
            messages = o.optJSONArray("messages")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    runCatching { ChatMessage.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }?.toMutableList() ?: mutableListOf(),
            createdAt = o.optLong("createdAt", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            backend = ChatBackend.entries.firstOrNull { it.name == o.optString("backend") }
                ?: ChatBackend.DIRECT,
            webSearch = o.optBoolean("webSearch", false),
        )
    }
}

enum class ChatBackend { DIRECT, OPENCODE }

data class Workspace(
    val id: String,
    val name: String,
    val path: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val envVars: Map<String, String> = emptyMap(),
    val opencodeConfig: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var lastOpenedAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("path", path)
        .put("providerId", providerId ?: JSONObject.NULL)
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("envVars", JSONObject(envVars))
        .put("opencodeConfig", opencodeConfig ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("lastOpenedAt", lastOpenedAt ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Workspace = Workspace(
            id = o.optString("id"),
            name = o.optString("name", "Workspace"),
            path = o.optString("path"),
            providerId = if (o.isNull("providerId")) null else o.optString("providerId"),
            modelId = if (o.isNull("modelId")) null else o.optString("modelId"),
            envVars = o.optJSONObject("envVars")?.let { jo ->
                buildMap { jo.keys().forEach { k -> put(k, jo.optString(k)) } }
            } ?: emptyMap(),
            opencodeConfig = if (o.isNull("opencodeConfig")) null else o.optString("opencodeConfig"),
            createdAt = o.optLong("createdAt", 0L),
            lastOpenedAt = if (o.isNull("lastOpenedAt")) null else o.optLong("lastOpenedAt"),
        )
    }
}

enum class ProcState { RUNNING, EXITED, FAILED, KILLED }

data class ProcessInfo(
    val pid: Int,
    val sessionId: String,
    val title: String,
    val command: String,
    val cwd: String,
    val workspaceId: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    var state: ProcState = ProcState.RUNNING,
    var exitCode: Int? = null,
    var note: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pid", pid)
        .put("sessionId", sessionId)
        .put("title", title)
        .put("command", command)
        .put("cwd", cwd)
        .put("workspaceId", workspaceId ?: JSONObject.NULL)
        .put("startedAt", startedAt)
        .put("state", state.name)
        .put("exitCode", exitCode ?: JSONObject.NULL)
        .put("note", note ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): ProcessInfo = ProcessInfo(
            pid = o.optInt("pid"),
            sessionId = o.optString("sessionId"),
            title = o.optString("title", "process"),
            command = o.optString("command"),
            cwd = o.optString("cwd", "/root"),
            workspaceId = if (o.isNull("workspaceId")) null else o.optString("workspaceId"),
            startedAt = o.optLong("startedAt", 0L),
            state = ProcState.entries.firstOrNull { it.name == o.optString("state") }
                ?: ProcState.EXITED,
            exitCode = if (o.isNull("exitCode")) null else o.optInt("exitCode"),
            note = if (o.isNull("note")) null else o.optString("note"),
        )
    }
}

enum class UbuntuState {
    NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, CONFIGURING,
    INSTALLING_PACKAGES, INSTALLING_TOOLS, INSTALLING_OPENCODE,
    READY, REPAIRING, UPDATING, RESETTING, EXPORTING, IMPORTING, ERROR;

    val busy: Boolean get() = this in setOf(
        DOWNLOADING, VERIFYING, EXTRACTING, CONFIGURING,
        INSTALLING_PACKAGES, INSTALLING_TOOLS, INSTALLING_OPENCODE,
        REPAIRING, UPDATING, RESETTING, EXPORTING, IMPORTING,
    )
}

data class UbuntuStatus(
    val state: UbuntuState = UbuntuState.NOT_INSTALLED,
    val installed: Boolean = false,
    val version: String? = null,
    val arch: String? = null,
    val rootfsPath: String? = null,
    val lastUpdated: Long? = null,
    val message: String? = null,
    val progress: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name)
        .put("installed", installed)
        .put("version", version ?: JSONObject.NULL)
        .put("arch", arch ?: JSONObject.NULL)
        .put("rootfsPath", rootfsPath ?: JSONObject.NULL)
        .put("lastUpdated", lastUpdated ?: JSONObject.NULL)
        .put("message", message ?: JSONObject.NULL)
        .put("progress", progress)

    companion object {
        fun fromJson(o: JSONObject): UbuntuStatus = UbuntuStatus(
            state = UbuntuState.entries.firstOrNull { it.name == o.optString("state") }
                ?: UbuntuState.NOT_INSTALLED,
            installed = o.optBoolean("installed", false),
            version = if (o.isNull("version")) null else o.optString("version"),
            arch = if (o.isNull("arch")) null else o.optString("arch"),
            rootfsPath = if (o.isNull("rootfsPath")) null else o.optString("rootfsPath"),
            lastUpdated = if (o.isNull("lastUpdated")) null else o.optLong("lastUpdated"),
            message = if (o.isNull("message")) null else o.optString("message"),
            progress = o.optInt("progress", 0),
        )
    }
}

data class OpenCodeStatus(
    val installed: Boolean = false,
    val version: String? = null,
    val lastChecked: Long? = null,
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("installed", installed)
        .put("version", version ?: JSONObject.NULL)
        .put("lastChecked", lastChecked ?: JSONObject.NULL)
        .put("message", message ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): OpenCodeStatus = OpenCodeStatus(
            installed = o.optBoolean("installed", false),
            version = if (o.isNull("version")) null else o.optString("version"),
            lastChecked = if (o.isNull("lastChecked")) null else o.optLong("lastChecked"),
            message = if (o.isNull("message")) null else o.optString("message"),
        )
    }
}

data class OllamaServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKeyRef: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("apiKeyRef", apiKeyRef ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): OllamaServer = OllamaServer(
            id = o.optString("id"),
            name = o.optString("name", "Ollama"),
            baseUrl = o.optString("baseUrl"),
            apiKeyRef = if (o.isNull("apiKeyRef")) null else o.optString("apiKeyRef"),
        )
    }
}

data class OllamaModel(
    val name: String,
    val sizeBytes: Long = 0,
    val digest: String? = null,
    val modifiedAt: String? = null,
)

/** Structured, actionable error surfaced to the UI (spec §24 — never generic). */
enum class RepairAction { UBUNTU_REPAIR, UBUNTU_RESET, OPENCODE_REINSTALL, RETRY, OPEN_LOGS, CHECK_CONNECTION, INSTALL_UBUNTU }

data class ErrorInfo(
    val title: String,
    val detail: String,
    val causes: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val retryable: Boolean = false,
    val repairAction: RepairAction? = null,
)
