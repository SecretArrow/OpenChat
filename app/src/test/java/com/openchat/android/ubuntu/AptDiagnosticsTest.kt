package com.openchat.android.ubuntu

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
        assertTrue(AptDiagnostics.isSignatureFailure(lines))
    }

    @Test
    fun `expired release is detected`() {
        assertTrue(AptDiagnostics.isSignatureFailure(listOf("E: Release file for … focal InRelease is expired")))
        assertTrue(
            AptDiagnostics.isSignatureFailure(
                listOf("E: InRelease is not valid yet (invalid for another 12h)"),
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
}
