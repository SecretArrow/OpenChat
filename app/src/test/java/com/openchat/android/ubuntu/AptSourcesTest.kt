package com.openchat.android.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [AptSources] — v0.1.13 makes the official mirrors HTTPS-only
 * (transparent HTTP proxies on real networks produce the exact
 * `E: The repository … is not signed` failure reported by users) and adds
 * [AptSources.upgradeToHttps] to heal rootfs written by older app versions.
 */
class AptSourcesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------- writeFor

    @Test
    fun `classic sources use https ports for arm64`() {
        val apt = tmp.newFolder("apt1")
        AptSources.writeFor(apt, "arm64", "focal").getOrThrow()
        val text = File(apt, "sources.list").readText()
        assertEquals(
            "deb https://ports.ubuntu.com/ubuntu-ports focal main universe\n" +
                "deb https://ports.ubuntu.com/ubuntu-ports focal-updates main universe\n" +
                "deb https://ports.ubuntu.com/ubuntu-ports focal-security main universe\n",
            text,
        )
        assertFalse("http://" in text)
    }

    @Test
    fun `classic sources use https archive for amd64`() {
        val apt = tmp.newFolder("apt2")
        AptSources.writeFor(apt, "amd64", "focal").getOrThrow()
        val text = File(apt, "sources.list").readText()
        assertTrue("deb https://archive.ubuntu.com/ubuntu focal main universe" in text)
        assertFalse("http://" in text)
    }

    @Test
    fun `deb822 content carries https and signed-by`() {
        val content = AptSources.deb822Content(AptSources.PORTS_BASE, "focal")
        assertTrue("URIs: https://ports.ubuntu.com/ubuntu-ports" in content)
        assertTrue("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg" in content)
        assertFalse("http://" in content)
    }

    // -------------------------------------------------------- upgradeToHttps

    @Test
    fun `legacy http sources are upgraded in place`() {
        val apt = tmp.newFolder("apt3")
        val list = File(apt, "sources.list")
        list.writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports focal main universe\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports focal-updates main universe\n",
        )
        assertTrue(AptSources.upgradeToHttps(apt))
        val text = list.readText()
        assertFalse("http://" in text)
        assertTrue("deb https://ports.ubuntu.com/ubuntu-ports focal main universe" in text)
    }

    @Test
    fun `deb822 legacy http uris are upgraded`() {
        val apt = tmp.newFolder("apt4")
        val d = File(apt, "sources.list.d").apply { mkdirs() }
        File(d, "ubuntu.sources").writeText(
            "Types: deb\n" +
                "URIs: http://archive.ubuntu.com/ubuntu\n" +
                "Suites: jammy jammy-updates jammy-security\n",
        )
        assertTrue(AptSources.upgradeToHttps(apt))
        val text = File(d, "ubuntu.sources").readText()
        assertTrue("URIs: https://archive.ubuntu.com/ubuntu" in text)
        assertFalse("http://" in text)
    }

    @Test
    fun `third party uris and https content are untouched`() {
        val apt = tmp.newFolder("apt5")
        val list = File(apt, "sources.list")
        val original =
            "deb https://ports.ubuntu.com/ubuntu-ports focal main universe\n" +
                "deb http://example.local/ubuntu custom main\n"
        list.writeText(original)
        assertFalse(AptSources.upgradeToHttps(apt))
        assertEquals(original, list.readText())
    }

    @Test
    fun `upgrade is idempotent`() {
        val apt = tmp.newFolder("apt6")
        File(apt, "sources.list").writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports focal main universe\n",
        )
        assertTrue(AptSources.upgradeToHttps(apt))
        assertFalse("second run must be a no-op", AptSources.upgradeToHttps(apt))
    }

    @Test
    fun `missing apt dir is a safe no-op`() {
        assertFalse(AptSources.upgradeToHttps(File(tmp.root, "does-not-exist")))
    }

    // ------------------------------------------------- moved from the former
    // AptSourcesTest (was inside ProotFailureMapperTest.kt; its http://
    // expectations belonged to the pre-HTTPS writer).

    @Test
    fun `hasActiveSources distinguishes raw base from configured backup`() {
        val apt = tmp.newFolder("apt7")
        // raw ubuntu-base: no sources at all
        assertFalse(AptSources.hasActiveSources(apt))
        // classic active line
        File(apt, "sources.list").writeText("# comment\ndeb http://x jammy main\n")
        assertTrue(AptSources.hasActiveSources(apt))
        // empty classic + deb822 active
        File(apt, "sources.list").writeText("# nothing active\n")
        assertFalse(AptSources.hasActiveSources(apt))
        val d822 = File(apt, "sources.list.d").apply { mkdirs() }
        File(d822, "ubuntu.sources").writeText("Types: deb\nURIs: http://x\n")
        assertTrue(AptSources.hasActiveSources(apt))
    }

    @Test
    fun `codename from os-release handles quotes`() {
        val codename = AptSources.codenameFromOsRelease(
            "NAME=\"Ubuntu\"\nVERSION=\"24.04.5 LTS (Noble Numbat)\"\nVERSION_CODENAME=noble\n",
        )
        assertEquals("noble", codename)
        assertEquals(null, AptSources.codenameFromOsRelease("ID=ubuntu\n"))
    }

    @Test
    fun `uname machine maps to ubuntu arch`() {
        assertEquals("arm64", AptSources.archFromUname("aarch64"))
        assertEquals("amd64", AptSources.archFromUname("x86_64"))
        assertEquals("armhf", AptSources.archFromUname("armv7l"))
        assertEquals(null, AptSources.archFromUname("riscv64"))
    }
}
