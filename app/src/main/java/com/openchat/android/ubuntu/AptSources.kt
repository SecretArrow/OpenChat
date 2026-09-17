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
     * Official mirrors, HTTPS-only.
     *
     * Why HTTPS: real-device reports (v0.1.12) showed `E: The repository …
     * focal … is not signed` while the same rootfs verified fine on CI. The
     * classic cause on mobile/carrier networks is a transparent HTTP proxy or
     * captive portal answering the plain-HTTP mirror requests with its own
     * content — apt then sees unsigned garbage and (correctly) refuses the
     * repository. HTTPS makes tampering impossible and turns such networks
     * into an honest connection error instead of a misleading "not signed".
     * apt >= 1.6 ships the https transport natively (focal has 2.0) and the
     * installer provisions a CA bundle into the rootfs when missing
     * ([UbuntuInstaller.restoreAptTrust]), so https works from the very first
     * `apt-get update`.
     */
    const val ARCHIVE_BASE: String = "https://archive.ubuntu.com/ubuntu"
    const val PORTS_BASE: String = "https://ports.ubuntu.com/ubuntu-ports"

    /** The plain-HTTP mirror URIs that older app versions wrote into rootfs. */
    private const val LEGACY_ARCHIVE_HTTP = "http://archive.ubuntu.com/ubuntu"
    private const val LEGACY_PORTS_HTTP = "http://ports.ubuntu.com/ubuntu-ports"

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
     * base ships one, classic sources.list otherwise. Always HTTPS (see the
     * mirror constants above).
     */
    fun writeFor(aptDir: File, arch: String, codename: String): Result<Unit> = runCatching {
        val base = if (arch == "amd64") ARCHIVE_BASE else PORTS_BASE
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

    /**
     * Rewrites plain-HTTP official mirror URIs to their HTTPS form, in place,
     * across `sources.list`, every `.list`/`.sources` fragment under
     * `sources.list.d` and the deb822 `ubuntu.sources` file.
     *
     * Rootfs installed by older app versions (v0.1.12 and earlier) carry
     * `http://archive.ubuntu.com` / `http://ports.ubuntu.com` lines; on
     * networks with a transparent proxy this is what produces the
     * `repository … is not signed` failure. This runs before every
     * `apt-get update` ([UbuntuRuntime.runAptUpdate]) and during install,
     * repair and import — idempotent, and never touches third-party URIs.
     *
     * @return true when at least one file was rewritten (a log-worthy event),
     *   false when everything was already HTTPS (or nothing matched).
     */
    fun upgradeToHttps(aptDir: File): Boolean {
        if (!aptDir.isDirectory) return false
        var changed = false
        val classic = File(aptDir, "sources.list")
        if (classic.isFile && rewriteHttps(classic)) changed = true
        val fragments = File(aptDir, "sources.list.d")
        fragments.listFiles { f -> f.isFile && (f.name.endsWith(".list") || f.name.endsWith(".sources")) }
            ?.forEach { f -> if (rewriteHttps(f)) changed = true }
        return changed
    }

    /** Replaces legacy HTTP mirror URIs in [file]; true when content changed. */
    private fun rewriteHttps(file: File): Boolean {
        val original = runCatching { file.readText() }.getOrNull() ?: return false
        if (LEGACY_ARCHIVE_HTTP !in original && LEGACY_PORTS_HTTP !in original) return false
        val upgraded = original
            .replace(LEGACY_ARCHIVE_HTTP, ARCHIVE_BASE)
            .replace(LEGACY_PORTS_HTTP, PORTS_BASE)
        return runCatching {
            file.writeText(upgraded)
            true
        }.getOrDefault(false)
    }
}
