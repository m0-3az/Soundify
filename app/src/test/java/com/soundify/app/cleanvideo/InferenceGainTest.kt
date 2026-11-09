package com.soundify.app.cleanvideo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class InferenceGainTest {

    @Test
    fun loudSourceIsAttenuatedToTheTrainingLevel() {
        // -14 LUFS is 13 dB above the model's -27 LUFS training level.
        assertEquals(0.2239f, InferenceGain.gainFor(-14.0), 0.0005f)
    }

    @Test
    fun quietSourceIsBoostedToTheTrainingLevel() {
        assertEquals(4.4668f, InferenceGain.gainFor(-40.0), 0.001f)
    }

    @Test
    fun sourceAlreadyAtTheTrainingLevelIsUntouched() {
        assertEquals(1f, InferenceGain.gainFor(-27.0), 0.0001f)
    }

    @Test
    fun missingMeasurementFallsBackToTheSourceLevel() {
        assertEquals(1f, InferenceGain.gainFor(null), 0.0001f)
        assertEquals(1f, InferenceGain.gainFor(Double.NaN), 0.0001f)
        assertEquals(1f, InferenceGain.gainFor(Double.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test
    fun nearSilentSourceIsNotAmplified() {
        assertEquals(1f, InferenceGain.gainFor(Loudness.NEAR_SILENT_LUFS), 0.0001f)
        assertEquals(1f, InferenceGain.gainFor(-60.0), 0.0001f)
    }

    @Test
    fun gainIsBoundedSoAMeasurementCannotExplode() {
        assertEquals(InferenceGain.MAX_GAIN, InferenceGain.gainFor(-49.0), 0.0001f)
        assertEquals(InferenceGain.MIN_GAIN, InferenceGain.gainFor(20.0), 0.0001f)
    }

    @Test
    fun restoringTheGainCancelsIt() {
        val original = arrayOf(
            floatArrayOf(0.5f, -0.25f, 0.125f),
            floatArrayOf(-0.5f, 0.25f, -0.125f),
        )
        val samples = original.map { it.copyOf() }.toTypedArray()
        val gain = InferenceGain.gainFor(-14.0)

        InferenceGain.scaleInPlace(samples, gain)
        assertEquals(0.5f * gain, samples[0][0], 1e-6f)

        InferenceGain.scaleInPlace(samples, 1f / gain)
        assertArrayEquals(original[0], samples[0], 1e-6f)
        assertArrayEquals(original[1], samples[1], 1e-6f)
    }
}
