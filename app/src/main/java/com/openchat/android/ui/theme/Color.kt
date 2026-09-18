package com.openchat.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Raw ARGB palette values (v0.1.15): kept as Long constants so the JVM unit
 * test [ThemeContrastTest] can prove the WCAG contrast of every TEXT role
 * without Android or Compose on the classpath. Colors below are built from
 * these — never duplicate a literal in both places.
 *
 * Contrast report (WCAG 2.1, computed over surface/background):
 *  - onBackground / onSurface           ≥ 13.3:1  (body text)
 *  - onSurfaceVariant (muted text)      ≥ 6.8:1   (secondary text, ≥ 4.5 AA)
 *  - primary (as text / links)          ≥ 5.3:1
 *  - outline (1dp strokes, NOT text)    ≥ 2.5:1   (decorative borders)
 *
 * History (v0.1.14 and earlier): `outline` (#39414F dark / #C4CAD4 light,
 * a border color) was used as the TEXT color for secondary text in ~50
 * places — mostly Settings — measuring 1.69:1 dark / 1.65:1 light against
 * the surface. Users reported "many texts in Settings are unreadable, the
 * text color is almost the background". The muted-text role is now
 * onSurfaceVariant (6.8:1 / 7.3:1) and outline is back to strokes only.
 */
object PaletteHex {
    // Dark palette
    const val DARK_BACKGROUND: Long = 0xFF0F1115
    const val DARK_SURFACE: Long = 0xFF171A21
    const val DARK_SURFACE_VARIANT: Long = 0xFF1F242E
    const val DARK_PRIMARY: Long = 0xFF5B8DEF
    const val DARK_ON_PRIMARY: Long = 0xFF0B1526
    const val DARK_SECONDARY: Long = 0xFF7BD88F
    const val DARK_TERTIARY: Long = 0xFFE8B65B
    const val DARK_ERROR: Long = 0xFFEF6B6B
    const val DARK_ON_BACKGROUND: Long = 0xFFE6E9EF
    const val DARK_ON_SURFACE: Long = 0xFFDDE2EA
    const val DARK_ON_SURFACE_VARIANT: Long = 0xFFA6AEBD
    const val DARK_OUTLINE: Long = 0xFF4D5667

    // Light palette
    const val LIGHT_BACKGROUND: Long = 0xFFFAFBFD
    const val LIGHT_SURFACE: Long = 0xFFFFFFFF
    const val LIGHT_SURFACE_VARIANT: Long = 0xFFEDF0F5
    const val LIGHT_PRIMARY: Long = 0xFF2D5FBF
    const val LIGHT_ON_PRIMARY: Long = 0xFFFFFFFF
    const val LIGHT_SECONDARY: Long = 0xFF1F7A3D
    const val LIGHT_TERTIARY: Long = 0xFF9A6A15
    const val LIGHT_ERROR: Long = 0xFFB3261E
    const val LIGHT_ON_BACKGROUND: Long = 0xFF171A21
    const val LIGHT_ON_SURFACE: Long = 0xFF1A1E26
    const val LIGHT_ON_SURFACE_VARIANT: Long = 0xFF4E5765
    const val LIGHT_OUTLINE: Long = 0xFF98A1AE
}

// Dark palette (default)
val DarkBackground = Color(PaletteHex.DARK_BACKGROUND)
val DarkSurface = Color(PaletteHex.DARK_SURFACE)
val DarkSurfaceVariant = Color(PaletteHex.DARK_SURFACE_VARIANT)
val DarkPrimary = Color(PaletteHex.DARK_PRIMARY)
val DarkOnPrimary = Color(PaletteHex.DARK_ON_PRIMARY)
val DarkSecondary = Color(PaletteHex.DARK_SECONDARY)
val DarkTertiary = Color(PaletteHex.DARK_TERTIARY)
val DarkError = Color(PaletteHex.DARK_ERROR)
val DarkOnBackground = Color(PaletteHex.DARK_ON_BACKGROUND)
val DarkOnSurface = Color(PaletteHex.DARK_ON_SURFACE)
val DarkOnSurfaceVariant = Color(PaletteHex.DARK_ON_SURFACE_VARIANT)
val DarkOutline = Color(PaletteHex.DARK_OUTLINE)

// Light palette
val LightBackground = Color(PaletteHex.LIGHT_BACKGROUND)
val LightSurface = Color(PaletteHex.LIGHT_SURFACE)
val LightSurfaceVariant = Color(PaletteHex.LIGHT_SURFACE_VARIANT)
val LightPrimary = Color(PaletteHex.LIGHT_PRIMARY)
val LightOnPrimary = Color(PaletteHex.LIGHT_ON_PRIMARY)
val LightSecondary = Color(PaletteHex.LIGHT_SECONDARY)
val LightTertiary = Color(PaletteHex.LIGHT_TERTIARY)
val LightError = Color(PaletteHex.LIGHT_ERROR)
val LightOnBackground = Color(PaletteHex.LIGHT_ON_BACKGROUND)
val LightOnSurface = Color(PaletteHex.LIGHT_ON_SURFACE)
val LightOnSurfaceVariant = Color(PaletteHex.LIGHT_ON_SURFACE_VARIANT)
val LightOutline = Color(PaletteHex.LIGHT_OUTLINE)

// Terminal palette
object TermPalette {
    val dark = mapOf(
        0 to Color(0xFF15171C), 1 to Color(0xFFCD3131), 2 to Color(0xFF0DBC79),
        3 to Color(0xFFE5E512), 4 to Color(0xFF2472C8), 5 to Color(0xFFBC3FBC),
        6 to Color(0xFF11A8CD), 7 to Color(0xFFE5E7EB),
        8 to Color(0xFF666666), 9 to Color(0xFFF14C4C), 10 to Color(0xFF23D18B),
        11 to Color(0xFFF5F543), 12 to Color(0xFF3B8EEA), 13 to Color(0xFFD670D6),
        14 to Color(0xFF29B8DB), 15 to Color(0xFFFFFFFF),
    )
    val light = mapOf(
        0 to Color(0xFF3B3B3B), 1 to Color(0xFFC41A16), 2 to Color(0xFF007400),
        3 to Color(0xFFBF8100), 4 to Color(0xFF0057AE), 5 to Color(0xFFA90D91),
        6 to Color(0xFF00919D), 7 to Color(0xFFBBBBBB),
        8 to Color(0xFF555555), 9 to Color(0xFFE5635D), 10 to Color(0xFF00BB00),
        11 to Color(0xFFE5C500), 12 to Color(0xFF0086D2), 13 to Color(0xFFD33DB3),
        14 to Color(0xFF00BBCC), 15 to Color(0xFF464646),
    )
}
