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
    fun `focal variants are the pinned default for the three supported ABIs`() {
        // Default = focal (glibc 2.31): jammy+ bases use clone3(2), which the
        // Android seccomp allowlist answers with SIGSYS — apt dies silently
        // with exit 159 (128+31). Focal predates clone3. Hashes are real
        // values from cdimage's SHA256SUMS (spec §32).
        val arm64 = RootfsCatalog.forAbi("arm64-v8a")
        assertEquals("focal", arm64.codename)
        assertEquals("20.04", arm64.ubuntuVersion)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-arm64.tar.gz",
            arm64.url,
        )
        assertEquals("f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52", arm64.sha256)

        val armhf = RootfsCatalog.forAbi("armeabi-v7a")
        assertEquals("focal", armhf.codename)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-armhf.tar.gz",
            armhf.url,
        )
        assertEquals("6bcbfa7f603d79d368d40e138dad98938907d2fb0d6416521417cf8702c2f5de", armhf.sha256)

        val amd64 = RootfsCatalog.forAbi("x86_64")
        assertEquals("focal", amd64.codename)
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-amd64.tar.gz",
            amd64.url,
        )
        assertEquals("60e216b60947653dc8989be3821380268315b99b2959c29882781541bfe5a426", amd64.sha256)
    }

    @Test
    fun `jammy variants keep their verified pins for override users`() {
        val jammyArm64 = RootfsCatalog.variants.first {
            it.codename == "jammy" && it.url.endsWith("arm64.tar.gz")
        }
        assertEquals("075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f", jammyArm64.sha256)
        val jammyAmd64 = RootfsCatalog.variants.first {
            it.codename == "jammy" && it.url.endsWith("amd64.tar.gz")
        }
        assertEquals("242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a", jammyAmd64.sha256)
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
    fun `catalog contains focal, jammy and noble for all three arches`() {
        assertEquals(9, RootfsCatalog.variants.size)
        for (codename in listOf("focal", "jammy", "noble")) {
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
