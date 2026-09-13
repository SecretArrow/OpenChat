package com.openchat.android.terminal

import android.content.Context
import com.openchat.android.bg.RuntimeServiceController
import com.openchat.android.ubuntu.SessionSpec
import com.openchat.android.ubuntu.UbuntuRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns all terminal sessions (spec §15, §17). The UI talks to sessions only
 * through this manager via AppGraph — never spawns processes itself.
 *
 * On the first live session the runtime foreground service (§14) is started so
 * the OS does not kill the app while a shell/long-running process is attached;
 * when the last live session dies, the service is stopped again. A session is
 * removed from [sessions] as soon as its process exits (its [TerminalSession]
 * instance stays usable for anyone holding a reference — e.g. the process
 * manager, which snapshots the output before the removal takes effect).
 *
 * If Ubuntu is not ready, [create] returns an honest dead session carrying the
 * reason in its buffer (exitCode 127) — no fake terminals (spec §32).
 */
class TerminalManager(
    private val ubuntu: UbuntuRuntime,
    private val context: Context,
) {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _sessions: MutableStateFlow<List<TerminalSession>> = MutableStateFlow(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions

    /**
     * Spawns a new terminal session.
     *
     * @param command argv to exec inside the Ubuntu rootfs; null → interactive login shell.
     * @param cwd working directory inside the rootfs (e.g. "/root/workspaces/demo").
     * @param env extra environment variables merged over the base session env (caller wins).
     */
    fun create(
        title: String,
        cwd: String,
        command: List<String>? = null,
        env: Map<String, String> = emptyMap(),
    ): TerminalSession {
        val id = UUID.randomUUID().toString()
        val session: TerminalSession = if (!ubuntu.isReady()) {
            TerminalSession.dead(id, title, NOT_READY_BANNER) { onClosed(id) }
        } else {
            val spec: SessionSpec = ubuntu.sessionCommand(cwd, command ?: DEFAULT_SHELL, env)
            val handle = Pty.createHandle(
                argv = spec.argv.toTypedArray(),
                cwd = spec.cwd,
                env = spec.env.map { "${it.key}=${it.value}" }.toTypedArray(),
                rows = DEFAULT_ROWS,
                cols = DEFAULT_COLS,
            )
            if (handle == null) {
                TerminalSession.dead(id, title, EXEC_FAIL_BANNER) { onClosed(id) }
            } else {
                TerminalSession(id, title, handle, TerminalBuffer(DEFAULT_COLS, DEFAULT_ROWS)) { onClosed(id) }
            }
        }
        synchronized(lock) { _sessions.value = _sessions.value + session }
        if (session.alive.value) {
            RuntimeServiceController.start(context)
        }
        return session
    }

    /** Session lookup by id. */
    fun get(id: String): TerminalSession? =
        synchronized(lock) { _sessions.value.firstOrNull { it.id == id } }

    /** Kills the session (SIGKILL), removes it and stops the keep-alive service if none live remain. */
    fun close(id: String) {
        val session = synchronized(lock) { _sessions.value.firstOrNull { it.id == id } } ?: return
        session.kill(force = true)
        synchronized(lock) { _sessions.value = _sessions.value.filterNot { it.id == id } }
        stopServiceIfIdle()
    }

    /** Kills and removes every session (also called when the app tears down). */
    fun closeAll() {
        val all = synchronized(lock) {
            val current = _sessions.value
            _sessions.value = emptyList()
            current
        }
        all.forEach { it.kill(force = true) }
        RuntimeServiceController.stop(context)
    }

    /** Called from every session's close callback (reader thread or dead factory). */
    private fun onClosed(id: String) {
        scope.launch {
            synchronized(lock) { _sessions.value = _sessions.value.filterNot { it.id == id } }
            stopServiceIfIdle()
        }
    }

    private fun stopServiceIfIdle() {
        val anyLive = synchronized(lock) { _sessions.value.any { it.alive.value } }
        if (!anyLive) RuntimeServiceController.stop(context)
    }

    private companion object {
        val DEFAULT_SHELL: List<String> = listOf("/bin/bash", "-l")
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val NOT_READY_BANNER =
            "[openchat] Ubuntu userspace is not ready. Install it in Settings → Ubuntu.\r\n"
        const val EXEC_FAIL_BANNER =
            "[openchat] Failed to start process (pty error)\r\n" +
                "Check Settings → Ubuntu → Repair, then try again.\r\n"
    }
}
