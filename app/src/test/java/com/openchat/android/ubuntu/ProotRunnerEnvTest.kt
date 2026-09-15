package com.openchat.android.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the proot environment contract. [ProotRunner.baseEnv]
 * must always carry PROOT_TMP_DIR (pointed at a writable app directory) — the
 * missing variable was the root cause of every exec failing with
 * `can't create temporary directory: Permission denied` (exit 255).
 */
class ProotRunnerEnvTest {

    @Test
    fun `base env always sets PROOT_TMP_DIR and guest TMPDIR separately`() {
        val env = ProotRunner.baseEnv("/data/user/0/com.openchat.android/files/ubuntu/tmp")

        // proot's own scratch space is a HOST path — the fix for the 255 bug.
        assertEquals(
            "/data/user/0/com.openchat.android/files/ubuntu/tmp",
            env["PROOT_TMP_DIR"],
        )
        // The guest's /tmp is independent (proot binds it inside the rootfs).
        assertEquals("/tmp", env["TMPDIR"])

        // Android kernel stability: seccomp disabled, same as Termux builds.
        assertEquals("1", env["PROOT_NO_SECCOMP"])

        // Standard guest shell environment.
        assertEquals("/root", env["HOME"])
        assertEquals("C.UTF-8", env["LANG"])
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
        assertTrue(env["PATH"]!!.contains("/opt/node/bin"))
    }

    @Test
    fun `caller extras override the base env`() {
        val base = ProotRunner.baseEnv("/tmp/proot")
        val merged = ProotRunner.mergeEnv(base, mapOf("HOME" to "/root/custom", "EXTRA" to "1"))

        assertEquals("/root/custom", merged["HOME"])
        assertEquals("1", merged["EXTRA"])
        // Base keys survive the merge.
        assertEquals("/tmp/proot", merged["PROOT_TMP_DIR"])
        assertEquals("1", merged["PROOT_NO_SECCOMP"])
    }

    @Test
    fun `merge with empty extras is the base env`() {
        val base = ProotRunner.baseEnv("/w")
        val merged = ProotRunner.mergeEnv(base, emptyMap())
        assertEquals(base, merged)
    }

    @Test
    fun `bionic env adds lib path and ptrace loaders`() {
        val env = ProotRunner.baseEnv(
            "/data/user/0/com.openchat.android/files/ubuntu/tmp",
            "/data/user/0/com.openchat.android/files/ubuntu/lib",
        )

        // libtalloc + libandroid-shmem live here (bionic x86_64 build).
        assertEquals(
            "/data/user/0/com.openchat.android/files/ubuntu/lib",
            env["LD_LIBRARY_PATH"],
        )
        // Termux's fallback loader paths point at the Termux prefix, which
        // does not exist in this app — the loaders MUST be pointed explicitly.
        assertEquals(
            "/data/user/0/com.openchat.android/files/ubuntu/lib/loader",
            env["PROOT_LOADER"],
        )
        assertEquals(
            "/data/user/0/com.openchat.android/files/ubuntu/lib/loader32",
            env["PROOT_LOADER_32"],
        )

        // Base keys survive.
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun `static-build env has no host lib keys`() {
        val env = ProotRunner.baseEnv("/w", null)
        assertTrue(!env.containsKey("LD_LIBRARY_PATH"))
        assertTrue(!env.containsKey("PROOT_LOADER"))
        assertTrue(!env.containsKey("PROOT_LOADER_32"))
    }

    @Test
    fun `bionicEnvExtras is empty without a lib dir and consistent with baseEnv`() {
        assertTrue(ProotRunner.bionicEnvExtras(null).isEmpty())

        val extras = ProotRunner.bionicEnvExtras("/data/lib")
        val env = ProotRunner.baseEnv("/w", "/data/lib")
        for ((k, v) in extras) {
            assertEquals(v, env[k])
        }
    }
}
