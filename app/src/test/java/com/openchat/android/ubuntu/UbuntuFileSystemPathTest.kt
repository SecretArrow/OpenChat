package com.openchat.android.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM tests for the anti path-traversal resolver and the protected
 * rootfs-path guard (spec §23). Uses only java.io/java.nio — no Android classes
 * are touched at runtime.
 */
class UbuntuFileSystemPathTest {

    private fun tempRoot(): File = Files.createTempDirectory("openchat-pathtest").toFile()

    @Test
    fun `parent traversal is rejected`() {
        val root = tempRoot()
        val result = UbuntuFileSystem.safeResolve(root, "../x")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `traversal nested inside a valid path is rejected`() {
        val root = tempRoot()
        assertTrue(UbuntuFileSystem.safeResolve(root, "a/../../x").isFailure)
        assertTrue(UbuntuFileSystem.safeResolve(root, "..").isFailure)
        assertTrue(UbuntuFileSystem.safeResolve(root, "a/../..").isFailure)
    }

    @Test
    fun `absolute paths are rejected`() {
        val root = tempRoot()
        val result = UbuntuFileSystem.safeResolve(root, "/abs")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `nested relative paths resolve below the root`() {
        val root = tempRoot()
        val result = UbuntuFileSystem.safeResolve(root, "sub/file.txt")
        val file = result.getOrNull() ?: return fail("expected success, got ${result.exceptionOrNull()}")
        assertEquals(File(root, "sub/file.txt").absolutePath, file.absolutePath)
    }

    @Test
    fun `empty and dot resolve to the root itself`() {
        val root = tempRoot()
        assertEquals(root.absolutePath, UbuntuFileSystem.safeResolve(root, "").getOrNull()?.absolutePath)
        assertEquals(root.absolutePath, UbuntuFileSystem.safeResolve(root, ".").getOrNull()?.absolutePath)
    }

    @Test
    fun `dot-dot inside the tree normalizes instead of failing`() {
        val root = tempRoot()
        val result = UbuntuFileSystem.safeResolve(root, "a/../b/c")
        val file = result.getOrNull() ?: return fail("expected success, got ${result.exceptionOrNull()}")
        assertEquals(File(root, "b/c").absolutePath, file.absolutePath)
    }

    @Test
    fun `blank root is rejected`() {
        val root = File("")
        assertTrue(UbuntuFileSystem.safeResolve(root, "anything").isFailure)
    }

    @Test
    fun `a symlink pointing outside the root cannot be resolved through`() {
        val root = tempRoot()
        val outside = Files.createTempDirectory("openchat-outside").toFile()
        val link = File(root, "escape")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        // Some filesystems/CI sandboxes forbid symlinks — only run the assertion when created.
        assumeTrue(created)
        val result = UbuntuFileSystem.safeResolve(root, "escape/secret.txt")
        assertTrue("symlink escape must be rejected", result.isFailure)
    }

    @Test
    fun `protected rootfs system paths are detected`() {
        for (p in listOf("/usr/bin", "usr/bin", "/etc/passwd", "etc", "/lib64", "boot/vmlinuz", "/sys")) {
            assertTrue("expected protected: $p", UbuntuFileSystem.isProtectedRootfsPath(p))
        }
    }

    @Test
    fun `user areas of the rootfs are not protected`() {
        for (p in listOf("/root/x", "root", "/home/u/file", "home", "/tmp", "/var/log", "/opt", "srv", "")) {
            assertTrue("expected NOT protected: $p", !UbuntuFileSystem.isProtectedRootfsPath(p))
        }
    }
}
