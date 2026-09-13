# INTEGRATION.md — Open Chat build contract (READ FIRST)

App: **Open Chat** — Android AI coding environment (real Ubuntu userspace + OpenCode CLI).
Repo local path: `/home/z/my-project/OpenChat`. Package root: `com.openchat.android`.

## Stack (fixed — do not change)
- Kotlin 2.0.20, AGP 8.5.2, Gradle 8.9, Jetpack Compose (BOM 2024.09.03, Material3), Navigation-Compose.
- minSdk 29, **targetSdk 28 (intentional: allows exec() of binaries in app data for proot/bash — do NOT change)**, compileSdk 34.
- JNI lib `libpty.so` via CMake (`app/src/main/cpp/pty.c`, already written).
- Allowed deps ONLY (already in app/build.gradle): androidx core-ktx/activity-compose/compose-*/navigation-compose/lifecycle-runtime-ktx/documentfile, kotlinx-coroutines-android, okhttp, commons-compress, junit. **NO other libraries, NO DI frameworks, NO Room, NO AAC ViewModels, NO kotlinx-serialization (use org.json).**

## Hard rules (spec §32)
- **NO placeholders / TODO / "coming soon" / mock / fake** — every method must do its real job or throw/return an actionable error.
- UI never touches runtime internals directly; only via `AppGraph` services (spec §17).
- Secrets never in logs/UI; use `Redact` for any log that may contain a key (spec §12).
- Never block the main thread. Managers own their own CoroutineScope (SupervisorJob + Dispatchers.IO).

## Singleton graph (`AppGraph` — written by integrator, do not create your own singletons)
```kotlin
object AppGraph {
    val appContext: Context
    fun init(context: Context)
    val secrets: SecretStore      // put(id,value), get(id), delete(id), exists(id), listIds()
    val settings: SettingsStore   // settings: StateFlow<AppSettings>; update { it.copy(...) }
    val json: JsonStore           // readText/writeText(name), file(name), dataDir, conversationsDir
    val providers: ProviderManager
    val models: ModelManager
    val ollama: OllamaManager
    val chat: ChatService
    val opencode: OpenCodeController
    val ubuntu: UbuntuRuntime
    val processes: UbuntuProcessManager
    val terminal: TerminalManager
    val workspaces: WorkspaceManager
    val files: FileManagerService
}
```
Data classes live in `core/model/Models.kt` (read it). Error surfaces via `core/util/Errors.kt` + `ErrorInfo`.

## Manager APIs (final signatures — implement exactly)
```kotlin
// ai/ProviderManager.kt
class ProviderManager(secrets, json) {
    val providers: StateFlow<List<Provider>>
    fun upsert(p: Provider)            // persists; apiKey NOT stored here
    fun remove(id: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun apiKeyFor(p: Provider): String?                 // SecretStore lookup via apiKeyRef
    fun setApiKey(p: Provider, value: String?): Boolean // null/blank → delete key
    suspend fun testConnection(p: Provider, apiKey: String?): Result<String>  // success msg like "OK: 12 models"
}

// ai/ModelManager.kt
class ModelManager(json) {
    val models: StateFlow<List<AIModel>>
    val defaultModelId: StateFlow<String?>
    fun upsert(m: AIModel); fun remove(id: String)
    fun duplicate(id: String): Result<AIModel>
    fun setDefault(id: String); fun setEnabled(id: String, enabled: Boolean)
    fun defaultModel(): AIModel?
    fun byProvider(providerId: String): List<AIModel>
    suspend fun testModel(m: AIModel): Result<String>   // tiny real request via ChatClients
}

// ai/OllamaManager.kt  (spec §11: local AND remote are first-class)
class OllamaManager(json, secrets) {
    val servers: StateFlow<List<OllamaServer>>
    val modelCache: StateFlow<Map<String, List<OllamaModel>>>   // serverId → models
    val busy: StateFlow<Boolean>
    fun upsertServer(s: OllamaServer); fun removeServer(id: String)
    suspend fun testServer(s: OllamaServer): Result<String>     // GET /api/tags
    suspend fun refreshModels(s: OllamaServer): Result<List<OllamaModel>>
    suspend fun pullModel(s: OllamaServer, name: String, onProgress: (String) -> Unit): Result<Unit> // POST /api/pull stream
    suspend fun deleteModel(s: OllamaServer, name: String): Result<Unit>   // DELETE /api/delete
    suspend fun runModel(s: OllamaServer, name: String): Result<String>    // POST /api/generate empty prompt keep_alive
}

// ai/ChatService.kt
class ChatService(json, providers, models, opencode, settings) {
    val conversations: StateFlow<List<Conversation>>
    val activeId: StateFlow<String?>
    val streaming: StateFlow<Boolean>
    val lastError: StateFlow<ErrorInfo?>
    fun newConversation(title: String = "New chat", modelId: String? = null, backend: ChatBackend = ChatBackend.DIRECT): Conversation
    fun open(id: String); fun delete(id: String); fun rename(id: String, title: String)
    fun setActiveModel(conversationId: String, modelId: String?)   // switching model must not lose messages
    fun setBackend(conversationId: String, backend: ChatBackend)
    suspend fun send(text: String)      // streams into the active conversation
    fun cancel()                        // cancel streaming job
    suspend fun regenerate()            // drop last assistant msg, resend last user msg
    suspend fun retry()                 // resend last failed exchange
    fun appendToolBlock(conversationId: String, block: ToolBlock) // e.g. "run in workspace" feature
}
// send() flow: resolve model (conversation.modelId ?: ModelManager.defaultModel()).
//   DIRECT backend → ChatClients.forProvider(type).stream(...), deltas appended live to a mutable assistant message.
//   OPENCODE backend → AppGraph.opencode.run(prompt, workspace = AppGraph.workspaces.current(), model, onTool = ::appendToolBlock)

// ai/providers/ChatClient.kt
interface ChatClient {
    suspend fun stream(req: ChatRequest, onDelta: (String) -> Unit): Result<String>
    suspend fun testConnection(p: Provider, apiKey: String?): Result<String>
}
data class ChatRequest(model: AIModel, provider: Provider, apiKey: String,
    messages: List<ChatMessage>, maxTokens: Int, temperature: Double)
object ChatClients { fun forProvider(type: ProviderType): ChatClient }
// Implementations: OpenAICompatClient (OPENAI/OPENROUTER/CUSTOM_OPENAI, POST {base}/chat/completions, SSE),
// AnthropicClient (POST {base}/v1/messages, headers x-api-key + anthropic-version: 2023-06-01, SSE events),
// GeminiClient (POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse&key=..., SSE).
// SSE parse: read okio source lines; "data: " prefix; [DONE] sentinel (OpenAI-style). Robust to blank lines.
// Errors → Errors.network / Errors.providerAuth (no raw key in messages!).

// ai/opencode/OpenCodeInstaller.kt
class OpenCodeInstaller(ubuntu: UbuntuRuntime, onLog: (String) -> Unit) {
    suspend fun install(): Result<Unit>    // see "OpenCode install chain" below
    suspend fun version(): Result<String>  // opencode --version
}

// ai/opencode/OpenCodeController.kt
class OpenCodeController(ubuntu, providers, models, json, settings) {
    val status: StateFlow<OpenCodeStatus>
    val log: StateFlow<List<String>>                       // tail ~200 lines for UI
    suspend fun install(): Result<Unit>; suspend fun reinstall(): Result<Unit>
    suspend fun checkUpdate(): Result<String>; suspend fun update(): Result<Unit>
    suspend fun run(prompt: String, workspace: Workspace?, model: AIModel?, onTool: (ToolBlock) -> Unit): Result<String>
    suspend fun syncConfig(model: AIModel, provider: Provider, apiKey: String): Result<Unit>  // §29
}
// syncConfig: write to <rootfs>/root/.config/opencode/opencode.json (official schema):
// {"$schema":"https://opencode.ai/config.json","model":"<provId>/<modelName>",
//  "provider":{"<provId>":{"npm":"@ai-sdk/openai-compatible"|"@ai-sdk/anthropic"|"@ai-sdk/google",
//    "name":<name>,"options":{"baseURL":<base>,"apiKey":<key>},"models":{"<modelName>":{"name":<displayName>}}}}}
// npm mapping: OPENAI/OPENROUTER/CUSTOM_OPENAI/OLLAMA→@ai-sdk/openai-compatible, ANTHROPIC→@ai-sdk/anthropic, GEMINI→@ai-sdk/google.

// ubuntu/RootfsCatalog.kt — pinned URLs + sha256 (REAL, verified values below)
data class RootfsVariant(val codename: String, val url: String, val sha256: String)
object RootfsCatalog {
    val variants: List<RootfsVariant>   // jammy+noble × arm64/armhf/amd64
    fun forAbi(abi: String): RootfsVariant
    fun ubuntuArchForAbi(abi: String): String  // arm64-v8a→arm64, armeabi-v7a→armhf, x86_64→amd64
}
// jammy (22.04.5): arm64 075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f
//                 armhf fd77cb0659326b75c08ce06b6b8649d2e13ef9a704a8e9212fec32cb97d42add
//                 amd64 242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a
// noble (24.04.5): arm64 a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2
//                 armhf 4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb
//                 amd64 e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86
// URL: https://cdimage.ubuntu.com/ubuntu-base/releases/{22.04|24.04}/release/ubuntu-base-{ver}-base-{arch}.tar.gz

// ubuntu/UbuntuRuntime.kt
class UbuntuRuntime(context, json, settings, proot: ProotRunner, installer: UbuntuInstaller) {
    val status: StateFlow<UbuntuStatus>
    val log: StateFlow<List<String>>                  // tail ~300 lines (installer + runtime)
    suspend fun install(): Result<Unit>               // §3–4 full chain, progress via status/log
    suspend fun repair(): Result<Unit>                // re-verify/re-extract, keep /root home data
    suspend fun update(): Result<Unit>                // apt update+upgrade + tools re-check
    suspend fun reset(): Result<Unit>                 // delete rootfs (confirm in UI)
    suspend fun ensureReady(): Result<Unit>           // fast check; error = Errors.ubuntuNotReady()
    fun isReady(): Boolean
    suspend fun exec(cmd: String, cwd: String = "/root", env: Map<String,String> = emptyMap(), timeoutMs: Long = 180_000): Result<String>
    suspend fun execStream(cmd: String, cwd: String = "/root", env: Map<String,String> = emptyMap(), onLine: (String) -> Unit): Result<Int>
    fun sessionCommand(cwd: String, cmd: List<String>, env: Map<String,String>): SessionSpec
    fun abi(): String                                  // Build.SUPPORTED_ABIS[0]
}
data class SessionSpec(val argv: List<String>, val cwd: String, val env: Map<String, String>)
// proot argv (real): [prootPath, "--kill-on-exit", "-0", "-R", rootfsDir, "-w", cwd,
//   "-b","/dev","-b","/proc","-b","/sys", "-b", appFilesBind? (no), then cmd...]
// Env for sessions/exec: HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin
//   TERM=xterm-256color LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive + caller extras.
// proot binary: <filesDir>/ubuntu/bin/proot downloaded per-ABI from (REAL hashes):
//   aarch64: https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static  sha256 fa10b1a7818c2f5b1dcb5834450570c368c9ecf66d31521509621b95c4538a45
//   arm:     https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-arm-static      sha256 bf186a37c7a19621e5bf3cfdf6bce54bfa2e220f91eb7196318e699ac174cc69
//   x86_64:  https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-x86_64-static   sha256 d1eb20cb201e6df08d707023efb000623ff7c10d6574839d7bb42d0adba6b4da

// ubuntu/UbuntuInstaller.kt
class UbuntuInstaller(context, runtime refs..., onEvent: (UbuntuState, String, Int) -> Unit) {
    suspend fun downloadAndVerify(url: String, sha256: String, onProgress: (Long, Long) -> Unit): Result<File> // OkHttp stream → filesDir/ubuntu/cache/
    suspend fun extract(tarGz: File, targetDir: File): Result<Unit>  // commons-compress; reject "../"/absolute entries (§23); restore modes + symlinks (android.system.Os)
    suspend fun configure(rootfs: File, abi: String): Result<Unit>   // /etc/resolv.conf (8.8.8.8,1.1.1.1), apt sources per-arch
                                                                     // (armhf/arm64→ports.ubuntu.com/ubuntu-ports, amd64→archive.ubuntu.com; codename jammy/noble main universe), /etc/hosts
    suspend fun installTools(onLog: (String)->Unit): Result<Unit>    // via runtime.exec: apt-get update; apt-get install -y curl ca-certificates xz-utils git python3 python3-pip wget
    suspend fun installNode(onLog: (String)->Unit): Result<Unit>     // node v20 tarball from nodejs.org per-arch, verify against nodejs.org SHASUMS256.txt fetched at runtime, extract to /opt/node, symlink /usr/local/bin
    fun smokeTest(): Result<Unit>                                    // bash --version && git --version && node -v && python3 -V via runtime.exec
}

// ubuntu/UbuntuFileSystem.kt
object UbuntuFileSystem {
    fun rootfsDir(context: Context): File        // <filesDir>/ubuntu/rootfs
    fun prootBin(context: Context): File         // <filesDir>/ubuntu/bin/proot
    fun cacheDir(context: Context): File
    fun workspaceRoot(rootfs: File): File        // <rootfs>/root/workspaces
    fun safeResolve(root: File, rel: String): Result<File>  // anti path-traversal (§23)
}

// ubuntu/UbuntuProcessManager.kt
class UbuntuProcessManager(terminal, ubuntu, json) {
    val processes: StateFlow<List<ProcessInfo>>
    suspend fun start(workspace: Workspace?, command: String, title: String): Result<ProcessInfo>
    fun stop(pid: Int); fun restart(pid: Int)
    fun attach(pid: Int): TerminalSession?
    fun outputOf(pid: Int): String
    fun markRestoredAfterAppRestart()            // RUNNING → EXITED, note "terminated when app was killed" (honest §25)
}

// terminal/Pty.kt (JNI bridge — matches app/src/main/cpp/pty.c, already written)
object Pty {
    init { System.loadLibrary("pty") }
    external fun create(argv: Array<String>, cwd: String, env: Array<String>, rows: Int, cols: Int): Long // (pid<<32)|fd
    external fun write(fd: Int, data: ByteArray, len: Int): Int
    external fun read(fd: Int, buf: ByteArray, len: Int): Int   // >0 bytes, 0 EOF, -1 err, -2 EINTR-retry
    external fun resize(fd: Int, rows: Int, cols: Int): Int
    external fun kill(pid: Int, sig: Int): Int
    external fun wait(pid: Int, block: Boolean): Int            // exit status, -1 running, -2 unknown
    external fun close(fd: Int)
}

// terminal/TerminalManager.kt
class TerminalManager(ubuntu: UbuntuRuntime, context: Context) {
    val sessions: StateFlow<List<TerminalSession>>
    fun create(title: String, cwd: String, command: List<String>? = null, env: Map<String,String> = emptyMap()): TerminalSession
    // command==null → sessionCommand(cwd, ["/bin/bash","-l"], env); starts RuntimeForegroundService keep-alive (§14)
    fun get(id: String): TerminalSession?
    fun close(id: String); fun closeAll()
}
class TerminalSession(val id: String, val title: String) {
    val pid: Int
    val alive: StateFlow<Boolean>
    val exitCode: StateFlow<Int?>
    val revision: StateFlow<Long>          // bumped on every buffer change (UI observes this, NOT raw bytes)
    fun buffer(): TerminalBuffer            // synchronized access; UI calls lines(currentView) on revision change
    fun scrollbackLines(): List<String>
    fun write(text: String)                 // UTF-8 bytes → pty
    fun resize(cols: Int, rows: Int)
    fun kill(force: Boolean = true)
    fun outputText(): String                // plain text dump (for ProcessInfo.outputOf)
}

// terminal/TerminalBuffer.kt — REAL VT parser (pure Kotlin, unit-testable)
class TerminalBuffer(initialCols: Int = 80, initialRows: Int = 24, scrollbackLimit: Int = 2000) {
    fun feed(bytes: ByteArray)              // UTF-8 decode + ANSI/CSI parse: CUP/CUU/CUD/CUF/CUB, ED, EL, SGR(0,1,3,7,30-37,40-47,90-97,100-107,38;5;n,48;5;n), \r \n \b \t BEL, scrolling, wrap
    fun resize(cols: Int, rows: Int)
    fun rows(): Int; fun cols(): Int; fun cursorRow(): Int; fun cursorCol(): Int
    fun lineText(row: Int): String
    fun lineAttrs(row: Int): IntArray       // packed fg|bg|flags per cell (fg low 9 bits: 0=default,1..16 palette,256+n=256-color; bg next 9; flags: bit18 bold, bit19 italic, bit20 reverse)
    fun scrollbackCount(): Int
    fun scrollbackLine(index: Int): String
}

// workspace/WorkspaceManager.kt
class WorkspaceManager(ubuntu, json) {
    val workspaces: StateFlow<List<Workspace>>
    val currentId: StateFlow<String?>
    fun create(name: String, providerId: String?, modelId: String?, envVars: Map<String,String>): Result<Workspace>
    // creates dir under /root/workspaces/<name> inside rootfs via mkdir on mapped host path
    fun update(w: Workspace); fun delete(id: String)
    fun open(id: String); fun current(): Workspace?
}

// workspace/FileManagerService.kt
enum class FileDomain { APP_DATA, UBUNTU_ROOTFS, WORKSPACE, SHARED }
class FileEntry(val file: java.io.File?, val name: String, val isDir: Boolean,
                val size: Long, val lastModified: Long, val domain: FileDomain, val relativePath: String)
class FileManagerService(context, ubuntuFs) {
    fun rootFor(domain: FileDomain): java.io.File?           // SHARED → null (SAF)
    fun list(domain: FileDomain, relativePath: String): Result<List<FileEntry>>
    fun createFile(domain: FileDomain, relDir: String, name: String): Result<FileEntry>
    fun createDirectory(domain: FileDomain, relDir: String, name: String): Result<FileEntry>
    fun rename(domain: FileDomain, relPath: String, newName: String): Result<FileEntry>
    fun delete(domain: FileDomain, relPath: String): Result<Unit>
    fun copy(srcD: FileDomain, srcRel: String, dstD: FileDomain, dstRel: String): Result<FileEntry>
    fun move(srcD: FileDomain, srcRel: String, dstD: FileDomain, dstRel: String): Result<FileEntry>
    fun readText(target: java.io.File): Result<String>; fun writeText(target: java.io.File, content: String): Result<Unit>
    fun importFromUri(domain: FileDomain, relDir: String, uri: android.net.Uri): Result<FileEntry>
    fun exportToUri(file: java.io.File, uri: android.net.Uri): Result<Unit>   // contentResolver copy
}

// bg/RuntimeForegroundService.kt
object RuntimeServiceController {
    fun start(context: Context)   // startForegroundService, notif channel "runtime" (§14)
    fun stop(context: Context)    // stop only when no live sessions
}
class RuntimeForegroundService : Service  // minimal: startForeground with persistent notif

// core/storage/SettingsStore.kt (already written) — AppSettings fields incl. terminal.* and prootUrlOverride/rootfsUrlOverride.
```

## File ownership (ONLY write these)

### Agent 2-a — AI layer (package `com.openchat.android.ai.*`)
- ai/providers/ChatClient.kt (+ ChatRequest, ChatClients factory)
- ai/providers/OpenAICompatClient.kt
- ai/providers/AnthropicClient.kt
- ai/providers/GeminiClient.kt
- ai/providers/OllamaHttpClient.kt (shared low-level Ollama HTTP calls; may be object)
- ai/OllamaManager.kt
- ai/ProviderManager.kt
- ai/ModelManager.kt
- ai/ChatService.kt
- ai/opencode/OpenCodeConfigSync.kt (pure JSON builder → unit test)
- ai/opencode/OpenCodeInstaller.kt
- ai/opencode/OpenCodeController.kt
- tests: app/src/test/java/com/openchat/android/ai/OpenCodeConfigSyncTest.kt, SseChunkParseTest.kt (test any pure helper you extract)

### Agent 2-b — Runtime layer (package `com.openchat.android.{ubuntu,terminal,workspace,bg}.*`)
- terminal/Pty.kt, terminal/TerminalBuffer.kt, terminal/TerminalSession.kt, terminal/TerminalManager.kt
- ubuntu/RootfsCatalog.kt, ubuntu/UbuntuInstaller.kt, ubuntu/ProotRunner.kt (proot download/verify + SessionSpec builder), ubuntu/UbuntuRuntime.kt, ubuntu/UbuntuFileSystem.kt, ubuntu/UbuntuProcessManager.kt
- workspace/WorkspaceManager.kt, workspace/FileManagerService.kt
- bg/RuntimeForegroundService.kt (incl. RuntimeServiceController)
- tests: app/src/test/java/com/openchat/android/terminal/TerminalBufferTest.kt, ubuntu/RootfsCatalogTest.kt, ubuntu/UbuntuFileSystemPathTest.kt, workspace/WorkspaceJsonTest.kt

### Agent 2-c — UI layer (package `com.openchat.android.ui.*`)
- ui/components/Markdown.kt (headers, **bold**, `inline code`, ```fenced code with copy button```, lists, links plain-text)
- ui/components/Common.kt (ErrorCard(ErrorInfo, onRetry/onRepair/onLogs), LabeledTextField, ConfirmDialog, SectionHeader, StatusPill, KeyValueEditor)
- ui/components/ModelPicker.kt (dropdown grouped by provider; switching must not clear conversation)
- ui/chat/ChatScreen.kt (+ helpers in same file: message bubbles, tool blocks w/ copy, input bar, cancel/retry/regenerate, backend toggle, conversation drawer)
- ui/terminalui/TerminalScreen.kt (+ TerminalCanvas composable: Canvas rendering of TerminalBuffer via revision StateFlow; extra-keys row ESC/CTRL/TAB/↑↓←→/|; paste from clipboard; copy; font size from settings; reconnect on session death)
- ui/files/FilesScreen.kt (domain switcher APP_DATA/UBUNTU_ROOTFS/WORKSPACE/SHARED, list+ops, SAF import/export via rememberLauncherForActivityResult, open text files in editor)
- ui/files/EditorScreen.kt (read text, edit, save, Save&Close)
- ui/settings/SettingsHomeScreen.kt (all §27 entries + Ubuntu status card + OpenCode status card + Processes entry)
- ui/settings/ProvidersScreen.kt, ui/settings/ProviderEditScreen.kt (Name/Type/BaseURL/API key(masked!)/headers/Enabled + Test Connection ✓/✕)
- ui/settings/ModelsScreen.kt, ui/settings/ModelEditScreen.kt (all §9 fields + test/duplicate/set-default/enable)
- ui/settings/OllamaScreen.kt (servers list + add, test ✓/✕, models list/pull(progress)/delete/run/refresh)
- ui/settings/OpenCodeScreen.kt (status, Install/Update/Reinstall, log tail, config sync info)
- ui/settings/UbuntuScreen.kt (status per §3–4: version/arch/rootfs path/state/last update; Install/Repair/Update/Reset/Open Terminal; progress + log tail; error card with Repair/Reset actions)
- ui/settings/TerminalSettingsScreen.kt (font size/family/cursor blink/scrollback/theme/show extra keys)
- ui/settings/WorkspaceScreen.kt (CRUD, set current, per-workspace provider/model/env vars)
- ui/settings/ProcessManagerScreen.kt (list RUNNING/EXITED, Start command (uses current workspace), Stop/Restart/View output/Attach to terminal)
- ui/settings/SecurityScreen.kt (keystore-backed secrets list w/ masked values ••••, delete, note about secure storage; storage locations audit info)
- ui/settings/StorageScreen.kt (sizes per domain computed lazily on button press; clear cache)
- ui/settings/AboutScreen.kt (version from BuildConfig, ABI, spec summary)
- DO NOT edit AppNav.kt/theme/MainActivity — the composables above must exist with EXACTLY these names/signatures:
  ChatScreen(); TerminalScreen(); FilesScreen(); SettingsHomeScreen(nav: NavHostController); ProvidersScreen(nav); ProviderEditScreen(nav, providerId: String); ModelsScreen(nav); ModelEditScreen(nav, modelId: String); OllamaScreen(nav); OpenCodeScreen(nav); UbuntuScreen(nav); TerminalSettingsScreen(nav); WorkspaceScreen(nav); ProcessManagerScreen(nav); SecurityScreen(nav); StorageScreen(nav); AboutScreen(nav); EditorScreen(nav, path: String?, domain: String, isNew: Boolean)

## Conventions
- Imports: Material3 `androidx.compose.material3.*`, runtime `androidx.compose.runtime.*`, `@OptIn(ExperimentalMaterial3Api::class)` where needed.
- All manager state changes via MutableStateFlow on the manager's own scope; UI uses collectAsState().
- Persistence file names in JsonStore: "providers.json", "models.json", "workspaces.json", "ollama.json", "opencode_status.json", "ubuntu_status.json", "processes.json", conversations/<id>.json + "conversations/index.json".
- Use java.util.UUID.randomUUID().toString() for ids. Use Redact before logging anything key-ish.
- Every Result failure must map to a specific ErrorInfo (never a bare exception message for the user).
- Kotlin style: 4 spaces, explicit types on public APIs, KDoc on classes.

## Worklog protocol
Before work: read /home/z/my-project/worklog.md. After work: APPEND a section:
```
---
Task ID: <2-a|2-b|2-c>
Agent: <name>
Task: <one line>
Work Log:
- <steps>
Stage Summary:
- <files written, public API notes, integration notes / needs from integrator>
```
