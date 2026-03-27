package com.gobag.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TacticalDarkColorScheme = darkColorScheme(
    primary = TacticalColorTokens.dark.primary,
    onPrimary = TacticalColorTokens.dark.onPrimary,
    secondary = TacticalColorTokens.dark.secondary,
    onSecondary = TacticalColorTokens.dark.textPrimary,
    tertiary = TacticalColorTokens.dark.tertiary,
    onTertiary = TacticalColorTokens.dark.textPrimary,
    background = TacticalColorTokens.dark.background,
    onBackground = TacticalColorTokens.dark.textPrimary,
    surface = TacticalColorTokens.dark.surface,
    onSurface = TacticalColorTokens.dark.textPrimary,
    surfaceVariant = TacticalColorTokens.dark.surfaceHighest,
    onSurfaceVariant = TacticalColorTokens.dark.textMuted,
    error = TacticalColorTokens.dark.error,
    onError = TacticalColorTokens.dark.textPrimary,
    outline = TacticalColorTokens.dark.outline
)

private val TacticalLightColorScheme = lightColorScheme(
    primary = TacticalColorTokens.light.primary,
    onPrimary = TacticalColorTokens.light.onPrimary,
    secondary = TacticalColorTokens.light.secondary,
    onSecondary = TacticalColorTokens.light.textPrimary,
    tertiary = TacticalColorTokens.light.tertiary,
    onTertiary = TacticalColorTokens.light.onPrimary,
    background = TacticalColorTokens.light.background,
    onBackground = TacticalColorTokens.light.textPrimary,
    surface = TacticalColorTokens.light.surface,
    onSurface = TacticalColorTokens.light.textPrimary,
    surfaceVariant = TacticalColorTokens.light.surfaceHighest,
    onSurfaceVariant = TacticalColorTokens.light.textMuted,
    error = TacticalColorTokens.light.error,
    onError = TacticalColorTokens.light.onPrimary,
    outline = TacticalColorTokens.light.outline
)

@Composable
fun TacticalTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TacticalDarkColorScheme else TacticalLightColorScheme,
        content = content
    )
}
