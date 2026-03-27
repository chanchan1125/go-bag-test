package com.gobag.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TacticalDarkColorScheme = darkColorScheme(
    primary = TacticalColorTokens.primary,
    onPrimary = TacticalColorTokens.onPrimary,
    background = TacticalColorTokens.background,
    onBackground = TacticalColorTokens.textPrimary,
    surface = TacticalColorTokens.surface,
    onSurface = TacticalColorTokens.textPrimary,
    surfaceVariant = TacticalColorTokens.surfaceHighest,
    onSurfaceVariant = TacticalColorTokens.textMuted,
    error = TacticalColorTokens.error,
    onError = Color.Black,
    secondary = TacticalColorTokens.secondary,
    onSecondary = Color.Black,
    tertiary = TacticalColorTokens.tertiary,
    onTertiary = Color.Black
)

@Composable
fun TacticalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TacticalDarkColorScheme,
        content = content
    )
}
