package com.openchat.android.terminal

/**
 * JNI bridge to the real PTY implementation in `app/src/main/cpp/pty.c`.
 *
 * The native symbols are `Java_com_openchat_android_terminal_Pty_<name>`:
 * create/write/read/resize/kill/wait/close (+ lastError for honest diagnostics).
 * `create` returns a packed `(pid << 32) | masterFd` Long, or -1 on failure —
 * use [createHandle] to unpack it into a [PtyHandle].
 *
 * IMPORTANT: loading this class triggers `System.loadLibrary("pty")`, so it is
 * only usable on a device/emulator with libpty.so (never from JVM unit tests).
 */
object Pty {

    init {
        System.loadLibrary("pty")
    }

    /**
     * Forks a child attached to a new pseudo-terminal and execs argv[0].
     * @return packed `(pid << 32) | masterFd`, or -1 on failure.
     */
    external fun create(argv: Array<String>, cwd: String, env: Array<String>, rows: Int, cols: Int): Long

    /** Writes up to len bytes of data to the pty master; returns bytes written or -1. */
    external fun write(fd: Int, data: ByteArray, len: Int): Int

    /**
     * Blocking read from the pty master.
     * @return >0 number of bytes, 0 = EOF (child side closed), -1 = error (e.g. EIO
     *   when the child exits on Android), -2 = EINTR (safe to retry).
     */
    external fun read(fd: Int, buf: ByteArray, len: Int): Int

    /** Resizes the terminal via TIOCSWINSZ; returns 0 on success, -1 on error. */
    external fun resize(fd: Int, rows: Int, cols: Int): Int

    /** Sends a signal to the child pid; never signals init. Returns 0 or -1. */
    external fun kill(pid: Int, sig: Int): Int

    /**
     * waitpid wrapper.
     * @return exit status (0..255), -1 when [block] is false and the child is still
     *   running, -2 when the pid is unknown / already reaped.
     */
    external fun wait(pid: Int, block: Boolean): Int

    /** Closes the pty master fd. */
    external fun close(fd: Int)

    /** strerror(errno) of the last failed native call, or null. */
    external fun lastError(): String?

    /**
     * Creates a process on a pseudo-terminal and unpacks the native result.
     * @return [PtyHandle] with pid + master fd, or null when forkpty/execve failed
     *   (see [lastError] for the native reason).
     */
    fun createHandle(
        argv: Array<String>,
        cwd: String,
        env: Array<String>,
        rows: Int,
        cols: Int,
    ): PtyHandle? {
        val packed = runCatching { create(argv, cwd, env, rows, cols) }.getOrNull() ?: return null
        if (packed < 0L) return null
        val pid = (packed shr 32).toInt()
        val fd = (packed and 0xffffffffL).toInt()
        if (pid <= 0 || fd < 0) return null
        return PtyHandle(pid, fd)
    }
}

/** Owned pair of child pid + pty master fd for one [TerminalSession]. */
data class PtyHandle(val pid: Int, val fd: Int)
