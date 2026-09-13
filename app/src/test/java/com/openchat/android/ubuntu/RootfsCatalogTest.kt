package com.openchat.android.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the REAL, verified rootfs catalog (pinned URLs + SHA-256, spec §3/§32).
 * If any of these hashes changes, the install pipeline must fail loudly instead
 * of silently trusting a different artifact — hence exact-match assertions.
 */
class RootfsCatalogTest {

    @Test
    fun `jammy variants are pinned for the three supported ABIs`() {
        val arm64 = RootfsCatalog.forAbi("arm64-v8a")
        assertEquals("jammy", arm64.codename)
        assertEquals("22.04", arm64.ubuntuVersion)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
            arm64.url,
        )
        assertEquals("075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f", arm64.sha256)

        val armhf = RootfsCatalog.forAbi("armeabi-v7a")
        assertEquals("jammy", armhf.codename)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-armhf.tar.gz",
            armhf.url,
        )
        assertEquals("fd77cb0659326b75c08ce06b6b8649d2e13ef9a704a8e9212fec32cb97d42add", armhf.sha256)

        val amd64 = RootfsCatalog.forAbi("x86_64")
        assertEquals("jammy", amd64.codename)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-amd64.tar.gz",
            amd64.url,
        )
        assertEquals("242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a", amd64.sha256)
    }

    @Test
    fun `every pinned sha256 is a lowercase 64-hex digest`() {
        val hex = Regex("^[0-9a-f]{64}$")
        for (v in RootfsCatalog.variants) {
            assertTrue("bad sha for ${v.url}: ${v.sha256}", hex.matches(v.sha256))
            assertTrue("bad url: ${v.url}", v.url.startsWith("https://cdimage.ubuntu.com/ubuntu-base/releases/"))
        }
    }

    @Test
    fun `catalog contains jammy and noble for all three arches`() {
        assertEquals(6, RootfsCatalog.variants.size)
        for (codename in listOf("jammy", "noble")) {
            for (arch in listOf("arm64", "armhf", "amd64")) {
                val found = RootfsCatalog.variants.any {
                    it.codename == codename && it.url.endsWith("-base-$arch.tar.gz")
                }
                assertTrue("missing variant $codename/$arch", found)
            }
        }
        val noble = RootfsCatalog.variants.first { it.codename == "noble" && it.url.endsWith("arm64.tar.gz") }
        assertEquals("24.04", noble.ubuntuVersion)
        assertEquals("a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", noble.sha256)
    }

    @Test
    fun `ubuntuArchForAbi maps Android ABIs to Ubuntu arch names`() {
        assertEquals("arm64", RootfsCatalog.ubuntuArchForAbi("arm64-v8a"))
        assertEquals("armhf", RootfsCatalog.ubuntuArchForAbi("armeabi-v7a"))
        assertEquals("amd64", RootfsCatalog.ubuntuArchForAbi("x86_64"))
    }

    @Test
    fun `unknown ABI throws IllegalArgumentException listing the supported ones`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            RootfsCatalog.ubuntuArchForAbi("mips64")
        }
        assertTrue(e.message!!.contains("arm64-v8a"))
        assertTrue(e.message!!.contains("armeabi-v7a"))
        assertTrue(e.message!!.contains("x86_64"))

        assertThrows(IllegalArgumentException::class.java) { RootfsCatalog.forAbi("riscv64") }
    }
}
