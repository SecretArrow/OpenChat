package com.openchat.android.ubuntu

import com.openchat.android.core.model.RepairAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the apt signature-failure classifier — the real-device
 * report ("E: The repository … focal InRelease is not signed", v0.1.12)
 * must be recognized from the exact apt output shapes, and healthy or
 * unrelated failures must never match.
 */
class AptDiagnosticsTest {

    @Test
    fun `real device failure shape is detected`() {
        val lines = listOf(
            "Hit:1 https://ports.ubuntu.com/ubuntu-ports focal InRelease",
            "W: GPG error: http://ports.ubuntu.com/ubuntu-ports focal InRelease: " +
                "The following signatures couldn't be verified because the public key is not available: " +
                "NO_PUBKEY 871920D1991BC93C",
            "E: The repository 'http://ports.ubuntu.com/ubuntu-ports focal InRelease' is not signed.",
            "N: Updating from such a repository can't be done securely, and is therefore disabled by default.",
        )
        assertTrue(AptDiagnostics.isSignatureFailure(lines))
    }

    @Test
    fun `clearsigned tampering is detected`() {
        val lines = listOf(
            "Err:1 http://archive.ubuntu.com/ubuntu focal InRelease",
            "  Clearsigned file isn't valid, got 'N:  Welcome to the captive portal'",
        )
        System.out.println("APT-DIAG-DEBUG per-line contains:")
        lines.forEach { l ->
            System.out.println("APT-DIAG-DEBUG line=$l -> clearsigned? " + l.contains("Clearsigned", ignoreCase = true))
        }
        val result = AptDiagnostics.isSignatureFailure(lines)
        System.out.println("APT-DIAG-DEBUG result=$result")
        assertTrue(
            "classifier must detect 'Clearsigned' marker in: $lines",
            result,
        )
    }

    @Test
    fun `expired release is detected`() {
        assertTrue(
            "classifier must detect 'is expired' wording",
            AptDiagnostics.isSignatureFailure(
                listOf(
                    "E: Release file for https://ports.ubuntu.com/ubuntu-ports focal " +
                        "InRelease is expired (invalid since 2023-10-01 00:00:00)",
                ),
            ),
        )
        assertTrue(
            "classifier must detect 'is not valid yet' wording",
            AptDiagnostics.isSignatureFailure(
                listOf("E: Release file for https://… focal InRelease is not valid yet (valid for another 12h)"),
            ),
        )
    }

    @Test
    fun `network and dns failures do not match`() {
        assertFalse(
            AptDiagnostics.isSignatureFailure(
                listOf(
                    "Err:1 https://ports.ubuntu.com/ubuntu-ports focal InRelease",
                    "  Temporary failure resolving 'ports.ubuntu.com'",
                ),
            ),
        )
        assertFalse(
            AptDiagnostics.isSignatureFailure(
                listOf(
                    "E: Failed to fetch https://ports.ubuntu.com/ubuntu-ports/dists/focal/InRelease " +
                        "Connection timed out",
                ),
            ),
        )
    }

    @Test
    fun `plain package errors do not match`() {
        assertFalse(
            AptDiagnostics.isSignatureFailure(
                listOf(
                    "E: Unable to locate package curl",
                    "E: The method driver /usr/lib/apt/methods/https could not be found.",
                ),
            ),
        )
    }

    @Test
    fun `empty and case insensitive inputs`() {
        assertFalse(AptDiagnostics.isSignatureFailure(emptyList()))
        assertTrue(AptDiagnostics.isSignatureFailure(listOf("E: THE REPOSITORY IS NOT SIGNED.")))
    }

    @Test
    fun `signature lines extract the evidence for the error detail`() {
        val lines = listOf(
            "Hit:1 https://ports.ubuntu.com/ubuntu-ports focal InRelease",
            "W: GPG error: … NO_PUBKEY 871920D1991BC93C",
            "E: The repository '…' is not signed.",
        )
        val extracted = AptDiagnostics.signatureLines(lines)
        assertEquals(2, extracted.size)
        assertTrue(extracted[0].contains("NO_PUBKEY"))
        assertTrue(extracted[1].contains("is not signed"))
    }

    @Test
    fun `signature error info embeds real output and repair actions`() {
        val info = AptDiagnostics.signatureErrorInfo(listOf("E: The repository '…' is not signed."))
        assertTrue(info.title.contains("signature"))
        assertTrue(info.detail.contains("is not signed"))
        assertTrue(info.causes.any { it.contains("keyring") })
        assertTrue(info.causes.any { it.contains("network", ignoreCase = true) })
        assertTrue(info.suggestions.any { it.contains("Copy error + logs") })
        assertTrue(info.repairAction == RepairAction.UBUNTU_REPAIR)
        assertTrue(info.retryable)
    }

    // ------------------------------------------------- temp-file class (v0.1.14)

    /** The EXACT output captured from the real device report (OPPO CPH2529). */
    private val deviceTempFileReport = listOf(
        "Hit:1 https://ports.ubuntu.com/ubuntu-ports focal InRelease",
        "Get:1 https://ports.ubuntu.com/ubuntu-ports focal InRelease [265 kB]",
        "Err:1 https://ports.ubuntu.com/ubuntu-ports focal InRelease",
        "  Couldn't create temporary file /tmp/apt.conf.bksSjz for passing config to apt-key",
        "Err:2 https://ports.ubuntu.com/ubuntu-ports focal-updates InRelease",
        "  Couldn't create temporary file /tmp/apt.conf.KpwkLC for passing config to apt-key",
        "Reading package lists...",
        "W: GPG error: https://ports.ubuntu.com/ubuntu-ports focal InRelease: " +
            "Couldn't create temporary file /tmp/apt.conf.bksSjz for passing config to apt-key",
        "E: The repository 'https://ports.ubuntu.com/ubuntu-ports focal InRelease' is not signed.",
        "W: GPG error: https://ports.ubuntu.com/ubuntu-ports focal-updates InRelease: " +
            "Couldn't create temporary file /tmp/apt.conf.KpwkLC for passing config to apt-key",
        "E: The repository 'https://ports.ubuntu.com/ubuntu-ports focal-updates InRelease' is not signed.",
    )

    @Test
    fun `temp file failure is detected in the exact real device report`() {
        assertTrue(AptDiagnostics.isTempFileFailure(deviceTempFileReport))
    }

    @Test
    fun `temp file failure takes precedence over the signature class`() {
        // The same tail matches BOTH classifiers (apt concludes "is not signed"
        // after the mkstemp failure) — the runtime must attempt the tmp repair,
        // not the keyring restore. This test pins the classification itself.
        assertTrue(AptDiagnostics.isTempFileFailure(deviceTempFileReport))
        assertTrue(AptDiagnostics.isSignatureFailure(deviceTempFileReport))
    }

    @Test
    fun `bare temp file half without the apt-key tail does not match`() {
        // Precision: "Couldn't create temporary file" alone must not trigger
        // the tmp classifier — both markers must be on the SAME line.
        assertFalse(
            AptDiagnostics.isTempFileFailure(
                listOf("dpkg: error: couldn't create temporary file while extracting './x'"),
            ),
        )
    }

    @Test
    fun `plain signature and network failures are not temp file failures`() {
        assertFalse(
            AptDiagnostics.isTempFileFailure(
                listOf("E: The repository 'http://…' is not signed."),
            ),
        )
        assertFalse(
            AptDiagnostics.isTempFileFailure(
                listOf("Err:1 https://ports.ubuntu.com … Temporary failure resolving 'ports.ubuntu.com'"),
            ),
        )
        assertFalse(AptDiagnostics.isTempFileFailure(emptyList()))
    }

    @Test
    fun `temp file error info embeds the real apt lines and repair actions`() {
        val info = AptDiagnostics.tempFileErrorInfo(deviceTempFileReport)
        assertTrue(info.title.contains("temp", ignoreCase = true))
        assertTrue(info.detail.contains("Couldn't create temporary file"))
        assertTrue(info.detail.contains("/tmp/apt.conf.bksSjz"))
        assertTrue(info.detail.contains("is not signed"))
        assertTrue(info.detail.contains("apt output:"))
        assertTrue(info.causes.any { it.contains("host /tmp", ignoreCase = true) })
        assertTrue(info.causes.any { it.contains("vendor", ignoreCase = true) })
        assertTrue(info.suggestions.any { it.contains("Copy error + logs") })
        assertTrue(info.suggestions.any { it.contains("Repair") })
        assertTrue(info.repairAction == RepairAction.UBUNTU_REPAIR)
        assertTrue(info.retryable)
    }

    @Test
    fun `temp file lines extract both error shapes for the detail`() {
        val extracted = AptDiagnostics.tempFileLines(deviceTempFileReport)
        assertTrue(extracted.any { it.contains("apt.conf.bksSjz") })
        assertTrue(extracted.any { it.startsWith("E: The repository") })
        // Cap respected (max 8 by default; the report has more matching lines).
        assertTrue(extracted.size <= 8)
    }
}
