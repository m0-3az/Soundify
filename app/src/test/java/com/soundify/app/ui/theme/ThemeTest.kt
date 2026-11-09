package com.soundify.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ThemeTest {

    @Test
    fun systemModeFollowsTheSystemFlag() {
        val darkSystem = soundifyColorScheme(ThemeMode.SYSTEM, ThemeAccent.MINT, systemDark = true)
        val dark = soundifyColorScheme(ThemeMode.DARK, ThemeAccent.MINT, systemDark = false)
        assertEquals(dark.background, darkSystem.background)

        val lightSystem = soundifyColorScheme(ThemeMode.SYSTEM, ThemeAccent.MINT, systemDark = false)
        val light = soundifyColorScheme(ThemeMode.LIGHT, ThemeAccent.MINT, systemDark = true)
        assertEquals(light.background, lightSystem.background)
    }

    @Test
    fun amoledUsesBlackSurfaces() {
        val scheme = soundifyColorScheme(ThemeMode.AMOLED, ThemeAccent.BLUE, systemDark = false)
        assertEquals(Color.Black, scheme.background)
        assertEquals(Color.Black, scheme.surface)
    }

    @Test
    fun everyAccentHasReadablePrimaryText() {
        for (accent in ThemeAccent.entries) {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED)) {
                val scheme = soundifyColorScheme(mode, accent, systemDark = false)
                val ratio = contrast(scheme.primary, scheme.onPrimary)
                assertTrue("$accent / $mode contrast was $ratio", ratio >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }
}
