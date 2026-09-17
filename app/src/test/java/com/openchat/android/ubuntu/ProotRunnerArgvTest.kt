package com.openchat.android.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ProotRunner.sessionArgv] — the argv contract of EVERY proot
 * exec/session in the app.
 *
 * The tmp binds are the v0.1.14 fix for the real-device report (OPPO CPH2529,
 * ColorOS 15): `proot -R` bind-mounts the HOST's /tmp over the guest, and on
 * vendor builds that ship an app-unwritable /tmp apt died with
 * "Couldn't create temporary file /tmp/apt.conf.XXXXXX for passing config to
 * apt-key" → "The repository … is not signed". Later `-b` arguments override
 * -R's recommended binds (the mechanism the NSS identity binds rely on since
 * run 18), so the tmp binds MUST come after `-R rootfs`.
 */
class ProotRunnerArgvTest {

    private val identity = listOf(
        "/data/…/rootfs/etc/passwd" to "/etc/passwd",
        "/data/…/rootfs/etc/group" to "/etc/group",
        "/data/…/rootfs/etc/nsswitch.conf" to "/etc/nsswitch.conf",
    )
    private val tmpBinds = listOf(
        "/data/…/files/ubuntu/guest-tmp" to "/tmp",
        "/data/…/files/ubuntu/guest-shm" to "/dev/shm",
    )

    private fun argv() = ProotRunner.sessionArgv(
        prootBin = "/data/…/files/ubuntu/bin/proot",
        cwd = "/root",
        rootfsPath = "/data/…/files/ubuntu/rootfs",
        identityBinds = identity,
        tmpBinds = tmpBinds,
        cmd = listOf("/bin/bash", "-lc", "echo hi"),
    )

    @Test
    fun `argv opens with the fixed proot flags and rootfs`() {
        val a = argv()
        assertEquals(
            listOf(
                "/data/…/files/ubuntu/bin/proot",
                "--kill-on-exit",
                "-0",
                "-w",
                "/root",
                "-R",
                "/data/…/files/ubuntu/rootfs",
            ),
            a.take(7),
        )
    }

    @Test
    fun `identity binds are present in host-guest form`() {
        val a = argv()
        // Each bind pair: "-b" followed by "host:guest".
        val bindArgs = a.filterIndexed { idx, _ -> idx > 0 && a[idx - 1] == "-b" }
        for ((host, guest) in identity) {
            assertTrue("missing bind $host:$guest in $a", bindArgs.contains("$host:$guest"))
        }
    }

    @Test
    fun `guest tmp binds are present`() {
        val bindArgs = argv().filterIndexed { idx, s -> idx > 0 && argv()[idx - 1] == "-b" }
        assertTrue(bindArgs.contains("/data/…/files/ubuntu/guest-tmp:/tmp"))
        assertTrue(bindArgs.contains("/data/…/files/ubuntu/guest-shm:/dev/shm"))
    }

    @Test
    fun `tmp binds come after -R so they override its recommended host binds`() {
        val a = argv()
        val rIndex = a.indexOf("-R")
        val tmpBindIndex = a.indexOf("/data/…/files/ubuntu/guest-tmp:/tmp")
        val shmBindIndex = a.indexOf("/data/…/files/ubuntu/guest-shm:/dev/shm")
        assertTrue("guest-tmp bind must precede nothing — it must FOLLOW -R", tmpBindIndex > rIndex)
        assertTrue(shmBindIndex > rIndex)
        // And after the identity binds, keeping a stable, documented order.
        val nssIndex = a.indexOf("/data/…/rootfs/etc/nsswitch.conf:/etc/nsswitch.conf")
        assertTrue(tmpBindIndex > nssIndex)
    }

    @Test
    fun `command is the argv tail`() {
        val a = argv()
        assertEquals(listOf("/bin/bash", "-lc", "echo hi"), a.takeLast(3))
    }

    @Test
    fun `every bind is introduced by -b`() {
        val a = argv()
        var binds = 0
        for (i in a.indices) {
            if (a[i] == "-b") {
                binds++
                // The next element must be the host:guest pair.
                assertTrue(i + 1 < a.size && a[i + 1].contains(":"))
            }
        }
        assertEquals(identity.size + tmpBinds.size, binds)
    }
}
