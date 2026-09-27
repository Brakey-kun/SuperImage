package com.supervideo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = black,
    onPrimary = white,
    primaryContainer = white,
    onPrimaryContainer = black,
    secondary = black,
    onSecondary = white,
    secondaryContainer = white,
    onSecondaryContainer = black,
    tertiary = black,
    onTertiary = white,
    tertiaryContainer = white,
    onTertiaryContainer = black,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = black,
    background = white,
    onBackground = black,
    surface = white,
    onSurface = black,
    surfaceVariant = white,
    onSurfaceVariant = black,
    inverseSurface = black,
    inverseOnSurface = white,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = white,
    outlineVariant = md_theme_light_outlineVariant,
    scrim = md_theme_light_scrim,
    surfaceBright = white,
    surfaceDim = white,
    surfaceContainer = white,
    surfaceContainerLow = white,
    surfaceContainerLowest = white,
    surfaceContainerHigh = white,
    surfaceContainerHighest = white,
)

private val DarkColors = darkColorScheme(
    primary = white,
    onPrimary = black,
    primaryContainer = black,
    onPrimaryContainer = white,
    secondary = white,
    onSecondary = black,
    secondaryContainer = black,
    onSecondaryContainer = white,
    tertiary = white,
    onTertiary = black,
    tertiaryContainer = black,
    onTertiaryContainer = white,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = white,
    background = black,
    onBackground = white,
    surface = black,
    onSurface = white,
    surfaceVariant = black,
    onSurfaceVariant = white,
    inverseSurface = white,
    inverseOnSurface = black,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = black,
    outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
    surfaceBright = black,
    surfaceDim = black,
    surfaceContainer = black,
    surfaceContainerLow = black,
    surfaceContainerLowest = black,
    surfaceContainerHigh = black,
    surfaceContainerHighest = black,
)

@Composable
fun MonoTheme(
    lightMode: Boolean = !isSystemInDarkTheme(),
    systemBars: @Composable (lightMode: Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    systemBars(lightMode)
    MaterialTheme(
        colorScheme = if (lightMode) LightColors else DarkColors,
        shapes = Shapes,
        content = content,
        typography = Typography
    )
}
