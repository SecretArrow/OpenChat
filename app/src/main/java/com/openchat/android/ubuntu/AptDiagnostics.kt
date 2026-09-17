package com.openchat.android.ubuntu

import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.RepairAction

/**
 * Classifies `apt-get update` failures from their real output lines
 * (pure, JVM-tested — [AptDiagnosticsTest]).
 *
 * The user-facing report that motivated this class (v0.1.12, real device):
 * `E: The repository 'http://ports.ubuntu.com/ubuntu-ports focal InRelease'
 * is not signed.` — while the identical rootfs verified fine on CI. The two
 * realistic causes are (1) an outdated/missing Ubuntu archive keyring inside
 * the rootfs (apt answers `NO_PUBKEY 871920D1991BC93C` and refuses the
 * repository) and (2) a network path that tampers with plain-HTTP mirror
 * traffic (transparent proxy / captive portal answers with its own unsigned
 * content). Both are auto-repaired by the runtime: the keyring + CA bundle
 * are re-provisioned from the APK's pinned assets, lists are cleared, the
 * sources are upgraded to HTTPS and the update is retried — see
 * [UbuntuRuntime.runAptUpdate]. When it still fails afterwards, the error
 * below is shown so the user can copy the exact apt lines.
 */
object AptDiagnostics {

    /**
     * The subset of `gpgv`/apt failure lines that mean "the repository
     * metadata could not be signature-verified". Matched case-insensitively
     * against the captured output — never on exit codes.
     */
    private val SIGNATURE_MARKERS = listOf(
        "NO_PUBKEY", // signature made with a key absent from the keyring
        "is not signed", // apt refused the repository outright
        "GPG error:", // generic apt prefix for signature problems
        "Clearsigned file", // InRelease replaced by non-OpenPGP content
        "couldn't be verified", // "The following signatures couldn't be verified…"
        "could not be verified", // variant wording across apt versions
        "EXPKEYSIG", // signature key expired
        "REVKEYSIG", // signature key revoked
        "InRelease is not valid yet", // clock far behind / premature metadata
        "Release file expired", // clock far ahead / stale mirror
    )

    /** True when any captured line indicates an apt signature verification failure. */
    fun isSignatureFailure(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        val haystack = lines.joinToString("\n").lowercase()
        return SIGNATURE_MARKERS.any { marker -> marker in haystack }
    }

    /** The signature-bearing lines from the tail, for the error detail. */
    fun signatureLines(lines: List<String>, max: Int = 8): List<String> =
        lines.filter { line -> SIGNATURE_MARKERS.any { it.lowercase() in line.lowercase() } }
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
