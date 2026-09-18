package com.openchat.android.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Pure-JVM WCAG 2.1 contrast proofs for the palette (v0.1.15).
 *
 * The bug being locked out: v0.1.14 and earlier used the BORDER color
 * `outline` (#39414F dark / #C4CAD4 light) as the TEXT color for secondary
 * text in ~50 places — mostly Settings — measuring **1.69:1 (dark) and
 * 1.65:1 (light)** against the surface. Users could not read half of the
 * Settings screens ("banyak teks yang tak terbaca karena warna teksnya
 * mirip latar belakang"). The muted-text role is now `onSurfaceVariant`
 * and every text-bearing role is proven ≥ 4.5:1 (WCAG AA) here, directly
 * from the same hex constants the theme is built from ([PaletteHex]).
 */
class ThemeContrastTest {

    // ------------------------------------------------------------- WCAG math

    private fun srgbToLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAtLeast(name: String, ratio: Double, min: Double) {
        assertTrue(
            "$name contrast %.2f:1 is below the required %.1f:1".format(ratio, min),
            ratio >= min,
        )
    }

    // ------------------------------------------------------------ dark theme

    @Test
    fun `dark body text is AA+ on background and surface`() {
        assertAtLeast(
            "dark onBackground vs background",
            contrast(PaletteHex.DARK_ON_BACKGROUND, PaletteHex.DARK_BACKGROUND),
            7.0,
        )
        assertAtLeast(
            "dark onSurface vs surface",
            contrast(PaletteHex.DARK_ON_SURFACE, PaletteHex.DARK_SURFACE),
            7.0,
        )
    }

    @Test
    fun `dark muted text (onSurfaceVariant) is WCAG AA on its backgrounds`() {
        // Was 1.69:1 with the old outline-as-text bug — now ≥ 4.5:1.
        assertAtLeast(
            "dark onSurfaceVariant vs surface",
            contrast(PaletteHex.DARK_ON_SURFACE_VARIANT, PaletteHex.DARK_SURFACE),
            4.5,
        )
        assertAtLeast(
            "dark onSurfaceVariant vs background",
            contrast(PaletteHex.DARK_ON_SURFACE_VARIANT, PaletteHex.DARK_BACKGROUND),
            4.5,
        )
        assertAtLeast(
            "dark onSurfaceVariant vs surfaceVariant",
            contrast(PaletteHex.DARK_ON_SURFACE_VARIANT, PaletteHex.DARK_SURFACE_VARIANT),
            4.5,
        )
    }

    @Test
    fun `dark primary works as text or link on dark surfaces`() {
        assertAtLeast(
            "dark primary vs surface",
            contrast(PaletteHex.DARK_PRIMARY, PaletteHex.DARK_SURFACE),
            4.5,
        )
        assertAtLeast(
            "dark primary vs background",
            contrast(PaletteHex.DARK_PRIMARY, PaletteHex.DARK_BACKGROUND),
            4.5,
        )
    }

    @Test
    fun `dark error text is readable on its surfaces`() {
        assertAtLeast(
            "dark error vs surface",
            contrast(PaletteHex.DARK_ERROR, PaletteHex.DARK_SURFACE),
            4.5,
        )
    }

    @Test
    fun `dark outline is a decorative stroke, clearly separated from text roles`() {
        // outline is ONLY for borders now — it must NOT be mistaken for a
        // text color, but as a stroke it still needs to be perceptible
        // (≥ 2.5:1 against the surface it is drawn on).
        assertAtLeast(
            "dark outline vs surface",
            contrast(PaletteHex.DARK_OUTLINE, PaletteHex.DARK_SURFACE),
            2.5,
        )
    }

    // ------------------------------------------------------------ light theme

    @Test
    fun `light body text is AA+ on background and surface`() {
        assertAtLeast(
            "light onBackground vs background",
            contrast(PaletteHex.LIGHT_ON_BACKGROUND, PaletteHex.LIGHT_BACKGROUND),
            7.0,
        )
        assertAtLeast(
            "light onSurface vs surface",
            contrast(PaletteHex.LIGHT_ON_SURFACE, PaletteHex.LIGHT_SURFACE),
            7.0,
        )
    }

    @Test
    fun `light muted text (onSurfaceVariant) is WCAG AA on its backgrounds`() {
        // Was 1.65:1 with the old outline-as-text bug — now ≥ 4.5:1.
        assertAtLeast(
            "light onSurfaceVariant vs surface",
            contrast(PaletteHex.LIGHT_ON_SURFACE_VARIANT, PaletteHex.LIGHT_SURFACE),
            4.5,
        )
        assertAtLeast(
            "light onSurfaceVariant vs background",
            contrast(PaletteHex.LIGHT_ON_SURFACE_VARIANT, PaletteHex.LIGHT_BACKGROUND),
            4.5,
        )
        assertAtLeast(
            "light onSurfaceVariant vs surfaceVariant",
            contrast(PaletteHex.LIGHT_ON_SURFACE_VARIANT, PaletteHex.LIGHT_SURFACE_VARIANT),
            4.5,
        )
    }

    @Test
    fun `light primary works as text or link on light surfaces`() {
        assertAtLeast(
            "light primary vs surface",
            contrast(PaletteHex.LIGHT_PRIMARY, PaletteHex.LIGHT_SURFACE),
            4.5,
        )
        assertAtLeast(
            "light primary vs background",
            contrast(PaletteHex.LIGHT_PRIMARY, PaletteHex.LIGHT_BACKGROUND),
            4.5,
        )
    }

    @Test
    fun `light error text is readable on its surfaces`() {
        assertAtLeast(
            "light error vs surface",
            contrast(PaletteHex.LIGHT_ERROR, PaletteHex.LIGHT_SURFACE),
            4.5,
        )
    }

    @Test
    fun `light outline is a decorative stroke, clearly separated from text roles`() {
        assertAtLeast(
            "light outline vs surface",
            contrast(PaletteHex.LIGHT_OUTLINE, PaletteHex.LIGHT_SURFACE),
            2.5,
        )
    }

    @Test
    fun `muted text is strictly dimmer than body text (visual hierarchy)`() {
        // onSurfaceVariant should be visually quieter than onSurface in BOTH
        // themes — but still readable (the AA assertions above).
        assertTrue(
            "dark onSurfaceVariant must be dimmer than dark onSurface",
            luminance(PaletteHex.DARK_ON_SURFACE_VARIANT) < luminance(PaletteHex.DARK_ON_SURFACE),
        )
        assertTrue(
            "light onSurfaceVariant must be brighter than light onSurface",
            luminance(PaletteHex.LIGHT_ON_SURFACE_VARIANT) > luminance(PaletteHex.LIGHT_ON_SURFACE),
        )
    }
}
