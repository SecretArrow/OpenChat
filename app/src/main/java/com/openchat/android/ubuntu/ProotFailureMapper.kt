package com.openchat.android.ubuntu

import com.openchat.android.core.model.ErrorInfo

/**
 * Maps proot's fatal exit (255) output signatures onto actionable [ErrorInfo].
 *
 * Pure Kotlin — JVM-tested ([ProotFailureMapperTest]). Extracted from
 * [UbuntuRuntime] so every 255 now produces an error that SHOWS the real
 * output tail (users on real devices reported a bare "error code 255" while
 * the actual reason was only visible in the log).
 */
object ProotFailureMapper {

    /** Lines kept for diagnosis when a command fails. */
    const val TAIL_LINES = 40

    /** How many tail lines are embedded into the surfaced error detail. */
    const val DETAIL_TAIL_LINES = 12

    /** Max characters per embedded tail line. */
    private const val LINE_CLIP = 200

    /**
     * Maps captured output [lines] onto an [ErrorInfo]. Never returns null —
     * an unmatched 255 falls back to an honest "proot exited with 255" that
     * embeds the output tail so nothing is hidden from the user.
     */
    fun map(lines: List<String>): ErrorInfo {
        fun firstLine(vararg needles: String): String? =
            lines.firstOrNull { line -> needles.any { line.contains(it, ignoreCase = true) } }

        firstLine("can't create temporary", "can't create glue", "PROOT_TMP_DIR")?.let { hit ->
            return ErrorInfo(
                title = "proot could not create its temporary files",
                detail = withTail(hit.trim(), lines),
                causes = listOf(
                    "proot writes glue/temp files to PROOT_TMP_DIR at startup",
                    "that directory was missing, not writable, or wiped by the system",
                ),
                suggestions = listOf(
                    "Run Repair — it recreates the app's writable directories",
                    "If it persists, Reset and Install again",
                    "Report this bug if it happens on every attempt",
                ),
                retryable = true,
            )
        }

        firstLine("No space left on device", "ENOSPC")?.let { hit ->
            return ErrorInfo(
                title = "The filesystem reported it is out of space",
                detail = withTail(hit.trim(), lines),
                causes = listOf(
                    "The volume holding the app's data (where the rootfs lives) filled up",
                    "Note: the phone's overall free space can still look large when a " +
                        "different partition is full — the report below shows the exact bytes seen by the app",
                    "Inode exhaustion can also produce this message with bytes still free",
                ),
                suggestions = listOf(
                    "Open Settings → Ubuntu → Run diagnostics — it prints the real free space and the failing path",
                    "Free up space, then Repair",
                    "Remove large files from /root inside Ubuntu",
                ),
                retryable = true,
            )
        }

        firstLine("Exec format error")?.let { hit ->
            return ErrorInfo(
                title = "Wrong architecture",
                detail = withTail(hit.trim(), lines),
                causes = listOf(
                    "The rootfs (or an imported archive) was built for a different CPU architecture",
                    "for example an amd64/x86 image on an arm64 phone",
                ),
                suggestions = listOf(
                    "Use Reset, then Install — the pinned catalog always matches this device",
                    "For Import: pick an archive exported from the same architecture",
                ),
                retryable = false,
            )
        }

        firstLine("Cannot allocate memory", "out of memory", "fork failed")?.let { hit ->
            return ErrorInfo(
                title = "The device ran out of memory for the command",
                detail = withTail(hit.trim(), lines),
                causes = listOf(
                    "apt/proot need free RAM; other apps may be holding it",
                    "Very large apt operations can exceed the per-app memory ceiling",
                ),
                suggestions = listOf(
                    "Close other apps, then retry",
                    "Reboot the device if it persists (clears memory pressure)",
                    "Run Update again — retrying usually succeeds once memory is free",
                ),
                retryable = true,
            )
        }

        if (firstLine("ptrace", "Operation not permitted") != null &&
            lines.any { it.contains("proot", ignoreCase = true) || it.contains("ptrace", ignoreCase = true) }
        ) {
            return ErrorInfo(
                title = "The kernel refused proot's process tracing (ptrace)",
                detail = withTail(firstLine("ptrace", "Operation not permitted")!!.trim(), lines),
                causes = listOf(
                    "Some vendor ROMs or booster apps restrict ptrace for regular apps",
                    "A device security policy or hardened SELinux rule blocked tracing",
                ),
                suggestions = listOf(
                    "Reboot the phone and retry (most common fix)",
                    "Uninstall cleaner/booster apps that restrict background processes",
                    "PROOT_NO_SECCOMP=1 is already set — this is a kernel-level refusal",
                ),
                retryable = true,
            )
        }

        firstLine("Permission denied")?.let { hit ->
            if (hit.contains("rootfs") || hit.contains("proot") || lines.any { it.contains("proot", ignoreCase = true) }) {
                return ErrorInfo(
                    title = "Permission denied on the Ubuntu files",
                    detail = withTail(hit.trim(), lines),
                    causes = listOf(
                        "The app's private directories became inaccessible",
                        "A device cleaner/booster app or SELinux policy may have interfered",
                    ),
                    suggestions = listOf(
                        "Run Repair to recreate the writable directories",
                        "Close cleaner/booster apps that touch app data",
                        "As last resort Reset, then Install again",
                    ),
                    retryable = true,
                )
            }
        }

        if (lines.any { it.contains("proot error", ignoreCase = true) }) {
            val first = lines.firstOrNull { it.contains("proot error", ignoreCase = true) } ?: ""
            return ErrorInfo(
                title = "proot failed to start the command",
                detail = withTail(first.trim(), lines),
                causes = listOf("proot reported a fatal error before the command could run"),
                suggestions = listOf(
                    "Run diagnostics (Settings → Ubuntu) for the full environment report",
                    "Run Repair; if it persists, Reset and Install again",
                ),
                retryable = true,
            )
        }

        // Guest-side loader failures — a command inside the rootfs could not
        // start because a library is missing/incompatible (broken upgrade,
        // wrong-arch library, no-op ldconfig left a stale cache). These are
        // NOT proot's own errors (proot exits 255 after printing them), so
        // they must be matched before the honest fallback.
        firstLine("error while loading shared libraries", "cannot open shared object file")?.let { hit ->
            return ErrorInfo(
                title = "A program inside Ubuntu is missing a shared library",
                detail = withTail(hit.trim(), lines),
                causes = listOf(
                    "The library was removed or upgraded while a program still needs it",
                    "A stale /etc/ld.so.cache or a partially-applied package operation",
                ),
                suggestions = listOf(
                    "Run Repair — it re-links package state (apt-get -f install) and refreshes the loader setup",
                    "If one app inside Ubuntu fails repeatedly, reinstall it inside Ubuntu: apt-get install --reinstall <package>",
                ),
                retryable = true,
            )
        }

        firstLine("CANNOT LINK EXECUTABLE", "Inconsistency detected by ld.so")?.let { hit ->
            return ErrorInfo(
                title = "The guest dynamic linker rejected a program",
                detail = withTail(hit.trim(), lines),
                causes = listOf(
                    "A binary/library inside the rootfs does not match the guest libc (mixed-architecture import or interrupted upgrade)",
                ),
                suggestions = listOf(
                    "Run Repair; if it persists, Reset and Install again",
                    "For Import: use an archive exported from the same architecture",
                ),
                retryable = true,
            )
        }

        // Honest fallback: no known signature — show the real output so neither
        // the user nor a bug report has to guess.
        return ErrorInfo(
            title = "Command exited with code 255 (proot fatal)",
            detail = withTail("(no known proot signature in the output — tail below)", lines),
            causes = listOf(
                "proot aborted before the command finished; the reason is in its output",
                "Known causes: temporary-dir problems, memory pressure, ptrace restrictions, corrupted rootfs",
            ),
            suggestions = listOf(
                "Run diagnostics (Settings → Ubuntu → Run diagnostics) and send the report with any bug report",
                "Run Repair; if it persists, Reset and Install again",
            ),
            retryable = true,
        )
    }

    /** Clipped tail text embedded into error details (capped lines, capped width). */
    fun withTail(firstHit: String, lines: List<String>): String {
        val tail = lines.filter { it.isNotBlank() }
            .takeLast(DETAIL_TAIL_LINES)
            .map { "  | " + it.take(LINE_CLIP) }
        if (tail.isEmpty()) return firstHit
        return firstHit + "\nOutput tail:\n" + tail.joinToString("\n")
    }

    /**
     * Maps a process exit that is a SIGNAL death (128+signal) onto an
     * actionable error. Exit 159 = 128+SIGSYS: the kernel's seccomp policy
     * killed proot for a syscall the allowlist refuses. This used to fall
     * through as a bare "exited with code 159" with an empty tail — users
     * on stricter vendor kernels reported exactly that (v0.1.10/11 static
     * glibc proot; fixed by the bionic proot bundle, but the error must stay
     * explainable if any device still triggers it).
     */
    fun mapSignalExit(exitCode: Int, lines: List<String>): ErrorInfo {
        val signal = exitCode - 128
        if (exitCode == 128 + 31) {
            return ErrorInfo(
                title = "The kernel killed proot (seccomp SIGSYS)",
                detail = withTail("process died from signal 31 (SIGSYS) — exit $exitCode", lines),
                causes = listOf(
                    "The device's seccomp policy refuses a syscall the proot binary made at startup",
                    "Known trigger: static glibc proot builds on kernels with a strict zygote allowlist",
                ),
                suggestions = listOf(
                    "Update to the latest app version — it ships a bionic proot that avoids these syscalls",
                    "Run Repair to refresh the installed proot binary",
                    "If it persists on the latest version, report the device model + Android version",
                ),
                retryable = true,
            )
        }
        return ErrorInfo(
            title = "The process died from signal $signal (exit $exitCode)",
            detail = withTail("terminated by signal $signal", lines),
            causes = listOf(
                "The kernel or the OS terminated the process abnormally",
                "Possible causes: memory pressure (OOM), a security policy, or a crash inside proot",
            ),
            suggestions = listOf(
                "Retry; close other apps first if memory is tight",
                "Run diagnostics (Settings → Ubuntu → Run diagnostics) and include the report in any bug report",
                "Run Repair; if it persists, Reset and Install again",
            ),
            retryable = true,
        )
    }
}
