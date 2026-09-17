package com.openchat.android.core.util

import com.openchat.android.core.model.ErrorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the copyable error report assembly ([ErrorReport.format]) —
 * every error surface (v0.1.13) must produce a report that carries the
 * device identity, the structured error and the registered log evidence.
 */
class ErrorReportTest {

    private val device = listOf(
        "app: OpenChat v0.1.13",
        "device: TestBrand TestModel — Android 13 (SDK 33)",
        "abi: arm64-v8a",
        "time: 2026-09-17 10:00:00 UTC",
        "free space (app data): 4096 MB",
    )

    @Test
    fun `report contains device, error and provider sections`() {
        val report = ErrorReport.format(
            deviceLines = device,
            error = ErrorInfo(
                title = "APT repository signature verification failed",
                detail = "E: The repository '…' is not signed.",
                causes = listOf("keyring outdated"),
                suggestions = listOf("Copy error + logs"),
            ),
            extraSections = listOf("Ubuntu log (last 300 lines)" to listOf("apt-get update…", "W: GPG error: NO_PUBKEY")),
            providerSections = listOf("Ubuntu failure output tail" to listOf("E: … is not signed.")),
        )
        assertTrue(report.startsWith("=== OpenChat error report ==="))
        assertTrue("app: OpenChat v0.1.13" in report)
        assertTrue("device: TestBrand TestModel — Android 13 (SDK 33)" in report)
        assertTrue("abi: arm64-v8a" in report)
        assertTrue("error: APT repository signature verification failed" in report)
        assertTrue("• keyring outdated" in report)
        assertTrue("→ Copy error + logs" in report)
        assertTrue("--- Ubuntu log (last 300 lines) ---" in report)
        assertTrue("W: GPG error: NO_PUBKEY" in report)
        assertTrue("--- Ubuntu failure output tail ---" in report)
    }

    @Test
    fun `empty sections are omitted`() {
        val report = ErrorReport.format(
            deviceLines = device,
            error = null,
            extraSections = listOf("empty" to emptyList()),
            providerSections = emptyList(),
        )
        assertFalse("error:" in report)
        assertFalse("--- empty ---" in report)
        assertFalse("possible causes:" in report)
    }

    @Test
    fun `report ends with exactly one newline`() {
        val report = ErrorReport.format(device, null, emptyList(), emptyList())
        assertTrue(report.endsWith("\n"))
        assertFalse(report.endsWith("\n\n"))
    }

    @Test
    fun `stack trace section is bounded`() {
        val lines = ErrorReport.stackTraceSection(RuntimeException("boom"), maxLines = 3)
        assertEquals(3, lines.size)
        assertTrue(lines.first().contains("RuntimeException"))
    }
}
