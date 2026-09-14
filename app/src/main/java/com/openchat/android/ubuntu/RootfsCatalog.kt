package com.openchat.android.ubuntu

/**
 * One pinned Ubuntu base rootfs variant (REAL, verified SHA-256 values — no
 * mirrors, no "latest" links, spec §3 and §32).
 */
data class RootfsVariant(
    val codename: String,
    val ubuntuVersion: String,
    val url: String,
    val sha256: String,
)

/**
 * Pinned catalog of Ubuntu base rootfs tarballs per architecture
 * (https://cdimage.ubuntu.com/ubuntu-base/releases/).
 *
 * DEFAULT = focal (20.04): the DEFAULT install picks the focal variant for
 * the device ABI. Jammy (22.04, glibc 2.35) and newer bases spawn threads
 * through clone3(2); Android's per-app seccomp allowlist on kernels without
 * native clone3 (i.e. most devices/emulators below kernel 5.5) answers the
 * syscall with SIGSYS, which kills apt-get instantly and SILENTLY — the
 * shell reports it as exit 159 (128+31). Focal's glibc 2.31 predates clone3
 * entirely, so apt/Node/python all work on the same kernels. jammy + noble
 * stay pinned here for the advanced `rootfsUrlOverride` path and for the day
 * proot grows a clone3-safe syscall policy.
 *
 * Pure Kotlin — unit-tested (RootfsCatalogTest).
 */
object RootfsCatalog {

    /** Codename installed by [UbuntuRuntime.install] today. */
    const val DEFAULT_CODENAME: String = "focal"

    // focal 20.04.5 — SHA-256 values verified from cdimage.ubuntu.com SHA256SUMS.
    const val FOCAL_ARM64_SHA256: String = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
    const val FOCAL_ARMHF_SHA256: String = "6bcbfa7f603d79d368d40e138dad98938907d2fb0d6416521417cf8702c2f5de"
    const val FOCAL_AMD64_SHA256: String = "60e216b60947653dc8989be3821380268315b99b2959c29882781541bfe5a426"
    const val JAMMY_ARM64_SHA256: String = "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f"
    const val JAMMY_ARMHF_SHA256: String = "fd77cb0659326b75c08ce06b6b8649d2e13ef9a704a8e9212fec32cb97d42add"
    const val JAMMY_AMD64_SHA256: String = "242cd8898b33ea806ef5f13b1076ed7c76f9f989d18384452f7166692438ff1a"
    const val NOBLE_ARM64_SHA256: String = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    const val NOBLE_ARMHF_SHA256: String = "4fcee4d278f1c5232e085a021a85e4c6cef3853557a88d98ff380b5e5d5841bb"
    const val NOBLE_AMD64_SHA256: String = "e77b6f10c2590cef872b33ee9f635a0e3fd1f57fb074c0e52b5c7f56147a0c86"

    private const val BASE_URL_20_04: String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-"
    private const val BASE_URL_22_04: String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-"
    private const val BASE_URL_24_04: String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-"

    /** All pinned variants: focal + jammy + noble × arm64 + armhf + amd64. */
    val variants: List<RootfsVariant> = listOf(
        RootfsVariant("focal", "20.04", "${BASE_URL_20_04}arm64.tar.gz", FOCAL_ARM64_SHA256),
        RootfsVariant("focal", "20.04", "${BASE_URL_20_04}armhf.tar.gz", FOCAL_ARMHF_SHA256),
        RootfsVariant("focal", "20.04", "${BASE_URL_20_04}amd64.tar.gz", FOCAL_AMD64_SHA256),
        RootfsVariant("jammy", "22.04", "${BASE_URL_22_04}arm64.tar.gz", JAMMY_ARM64_SHA256),
        RootfsVariant("jammy", "22.04", "${BASE_URL_22_04}armhf.tar.gz", JAMMY_ARMHF_SHA256),
        RootfsVariant("jammy", "22.04", "${BASE_URL_22_04}amd64.tar.gz", JAMMY_AMD64_SHA256),
        RootfsVariant("noble", "24.04", "${BASE_URL_24_04}arm64.tar.gz", NOBLE_ARM64_SHA256),
        RootfsVariant("noble", "24.04", "${BASE_URL_24_04}armhf.tar.gz", NOBLE_ARMHF_SHA256),
        RootfsVariant("noble", "24.04", "${BASE_URL_24_04}amd64.tar.gz", NOBLE_AMD64_SHA256),
    )

    /**
     * Maps a device ABI to the Ubuntu architecture name.
     * @throws IllegalArgumentException for unsupported ABIs.
     */
    fun ubuntuArchForAbi(abi: String): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "amd64"
        else -> throw IllegalArgumentException(
            "Unsupported ABI '$abi' — this APK supports arm64-v8a, armeabi-v7a and x86_64 only",
        )
    }

    /**
     * The default (focal) rootfs variant for the given device ABI.
     * @throws IllegalArgumentException for unsupported ABIs.
     */
    fun forAbi(abi: String): RootfsVariant {
        val arch = ubuntuArchForAbi(abi)
        return variants.firstOrNull {
            it.codename == DEFAULT_CODENAME && it.url.endsWith("-base-$arch.tar.gz")
        } ?: throw IllegalArgumentException(
            "No ${DEFAULT_CODENAME} rootfs variant pinned for ABI '$abi' (arch '$arch')",
        )
    }
}
