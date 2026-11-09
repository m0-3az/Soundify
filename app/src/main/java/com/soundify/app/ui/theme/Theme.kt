package com.soundify.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED"),
}

enum class ThemeAccent(val label: String, val swatch: Color) {
    MINT("Mint", Color(0xFF2ECC71)),
    BLUE("Blue", Color(0xFF4A9EFF)),
    VIOLET("Violet", Color(0xFFB388FF)),
    AMBER("Amber", Color(0xFFFFB74D)),
}

private data class AccentPalette(
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightContainer: Color,
    val lightOnContainer: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkContainer: Color,
    val darkOnContainer: Color,
)

private fun palette(accent: ThemeAccent): AccentPalette = when (accent) {
    ThemeAccent.MINT -> AccentPalette(
        lightPrimary = Color(0xFF006D3A),
        lightOnPrimary = Color.White,
        lightContainer = Color(0xFF8FF7B7),
        lightOnContainer = Color(0xFF002110),
        darkPrimary = Color(0xFF7CFFB2),
        darkOnPrimary = Color(0xFF00391D),
        darkContainer = Color(0xFF00522C),
        darkOnContainer = Color(0xFF9BFFC8),
    )

    ThemeAccent.BLUE -> AccentPalette(
        lightPrimary = Color(0xFF0061A4),
        lightOnPrimary = Color.White,
        lightContainer = Color(0xFFD1E4FF),
        lightOnContainer = Color(0xFF001D36),
        darkPrimary = Color(0xFF9ECAFF),
        darkOnPrimary = Color(0xFF003258),
        darkContainer = Color(0xFF00497D),
        darkOnContainer = Color(0xFFD1E4FF),
    )

    ThemeAccent.VIOLET -> AccentPalette(
        lightPrimary = Color(0xFF6750A4),
        lightOnPrimary = Color.White,
        lightContainer = Color(0xFFEADDFF),
        lightOnContainer = Color(0xFF21005D),
        darkPrimary = Color(0xFFD0BCFF),
        darkOnPrimary = Color(0xFF381E72),
        darkContainer = Color(0xFF4F378B),
        darkOnContainer = Color(0xFFEADDFF),
    )

    ThemeAccent.AMBER -> AccentPalette(
        lightPrimary = Color(0xFF7A5900),
        lightOnPrimary = Color.White,
        lightContainer = Color(0xFFFFDEA3),
        lightOnContainer = Color(0xFF261A00),
        darkPrimary = Color(0xFFFFD18A),
        darkOnPrimary = Color(0xFF412D00),
        darkContainer = Color(0xFF5E4200),
        darkOnContainer = Color(0xFFFFDEA3),
    )
}

/** Non-composable so the palette can be unit tested. */
fun soundifyColorScheme(
    mode: ThemeMode,
    accent: ThemeAccent,
    systemDark: Boolean,
): ColorScheme {
    val palette = palette(accent)
    val dark = mode == ThemeMode.DARK ||
        mode == ThemeMode.AMOLED ||
        (mode == ThemeMode.SYSTEM && systemDark)
    return if (dark) {
        darkScheme(palette, amoled = mode == ThemeMode.AMOLED)
    } else {
        lightScheme(palette)
    }
}

private fun darkScheme(palette: AccentPalette, amoled: Boolean): ColorScheme {
    val background = if (amoled) Color.Black else Color(0xFF101014)
    val surface = if (amoled) Color.Black else Color(0xFF17171C)
    val variant = if (amoled) Color(0xFF141414) else Color(0xFF24242B)
    return darkColorScheme(
        primary = palette.darkPrimary,
        onPrimary = palette.darkOnPrimary,
        primaryContainer = palette.darkContainer,
        onPrimaryContainer = palette.darkOnContainer,
        secondary = palette.darkPrimary,
        onSecondary = palette.darkOnPrimary,
        secondaryContainer = palette.darkContainer,
        onSecondaryContainer = palette.darkOnContainer,
        background = background,
        onBackground = Color(0xFFE5E1E6),
        surface = surface,
        onSurface = Color(0xFFE5E1E6),
        surfaceVariant = variant,
        onSurfaceVariant = Color(0xFFC4C7C5),
        outline = if (amoled) Color(0xFF5A5A5A) else Color(0xFF8E918F),
        surfaceContainerLowest = if (amoled) Color.Black else Color(0xFF0D0D11),
        surfaceContainerLow = if (amoled) Color(0xFF0A0A0A) else Color(0xFF131318),
        surfaceContainer = if (amoled) Color(0xFF121212) else Color(0xFF1B1B21),
        surfaceContainerHigh = if (amoled) Color(0xFF1A1A1A) else Color(0xFF24242B),
        surfaceContainerHighest = if (amoled) Color(0xFF222222) else Color(0xFF2E2E36),
    )
}

private fun lightScheme(palette: AccentPalette): ColorScheme = lightColorScheme(
    primary = palette.lightPrimary,
    onPrimary = palette.lightOnPrimary,
    primaryContainer = palette.lightContainer,
    onPrimaryContainer = palette.lightOnContainer,
    secondary = palette.lightPrimary,
    onSecondary = palette.lightOnPrimary,
    secondaryContainer = palette.lightContainer,
    onSecondaryContainer = palette.lightOnContainer,
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
)

@Composable
fun SoundifyTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    accent: ThemeAccent = ThemeAccent.MINT,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = mode == ThemeMode.DARK ||
        mode == ThemeMode.AMOLED ||
        (mode == ThemeMode.SYSTEM && systemDark)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = soundifyColorScheme(mode, accent, systemDark),
        content = content,
    )
}
