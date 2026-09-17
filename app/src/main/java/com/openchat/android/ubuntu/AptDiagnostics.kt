package com.openchat.android.ubuntu

import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.RepairAction

/**
 * Classifies `apt-get update` failures from their real output lines
 * (pure, JVM-tested — [AptDiagnosticsTest]).
 *
 * Three failure classes are distinguished, in precedence order:
 *
 *  1. **Temp-file failure** ([isTempFileFailure], v0.1.14): apt cannot create
 *     its temporary files in /tmp — `Couldn't create temporary file
 *     /tmp/apt.conf.XXXXXX for passing config to apt-key`. Real-device report
 *     (OPPO CPH2529, ColorOS 15): proot -R binds the HOST's /tmp over the
 *     guest, and on vendor builds that ship an app-unwritable /tmp every
 *     mkstemp fails — apt then declares every repository "is not signed".
 *     NOT a keyring problem: the v0.1.13 keyring/HTTPS repair cannot fix it
 *     (proven by the device report — two repair retries, identical error).
 *     Healed by the explicit guest-tmp bind + [UbuntuRuntime.runAptUpdate]'s
 *     tmp re-provision retry.
 *  2. **Signature failure** ([isSignatureFailure], v0.1.13): the repository
 *     metadata itself fails verification (outdated keyring → NO_PUBKEY, or a
 *     network path that tampers with plain-HTTP mirror traffic — transparent
 *     proxy / captive portal). Auto-repaired by re-provisioning the pinned
 *     keyring + CA bundle, clearing the lists, upgrading sources to HTTPS
 *     and retrying — see [UbuntuRuntime.runAptUpdate].
 *  3. Anything else is a generic command failure.
 *
 * The precedence matters: a temp-file failure ALSO contains "is not signed"
 * lines (apt's conclusion after the mkstemp failure), so the temp-file check
 * must run first or the wrong repair is attempted.
 */
object AptDiagnostics {

    /**
     * Both halves of apt's temp-file failure message, matched on the SAME line
     * for precision (a bare "Couldn't create temporary file" could also come
     * from other tools; the "for passing config to apt-key" tail is unique to
     * apt's ExecGPGV temp config). Matched case-insensitively.
     */
    private val TEMP_FILE_MARKERS = listOf(
        "Couldn't create temporary file",
        "for passing config to apt-key",
    )

    /**
     * True when any single captured line carries BOTH temp-file markers —
     * the exact signature of "proot bound an unusable /tmp over the guest".
     */
    fun isTempFileFailure(lines: List<String>): Boolean =
        lines.any { line -> TEMP_FILE_MARKERS.all { marker -> line.contains(marker, ignoreCase = true) } }

    /** The temp-file-bearing lines from the tail, for the error detail. */
    fun tempFileLines(lines: List<String>, max: Int = 8): List<String> =
        lines.filter { line ->
            TEMP_FILE_MARKERS.all { marker -> line.contains(marker, ignoreCase = true) } ||
                line.contains("is not signed", ignoreCase = true)
        }.takeLast(max)

    /**
     * Honest, actionable error for a temp-file failure that survived the
     * automatic tmp re-provision. [tail] is the real apt output.
     */
    fun tempFileErrorInfo(tail: List<String>): ErrorInfo {
        val real = tempFileLines(tail)
        val detail = buildString {
            append("apt-get could not create its temporary files in /tmp inside the rootfs.")
            if (real.isNotEmpty()) {
                append("\n\napt output:\n")
                append(real.joinToString("\n") { "| $it" })
            }
        }
        return ErrorInfo(
            title = "APT temporary-file failure (/tmp not writable)",
            detail = detail,
            causes = listOf(
                "proot bound an unusable host /tmp over the guest — vendor Android " +
                    "builds (OPPO/realme/OnePlus among others) ship a /tmp the app " +
                    "cannot write; OpenChat now overrides it with its own directory, " +
                    "so seeing this on an up-to-date app is unexpected",
                "The app's tmp directories were deleted while the operation ran " +
                    "(aggressive storage cleaners, very low storage)",
                "The rootfs's own /tmp or apt's partial directories are missing",
            ),
            suggestions = listOf(
                "Tap Copy error + logs and include the /tmp lines when reporting",
                "Run Repair — it re-provisions the tmp directories and retries the update",
                "Check that free storage is not exhausted (Settings → Storage)",
                "If it repeats every attempt, run the diagnostics below and report the output",
            ),
            retryable = true,
            repairAction = RepairAction.UBUNTU_REPAIR,
        )
    }

    /**
     * The subset of `gpgv`/apt failure lines that mean "the repository
     * metadata could not be signature-verified". Matched case-insensitively
     * against the captured output — never on exit codes.
     */
    private val SIGNATURE_MARKERS = listOf(
        "NO_PUBKEY", // signature made with a key absent from the keyring
        "is not signed", // apt refused the repository outright
        "GPG error:", // generic apt prefix for signature problems
        "Clearsigned", // InRelease replaced by non-OpenPGP content
        "couldn't be verified", // "The following signatures couldn't be verified…"
        "could not be verified", // variant wording across apt versions
        "EXPKEYSIG", // signature key expired
        "REVKEYSIG", // signature key revoked
        "is not valid yet", // clock far behind / premature metadata
        "is expired", // clock far ahead / stale mirror
    )

    /** True when any captured line indicates an apt signature verification failure. */
    fun isSignatureFailure(lines: List<String>): Boolean =
        lines.any { line -> SIGNATURE_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) } }

    /** The signature-bearing lines from the tail, for the error detail. */
    fun signatureLines(lines: List<String>, max: Int = 8): List<String> =
        lines.filter { line -> SIGNATURE_MARKERS.any { line.contains(it, ignoreCase = true) } }
            .takeLast(max)

    /**
     * Honest, actionable error for a signature failure that survived the
     * automatic keyring/HTTPS repair. [tail] is the real apt output.
     */
    fun signatureErrorInfo(tail: List<String>): ErrorInfo {
        val real = signatureLines(tail)
        val detail = buildString {
            append("apt-get could not verify the Ubuntu repository signatures.")
            if (real.isNotEmpty()) {
                append("\n\napt output:\n")
                append(real.joinToString("\n") { "| $it" })
            }
        }
        return ErrorInfo(
            title = "APT repository signature verification failed",
            detail = detail,
            causes = listOf(
                "The Ubuntu archive keyring in the rootfs is outdated or missing " +
                    "(OpenChat restores it automatically from its pinned assets)",
                "The network intercepts apt traffic (transparent proxy, captive portal " +
                    "or carrier DNS hijack) — HTTPS sources make this an honest " +
                    "connection error instead of silent tampering",
                "The device clock is far off (expired / not-yet-valid Release files)",
            ),
            suggestions = listOf(
                "Tap Copy error + logs and check the NO_PUBKEY / GPG lines",
                "Try a different network (mobile data vs Wi-Fi) and Repair again",
                "Verify the device date & time are correct (Settings → System)",
                "Use Repair, then Update — Update re-runs the signature-checked update",
            ),
            retryable = true,
            repairAction = RepairAction.UBUNTU_REPAIR,
        )
    }
}
