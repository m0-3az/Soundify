package com.soundify.app.cleanvideo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ResidualMixerTest {

    private fun input(vararg values: Float) = arrayOf(values.copyOf(), values.copyOf())

    private fun stems(music: FloatArray, voice: FloatArray, sfx: FloatArray) =
        arrayOf(arrayOf(music.copyOf(), music.copyOf()), arrayOf(voice.copyOf(), voice.copyOf()), arrayOf(sfx.copyOf(), sfx.copyOf()))

    @Test
    fun passLeavesTheStemsAlone() {
        val input = input(0.5f, 0.25f)
        val stems = stems(floatArrayOf(0.2f, 0.1f), floatArrayOf(0.2f, 0.1f), floatArrayOf(0.1f, 0.05f))

        ResidualMixer.apply(input, stems, ResidualMode.PASS)

        assertArrayEquals(floatArrayOf(0.2f, 0.1f), stems[0][0], 1e-6f)
        assertArrayEquals(floatArrayOf(0.2f, 0.1f), stems[1][0], 1e-6f)
        assertArrayEquals(floatArrayOf(0.1f, 0.05f), stems[2][0], 1e-6f)
    }

    @Test
    fun musicModePutsTheResidualInMusicAndKeepsCleanUntouched() {
        val input = input(1.0f, 0.5f)
        val stems = stems(
            floatArrayOf(0.2f, 0.2f),
            floatArrayOf(0.3f, 0.2f),
            floatArrayOf(0.1f, 0.1f),
        )

        ResidualMixer.apply(input, stems, ResidualMode.MUSIC)

        // Residuals are 0.4 and 0.0; voice + sfx stays 0.4 and 0.3.
        assertArrayEquals(floatArrayOf(0.6f, 0.2f), stems[0][0], 1e-6f)
        assertEquals(0.4f, stems[1][0][0] + stems[2][0][0], 1e-6f)
        assertEquals(0.3f, stems[1][0][1] + stems[2][0][1], 1e-6f)
    }

    @Test
    fun musicSfxModeSplitsTheResidualEvenly() {
        val input = input(1.0f)
        val stems = stems(floatArrayOf(0.2f), floatArrayOf(0.4f), floatArrayOf(0.1f))

        ResidualMixer.apply(input, stems, ResidualMode.MUSIC_SFX)

        assertEquals(0.35f, stems[0][0][0], 1e-6f)
        assertEquals(0.25f, stems[2][0][0], 1e-6f)
    }

    @Test
    fun channelsAreIndependent() {
        val input = arrayOf(floatArrayOf(1.0f), floatArrayOf(0.0f))
        val stems = stems(floatArrayOf(0.0f), floatArrayOf(0.0f), floatArrayOf(0.0f))

        ResidualMixer.apply(input, stems, ResidualMode.MUSIC)

        assertEquals(1.0f, stems[0][0][0], 1e-6f)
        assertEquals(0.0f, stems[0][1][0], 1e-6f)
    }
}
