package com.openchat.android.ubuntu

import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.ProcessInfo
import com.openchat.android.core.model.ProcState
import com.openchat.android.core.model.Workspace
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.terminal.TerminalManager
import com.openchat.android.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks long-running processes started inside the Ubuntu userspace (spec §28).
 *
 * Every process is backed by a real [TerminalSession] PTY (`bash -lc <command>`),
 * so output capture, attach-to-terminal and stop/restart are all real — nothing
 * is polled from /proc or faked. The list is persisted to `processes.json`;
 * after an app restart previously-RUNNING entries are honestly marked
 * EXITED with a note (Android killed the whole app process — spec §25).
 */
class UbuntuProcessManager(
    private val terminal: TerminalManager,
    private val ubuntu: UbuntuRuntime,
    private val json: JsonStore,
) {

    /**
     * Workspace lookup for [restart] — wired by the integrator to
     * AppGraph.workspaces (avoids a construction cycle in the object graph).
     */
    var workspaceProvider: (String?) -> Workspace? = { null }

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _processes: MutableStateFlow<List<ProcessInfo>> = MutableStateFlow(load())
    val processes: StateFlow<List<ProcessInfo>> = _processes

    /** pid → captured plain-text output at exit (in-memory, honest snapshots). */
    private val outputs = java.util.concurrent.ConcurrentHashMap<Int, String>()

    init {
        markRestoredAfterAppRestart()
    }

    /**
     * Starts `command` inside the rootfs via a real PTY session.
     * The cwd is the workspace path (or /root/workspaces); the workspace env
     * vars are merged into the session environment (caller wins).
     */
    suspend fun start(workspace: Workspace?, command: String, title: String): Result<ProcessInfo> {
        ubuntu.ensureReady().getOrElse { return Result.failure(it) }
        val trimmedCommand = command.trim()
        if (trimmedCommand.isEmpty()) {
            return Result.failure(
                ErrorInfoException(
                    ErrorInfo(
                        title = "No command given",
                        detail = "Type the command to run, e.g. \"python3 train.py\".",
                        suggestions = listOf("Enter a shell command", "Use the Terminal screen for interactive shells"),
                    ),
                ),
            )
        }
        val cwd = workspace?.path?.takeIf { it.isNotBlank() } ?: DEFAULT_CWD
        val session: TerminalSession = terminal.create(
            title = "proc: $title",
            cwd = cwd,
            command = listOf("/bin/bash", "-lc", trimmedCommand),
            env = workspace?.envVars ?: emptyMap(),
        )
        if (!session.alive.value) {
            return Result.failure(
                ErrorInfoException(
                    ErrorInfo(
                        title = "Failed to start",
                        detail = session.outputText().take(400).ifBlank {
                            "the process exited immediately without output"
                        },
                        causes = listOf("command not found or crashed immediately"),
                        suggestions = listOf("check the command spelling", "check Ubuntu is installed"),
                        retryable = true,
                    ),
                ),
            )
        }
        val info = ProcessInfo(
            pid = session.pid,
            sessionId = session.id,
            title = title,
            command = trimmedCommand,
            cwd = cwd,
            workspaceId = workspace?.id,
        )
        _processes.value = _processes.value + info
        persist()
        watch(session, info)
        return Result.success(info)
    }

    /** Kills the process behind [pid] and marks it KILLED (honest, no auto-restart). */
    fun stop(pid: Int) {
        val info = find(pid) ?: return
        terminal.get(info.sessionId)?.kill(force = true)
        info.state = ProcState.KILLED
        info.note = "stopped by the user"
        snapshotOutput(info)
        persist()
    }

    /**
     * Restarts [pid]: kills the old instance (SIGKILL — harmless when already
     * dead, and it must not overwrite the previous exit state), then starts the
     * same command in the same workspace. Runs asynchronously; when the new
     * instance is up the old list entry is replaced. Failures are recorded in
     * the old entry's note (never silently dropped).
     */
    fun restart(pid: Int) {
        val old = find(pid) ?: return
        scope.launch {
            val workspace = workspaceProvider(old.workspaceId)
            terminal.get(old.sessionId)?.kill(force = true)
            start(workspace, old.command, old.title).fold(
                onSuccess = {
                    _processes.value = _processes.value.filterNot { it.pid == pid }
                    persist()
                },
                onFailure = { err ->
                    old.state = ProcState.FAILED
                    old.note = "restart failed: ${infoOf(err).title} — ${infoOf(err).detail}"
                    persist()
                },
            )
        }
    }

    /** The live terminal session of a running process (for attach-to-terminal). */
    fun attach(pid: Int): TerminalSession? {
        val info = find(pid) ?: return null
        return terminal.get(info.sessionId)
    }

    /** Captured output of a process: the exit snapshot, the live screen, or "". */
    fun outputOf(pid: Int): String {
        outputs[pid]?.let { return it }
        return find(pid)?.let { info -> terminal.get(info.sessionId)?.outputText() } ?: ""
    }

    /**
     * Called at construction: entries persisted as RUNNING cannot outlive the
     * app process — mark them EXITED with the honest note (spec §25).
     */
    fun markRestoredAfterAppRestart() {
        var changed = false
        for (info in _processes.value) {
            if (info.state == ProcState.RUNNING) {
                info.state = ProcState.EXITED
                info.exitCode = null
                info.note = "terminated when the app process was killed (Android)"
                changed = true
            }
        }
        if (changed) persist()
    }

    // ------------------------------------------------------------------ internals

    /** Marks the entry EXITED/FAILED once and snapshots the final screen output. */
    private fun watch(session: TerminalSession, info: ProcessInfo) {
        scope.launch {
            val code = session.exitCode.first { it != null }
            // Re-read the list entry (stop/restart may have replaced the instance).
            val current = find(info.pid) ?: return@launch
            if (current.sessionId != session.id) return@launch // restarted meanwhile
            if (current.state == ProcState.RUNNING) {
                current.state = if (code == 0) ProcState.EXITED else ProcState.FAILED
                current.exitCode = code
            }
            outputs[current.pid] = session.outputText()
            persist()
        }
    }

    private fun snapshotOutput(info: ProcessInfo) {
        terminal.get(info.sessionId)?.let { session ->
            outputs[info.pid] = session.outputText()
        } ?: run { outputs[info.pid] = outputs[info.pid] ?: "" }
    }

    private fun find(pid: Int): ProcessInfo? = _processes.value.firstOrNull { it.pid == pid }

    /** Unwraps the structured error from a failed Result (fallback: generic info). */
    private fun infoOf(t: Throwable): com.openchat.android.core.model.ErrorInfo =
        (t as? ErrorInfoException)?.info
            ?: com.openchat.android.core.model.ErrorInfo(
                title = "Operation failed",
                detail = t.message ?: t.javaClass.simpleName,
                suggestions = listOf("Retry", "Check the Ubuntu status in Settings → Ubuntu"),
            )

    private fun persist() {
        val arr = JSONArray()
        _processes.value.forEach { arr.put(it.toJson()) }
        json.writeText(FILE, JSONObject().put("items", arr).toString())
    }

    private fun load(): List<ProcessInfo> {
        val raw = json.readText(FILE) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { ProcessInfo.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val FILE = "processes.json"
        const val DEFAULT_CWD = "/root/workspaces"
    }
}
