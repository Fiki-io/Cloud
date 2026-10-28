package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = TextPrimary,
    secondary = SurfaceInteractive,
    onSecondary = TextPrimary,
    tertiary = SurfaceInteractive,
    background = BgPrimary,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    outline = BorderSubtle
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
