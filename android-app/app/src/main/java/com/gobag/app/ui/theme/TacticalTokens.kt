package com.gobag.app.ui.theme

import androidx.compose.ui.graphics.Color

data class TacticalPalette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val error: Color,
    val success: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val onPrimary: Color,
    val outline: Color
)

object TacticalColorTokens {
    val dark = TacticalPalette(
        background = Color(0xFF131314),
        surface = Color(0xFF1B1B1C),
        surfaceHigh = Color(0xFF232325),
        surfaceHighest = Color(0xFF2D2D2F),
        primary = Color(0xFFFF6B00),
        secondary = Color(0xFFFFB693),
        tertiary = Color(0xFF8CCDFF),
        error = Color(0xFFB3261E),
        success = Color(0xFF2ECC71),
        textPrimary = Color(0xFFF6F6F6),
        textMuted = Color(0xFFB7B8BD),
        onPrimary = Color(0xFFFFFFFF),
        outline = Color(0xFF5A4136)
    )

    val light = TacticalPalette(
        background = Color(0xFFF8F9FA),
        surface = Color(0xFFFFFFFF),
        surfaceHigh = Color(0xFFF0F2F5),
        surfaceHighest = Color(0xFFE7EAEE),
        primary = Color(0xFFFF6B00),
        secondary = Color(0xFFFFB693),
        tertiary = Color(0xFF0B6B9C),
        error = Color(0xFFB3261E),
        success = Color(0xFF1F9D55),
        textPrimary = Color(0xFF191C1D),
        textMuted = Color(0xFF69737B),
        onPrimary = Color(0xFFFFFFFF),
        outline = Color(0xFFD1D6DA)
    )
}
