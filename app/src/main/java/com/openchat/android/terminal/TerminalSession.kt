package com.openchat.android.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One live (or dead) terminal attached to a real PTY (spec §15).
 *
 * Created exclusively by [TerminalManager] — spawning logic lives there. The
 * daemon reader thread loops on [Pty.read], feeds the [TerminalBuffer] and
 * bumps [revision] (throttled to ~50 ms) so the UI re-renders on buffer
 * changes instead of consuming raw bytes. Read results are handled as:
 * `>0` → feed, `0` → EOF, `-2` (EINTR) → immediate retry, `-1` → short sleep
 * and retry (up to ~100 consecutive errors before giving up). After EOF the
 * thread polls [Pty.wait] every 300 ms for up to 10 s, closes the master fd,
 * flips [alive]/[exitCode] and finally invokes [onClose] exactly once.
 *
 * An unknown/reaped-away exit status is reported honestly as `-1` (never 0).
 *
 * UI observes: [alive], [exitCode], [revision]; content is read through
 * [buffer]/[scrollbackLines]/[outputText] — never raw fd access.
 */
class TerminalSession(
    val id: String,
    val title: String,
    handle: PtyHandle?,
    buffer: TerminalBuffer,
    private val onClose: () -> Unit = {},
) {

    private val ptyHandle: PtyHandle? = handle
    private val buf: TerminalBuffer = buffer
    private val fd: Int = handle?.fd ?: -1

    /** OS pid of the child process, or -1 for a session that never started. */
    val pid: Int = handle?.pid ?: -1

    private val _alive: MutableStateFlow<Boolean> = MutableStateFlow(handle != null)
    val alive: StateFlow<Boolean> = _alive

    private val _exitCode: MutableStateFlow<Int?> = MutableStateFlow(if (handle == null) 127 else null)
    val exitCode: StateFlow<Int?> = _exitCode

    /** Bumped whenever the buffer changed; UI re-reads lines on each bump. */
    private val _revision: MutableStateFlow<Long> = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        if (handle != null) {
            Thread({ readerLoop(handle) }, "openchat-term-$id").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** The emulator buffer backing this session (synchronized access). */
    fun buffer(): TerminalBuffer = buf

    /** Full scrollback content, oldest first. */
    fun scrollbackLines(): List<String> = (0 until buf.scrollbackCount()).map { buf.scrollbackLine(it) }

    /** Writes text to the pty (UTF-8); handles partial writes; no-op when dead. */
    fun write(text: String) {
        if (fd < 0) return
        var data: ByteArray = text.toByteArray(Charsets.UTF_8)
        while (data.isNotEmpty()) {
            val n = Pty.write(fd, data, data.size)
            if (n <= 0) return // pty closed or transient failure — drop the rest
            if (n >= data.size) return
            data = data.copyOfRange(n, data.size)
        }
    }

    /** Resizes both the pty (winsize) and the emulator buffer. */
    fun resize(cols: Int, rows: Int) {
        if (fd >= 0) Pty.resize(fd, rows, cols)
        buf.resize(cols, rows)
        bump()
    }

    /**
     * Terminates the child. force=true sends SIGKILL immediately; otherwise
     * SIGTERM first and SIGKILL after 2 s if the process is still alive.
     */
    fun kill(force: Boolean = true) {
        val h = ptyHandle ?: return
        if (!_alive.value) return
        Pty.kill(h.pid, if (force) SIGKILL else SIGTERM)
        if (!force) {
            scope.launch {
                delay(2000)
                if (_alive.value) Pty.kill(h.pid, SIGKILL)
            }
        }
    }

    /** Plain-text dump of the visible screen (for ProcessInfo output capture). */
    fun outputText(): String = buf.dumpPlain()

    // ------------------------------------------------------------------ internals

    private fun readerLoop(h: PtyHandle) {
        val chunk = ByteArray(8192)
        var lastBump = System.currentTimeMillis()
        var dirty = false
        var consecutiveErrors = 0
        try {
            while (true) {
                val n = Pty.read(h.fd, chunk, chunk.size)
                when {
                    n > 0 -> {
                        buf.feed(chunk.copyOf(n))
                        consecutiveErrors = 0
                        dirty = true
                        val now = System.currentTimeMillis()
                        if (now - lastBump >= REVISION_THROTTLE_MS) {
                            bump()
                            lastBump = now
                            dirty = false
                        }
                    }
                    n == -2 -> Unit // EINTR — immediate retry
                    n == 0 -> break // EOF — child side of the pty closed
                    else -> {
                        // -1: transient error (EIO/EAGAIN after child exit, fd churn).
                        // Small sleep + retry; give up after MAX_CONSECUTIVE_ERRORS.
                        consecutiveErrors++
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) break
                        try {
                            Thread.sleep(READ_ERROR_RETRY_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // fd closed under us or JVM shutdown — fall through to the exit poll.
        }

        // Poll exit status every 300 ms after EOF (Pty.wait: >=0 status, -1 running, -2 unknown).
        var code: Int? = null
        var waitedMs = 0L
        while (code == null && waitedMs < EXIT_POLL_TIMEOUT_MS) {
            val w = Pty.wait(h.pid, false)
            if (w >= 0) {
                code = w
            } else if (w == -2) {
                break // reaped elsewhere / unknown pid — reported as -1 (honest)
            } else {
                try {
                    Thread.sleep(EXIT_POLL_INTERVAL_MS)
                    waitedMs += EXIT_POLL_INTERVAL_MS
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        runCatching { Pty.close(h.fd) }
        if (dirty) bump()
        _alive.value = false
        _exitCode.value = code ?: UNKNOWN_EXIT
        bump()
        try {
            onClose()
        } catch (_: Exception) {
            // A failing close-callback must never crash the reader thread.
        }
        scope.cancel()
    }

    private fun bump() {
        _revision.value = _revision.value + 1L
    }

    companion object {
        private const val SIGKILL = 9
        private const val SIGTERM = 15
        private const val REVISION_THROTTLE_MS = 50L
        private const val EXIT_POLL_INTERVAL_MS = 300L
        private const val EXIT_POLL_TIMEOUT_MS = 10_000L
        private const val MAX_CONSECUTIVE_ERRORS = 100
        private const val READ_ERROR_RETRY_MS = 20L

        /** Exit code reported when the real status could not be reaped (honest sentinel). */
        const val UNKNOWN_EXIT = -1

        /**
         * A session that never started (Ubuntu not ready / exec failure).
         * alive=false, exitCode=127, buffer pre-fed with the given banner so the
         * terminal screen shows an honest, actionable message (spec §32).
         */
        fun dead(id: String, title: String, message: String, onClose: () -> Unit = {}): TerminalSession {
            val buffer = TerminalBuffer()
            buffer.feed(message.toByteArray(Charsets.UTF_8))
            return TerminalSession(id, title, null, buffer, onClose)
        }
    }
}
