package com.example.rjlmulticomsg_proclientportal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MulticomRed,
    onPrimary = Color.White,
    secondary = AccentOrange,
    onSecondary = Color.White,
    tertiary = OpenGreen,
    background = NavyDeep,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    surfaceVariant = BorderDark,
    onSurfaceVariant = TextMuted,
    error = FailRed
)

private val LightColorScheme = lightColorScheme(
    primary = MulticomRed,
    onPrimary = Color.White,
    secondary = AccentOrange,
    onSecondary = Color.White,
    tertiary = OpenGreen,
    background = BgLight,
    onBackground = TextDark,
    surface = SurfaceLight,
    onSurface = TextDark,
    surfaceVariant = Color(0xFFF5F7FA),
    onSurfaceVariant = TextMuted,
    error = FailRed
)

@Composable
fun RJLMulticomSGPROCLIENTPORTALTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Client portal defaults to light home / dark login handled per-screen. */
    useDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (useDark || darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
