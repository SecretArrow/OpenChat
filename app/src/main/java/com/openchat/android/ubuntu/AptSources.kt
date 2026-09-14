package com.openchat.android.ubuntu

import java.io.File

/**
 * APT sources configuration for Ubuntu rootfs — pure, JVM-tested ([AptSourcesTest]).
 *
 * Used by the installer ([UbuntuInstaller.configure]) and, crucially, by Import:
 * raw `ubuntu-base` tarballs from cdimage.ubuntu.com ship WITHOUT any apt
 * sources, so `apt-get update` fails out of the box after importing one.
 * Import now detects that state and writes the correct per-arch sources.
 */
object AptSources {

    /** deb822 file present in newer bases (noble+). */
    const val DEB822_FILE = "sources.list.d/ubuntu.sources"

    /**
     * True when the rootfs' apt dir already has usable repository config:
     * a classic `sources.list` with an active `deb ` line, or a deb822 file
     * with a `Types:` entry. Used to tell app backups (configured) apart from
     * raw base tarballs (empty).
     */
    fun hasActiveSources(aptDir: File): Boolean {
        if (!aptDir.isDirectory) return false
        val classic = File(aptDir, "sources.list")
        if (classic.isFile &&
            classic.readLines().any { line -> line.trimStart().startsWith("deb ") }
        ) {
            return true
        }
        val deb822 = File(aptDir, DEB822_FILE)
        if (deb822.isFile && deb822.readText().contains("Types:")) return true
        // Any other .list fragment with an active line also counts.
        val fragments = File(aptDir, "sources.list.d")
        if (fragments.isDirectory) {
            fragments.listFiles { f -> f.isFile && f.name.endsWith(".list") }?.forEach { f ->
                if (f.readLines().any { it.trimStart().startsWith("deb ") }) return true
            }
        }
        return false
    }

    /**
     * Writes the repository config for [arch] (`amd64` vs ports for arm64/armhf)
     * and [codename] — identical output to the installer path: deb822 when the
     * base ships one, classic sources.list otherwise.
     */
    fun writeFor(aptDir: File, arch: String, codename: String): Result<Unit> = runCatching {
        val base = if (arch == "amd64") "http://archive.ubuntu.com/ubuntu" else "http://ports.ubuntu.com/ubuntu-ports"
        val apt = aptDir.apply { mkdirs() }
        val deb822 = File(apt, DEB822_FILE)
        if (deb822.isFile) {
            // Rewrite the existing deb822 file in place (never duplicate suites).
            deb822.writeText(deb822Content(base, codename))
            File(apt, "sources.list").writeText(
                "# Configured by OpenChat — see sources.list.d/ubuntu.sources\n",
            )
        } else {
            File(apt, "sources.list").writeText(classicContent(base, codename))
        }
    }

    /** The full deb822 document (visible for tests). */
    fun deb822Content(base: String, codename: String): String =
        "Types: deb\n" +
            "URIs: $base\n" +
            "Suites: $codename $codename-updates $codename-security\n" +
            "Components: main universe\n" +
            "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"

    /** Classic sources.list document (visible for tests). */
    fun classicContent(base: String, codename: String): String =
        "deb $base $codename main universe\n" +
            "deb $base $codename-updates main universe\n" +
            "deb $base $codename-security main universe\n"

    /**
     * Extracts VERSION_CODENAME from an /etc/os-release document
     * (`VERSION_CODENAME=jammy` or `VERSION_CODENAME="noble"`); null when absent.
     */
    fun codenameFromOsRelease(text: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("VERSION_CODENAME=") }
            ?.substringAfter('=')
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }

    /** Ubuntu arch name derived from the machine reported by `uname -m`. */
    fun archFromUname(machine: String): String? = when (machine.trim()) {
        "aarch64" -> "arm64"
        "x86_64" -> "amd64"
        "armv7l", "armv8l" -> "armhf"
        else -> null
    }
}
