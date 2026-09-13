package com.openchat.android.ui.theme

import androidx.compose.ui.graphics.Color

// Dark palette (default)
val DarkBackground = Color(0xFF0F1115)
val DarkSurface = Color(0xFF171A21)
val DarkSurfaceVariant = Color(0xFF1F242E)
val DarkPrimary = Color(0xFF5B8DEF)
val DarkOnPrimary = Color(0xFF0B1526)
val DarkSecondary = Color(0xFF7BD88F)
val DarkTertiary = Color(0xFFE8B65B)
val DarkError = Color(0xFFEF6B6B)
val DarkOnBackground = Color(0xFFE6E9EF)
val DarkOnSurface = Color(0xFFDDE2EA)
val DarkOutline = Color(0xFF39414F)

// Light palette
val LightBackground = Color(0xFFFAFBFD)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEDF0F5)
val LightPrimary = Color(0xFF2D5FBF)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFF1F7A3D)
val LightTertiary = Color(0xFF9A6A15)
val LightError = Color(0xFFB3261E)
val LightOnBackground = Color(0xFF171A21)
val LightOnSurface = Color(0xFF1A1E26)
val LightOutline = Color(0xFFC4CAD4)

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
