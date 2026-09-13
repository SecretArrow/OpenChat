package com.openchat.android.core.upd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the in-app update checker: version comparison and
 * honest per-ABI APK asset selection (spec §2/§18/§21).
 */
class AppUpdaterTest {

    // ---------------------------------------------------------------- isNewer

    @Test
    fun `same version is not newer`() {
        assertFalse(AppUpdater.isNewer("v0.1.4", "0.1.4"))
        assertFalse(AppUpdater.isNewer("0.1.4", "v0.1.4"))
    }

    @Test
    fun `patch bump detected`() {
        assertTrue(AppUpdater.isNewer("v0.1.4", "0.1.3"))
        assertFalse(AppUpdater.isNewer("v0.1.3", "0.1.4"))
    }

    @Test
    fun `minor and major bumps detected`() {
        assertTrue(AppUpdater.isNewer("0.2.0", "0.1.9"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.99.99"))
    }

    @Test
    fun `short versions treated as padded`() {
        assertTrue(AppUpdater.isNewer("1.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("0.2", "0.1.4"))
    }

    @Test
    fun `malformed input never crashes and returns false`() {
        assertFalse(AppUpdater.isNewer("", "0.1.3"))
        assertFalse(AppUpdater.isNewer("vX.Y.Z", "0.1.3"))
        assertFalse(AppUpdater.isNewer("v0.1.4", ""))
        assertFalse(AppUpdater.isNewer("v0.1.4-beta", "0.1.3")) // prerelease tag of 0.1.4 still newer
    }

    // -------------------------------------------------------------- pickAsset

    @Test
    fun `exact ABI asset preferred`() {
        val assets = listOf(
            "OpenChat-arm64-v8a.apk" to 100L,
            "OpenChat-armeabi-v7a.apk" to 90L,
            "OpenChat-x86_64.apk" to 110L,
            "checksums.txt" to 10L,
        )
        assertEquals(
            "OpenChat-armeabi-v7a.apk" to 90L,
            AppUpdater.pickAsset(assets, listOf("armeabi-v7a", "arm64-v8a", "x86_64")),
        )
    }

    @Test
    fun `falls back to first apk when no ABI matches`() {
        val assets = listOf(
            "notes.md" to 5L,
            "OpenChat-arm64-v8a.apk" to 100L,
        )
        assertEquals(
            "OpenChat-arm64-v8a.apk" to 100L,
            AppUpdater.pickAsset(assets, listOf("riscv64")),
        )
    }

    @Test
    fun `no apk assets yields null`() {
        assertNull(AppUpdater.pickAsset(listOf("checksums.txt" to 1L), listOf("arm64-v8a")))
        assertNull(AppUpdater.pickAsset(emptyList(), listOf("arm64-v8a")))
    }

    // ------------------------------------------------------------- humanSize

    @Test
    fun `human sizes formatted`() {
        assertEquals("29.9 MB", AppUpdater.humanSize(29_928_044L))
        assertEquals("512 B", AppUpdater.humanSize(512L))
        assertEquals("2 KB", AppUpdater.humanSize(2048L))
    }
}
