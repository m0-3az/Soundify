package com.soundify.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CFFB2),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF00522C),
    onPrimaryContainer = Color(0xFF9BFFC8),
    secondary = Color(0xFFB0CCB9),
    onSecondary = Color(0xFF1C3527),
    background = Color(0xFF101014),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF24242B),
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = Color(0xFF8E918F)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006D3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8FF7B7),
    onPrimaryContainer = Color(0xFF002110),
    secondary = Color(0xFF4F6353),
    onSecondary = Color.White,
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717971)
)

@Composable
fun SoundifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
