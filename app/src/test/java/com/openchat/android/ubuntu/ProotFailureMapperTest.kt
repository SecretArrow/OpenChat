package com.openchat.android.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the proot exit-255 failure mapper — every 255 must produce
 * an actionable ErrorInfo whose detail embeds the real output tail.
 */
class ProotFailureMapperTest {

    @Test
    fun `temporary dir signature maps with tail`() {
        val lines = listOf(
            "proot warning: can't sanitize binding - ignored",
            "proot error: can't create temporary directory: Permission denied",
            "apt-get update",
        )
        val info = ProotFailureMapper.map(lines)
        assertEquals("proot could not create its temporary files", info.title)
        assertTrue(info.detail.contains("can't create temporary directory"))
        assertTrue(info.detail.contains("Output tail"))
        assertTrue(info.detail.contains("apt-get update"))
        assertTrue(info.retryable)
    }

    @Test
    fun `ENOSPC signature names the exact volume problem instead of generic storage`() {
        val info = ProotFailureMapper.map(listOf("write error: No space left on device"))
        assertTrue(info.title.contains("out of space"))
        assertTrue(info.detail.contains("No space left on device"))
        assertTrue(info.suggestions.any { it.contains("diagnostics", ignoreCase = true) })
    }

    @Test
    fun `exec format error is non-retryable wrong architecture`() {
        val info = ProotFailureMapper.map(listOf("proot error: Exec format error"))
        assertFalse(info.retryable)
        assertTrue(info.causes.any { it.contains("architecture", ignoreCase = true) })
    }

    @Test
    fun `memory pressure signature maps to OOM guidance`() {
        val info = ProotFailureMapper.map(listOf("E: Cannot allocate memory"))
        assertTrue(info.title.contains("memory", ignoreCase = true))
        assertTrue(info.retryable)
        assertTrue(info.suggestions.any { it.contains("Close other apps", ignoreCase = true) })
    }

    @Test
    fun `ptrace restriction maps to kernel guidance`() {
        val info = ProotFailureMapper.map(
            listOf("proot error: ptrace(PTRACE_TRACEME): Operation not permitted"),
        )
        assertTrue(info.title.contains("ptrace", ignoreCase = true))
        assertTrue(info.suggestions.any { it.contains("Reboot", ignoreCase = true) })
    }

    @Test
    fun `permission denied with proot context maps to permission guidance`() {
        val info = ProotFailureMapper.map(
            listOf("proot error: open /data/.../rootfs/bin/bash: Permission denied"),
        )
        assertEquals("Permission denied on the Ubuntu files", info.title)
        assertTrue(info.retryable)
    }

    @Test
    fun `unknown 255 falls back to honest error with the full tail`() {
        val lines = listOf(
            "line one",
            "something exotic went wrong",
            "final line",
        )
        val info = ProotFailureMapper.map(lines)
        assertTrue(info.title.contains("255"))
        assertTrue(info.detail.contains("something exotic went wrong"))
        assertTrue(info.detail.contains("final line"))
        assertTrue(info.causes.isNotEmpty())
    }

    @Test
    fun `withTail clips line count and width`() {
        val lines = (1..40).map { "L$it ${"x".repeat(500)}" }
        val text = ProotFailureMapper.withTail("hit", lines)
        val tailLines = text.lines().filter { it.startsWith("  | ") }
        assertEquals(ProotFailureMapper.DETAIL_TAIL_LINES, tailLines.size)
        tailLines.forEach { assertTrue(it.length <= 205) }
    }
}

/** JVM tests for the shared APT sources writer used by install + import. */
class AptSourcesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `classic base gets a classic sources list`() {
        val apt = tmp.newFolder("apt")
        AptSources.writeFor(apt, "arm64", "jammy").getOrThrow()
        val text = java.io.File(apt, "sources.list").readText()
        assertTrue(text.contains("http://ports.ubuntu.com/ubuntu-ports jammy main universe"))
        assertTrue(text.contains("jammy-updates") && text.contains("jammy-security"))
    }

    @Test
    fun `amd64 uses archive ubuntu com`() {
        val apt = tmp.newFolder("apt2")
        AptSources.writeFor(apt, "amd64", "noble").getOrThrow()
        assertTrue(java.io.File(apt, "sources.list").readText().contains("http://archive.ubuntu.com/ubuntu"))
    }

    @Test
    fun `existing deb822 file is rewritten in place and sources list neutralized`() {
        val apt = tmp.newFolder("apt3")
        val sourcesListD = java.io.File(apt, "sources.list.d").apply { mkdirs() }
        java.io.File(sourcesListD, "ubuntu.sources").writeText("Types: deb\nURIs: http://old\n")
        AptSources.writeFor(apt, "arm64", "noble").getOrThrow()
        val deb822 = java.io.File(sourcesListD, "ubuntu.sources").readText()
        assertTrue(deb822.contains("URIs: http://ports.ubuntu.com/ubuntu-ports"))
        assertTrue(deb822.contains("Suites: noble noble-updates noble-security"))
        assertTrue(java.io.File(apt, "sources.list").readText().contains("# Configured by OpenChat"))
    }

    @Test
    fun `hasActiveSources distinguishes raw base from configured backup`() {
        val apt = tmp.newFolder("apt4")
        // raw ubuntu-base: no sources at all
        assertFalse(AptSources.hasActiveSources(apt))
        // classic active line
        java.io.File(apt, "sources.list").writeText("# comment\ndeb http://x jammy main\n")
        assertTrue(AptSources.hasActiveSources(apt))
        // empty classic + deb822 active
        java.io.File(apt, "sources.list").writeText("# nothing active\n")
        assertFalse(AptSources.hasActiveSources(apt))
        val d822 = java.io.File(apt, "sources.list.d").apply { mkdirs() }
        java.io.File(d822, "ubuntu.sources").writeText("Types: deb\nURIs: http://x\n")
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

    @Test
    fun `shared-library signature maps to actionable error`() {
        val lines = listOf(
            "proot info: started /bin/bash",
            "/usr/bin/apt: error while loading shared libraries: libapt-pkg.so.6.0: cannot open shared object file: No such file or directory",
        )
        val err = ProotFailureMapper.map(lines)
        assertEquals("A program inside Ubuntu is missing a shared library", err.title)
        assertTrue(err.detail.contains("libapt-pkg.so.6.0"))
        assertTrue(err.retryable)
    }

    @Test
    fun `bionic linker signature maps to linker error`() {
        val lines = listOf("CANNOT LINK EXECUTABLE \"/bin/sh\": library \"libxxx.so\" not found: needed by /bin/sh")
        val err = ProotFailureMapper.map(lines)
        assertEquals("The guest dynamic linker rejected a program", err.title)
    }

    @Test
    fun `exit 159 maps to seccomp SIGSYS explanation`() {
        val err = ProotFailureMapper.mapSignalExit(159, emptyList())
        assertEquals("The kernel killed proot (seccomp SIGSYS)", err.title)
        assertTrue(err.detail.contains("SIGSYS"))
        assertTrue(err.retryable)
    }

    @Test
    fun `other signal deaths map with the signal number`() {
        val err = ProotFailureMapper.mapSignalExit(137, emptyList())
        assertEquals("The process died from signal 9 (exit 137)", err.title)
    }

    @Test
    fun `unknown 255 still falls back honestly with the tail`() {
        val err = ProotFailureMapper.map(listOf("something unprecedented went wrong"))
        assertEquals("Command exited with code 255 (proot fatal)", err.title)
        assertTrue(err.detail.contains("no known proot signature"))
        assertTrue(err.detail.contains("something unprecedented went wrong"))
    }
}
