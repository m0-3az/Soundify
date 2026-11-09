package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {

    @Test
    fun parsesLoudnormJsonFromFfmpegLog() {
        val log = """
            [Parsed_loudnorm_0 @ 0x1234] 
            {
                "input_i" : "-15.23",
                "input_tp" : "-0.88",
                "input_lra" : "1.40",
                "input_thresh" : "-25.73",
                "output_i" : "-16.00",
                "output_tp" : "-1.50",
                "output_lra" : "1.20",
                "output_thresh" : "-26.50",
                "normalization_type" : "dynamic",
                "target_offset" : "-0.77"
            }
        """.trimIndent()

        val stats = Loudness.parseLoudnormJson(log)!!

        assertEquals(-15.23, stats.inputI, 0.001)
        assertEquals(-0.88, stats.inputTp, 0.001)
        assertEquals(1.40, stats.inputLra, 0.001)
        assertEquals(-25.73, stats.inputThresh, 0.001)
        assertEquals(-0.77, stats.targetOffset, 0.001)
    }

    @Test
    fun returnsNullWhenMeasurementIsIncomplete() {
        assertNull(Loudness.parseLoudnormJson("""{"input_i" : "-15.23"}"""))
        assertNull(Loudness.parseLoudnormJson("not json"))
    }

    @Test
    fun parsesMinusInfinityForSilence() {
        val log = """
            {
                "input_i" : "-inf",
                "input_tp" : "-inf",
                "input_lra" : "0.00",
                "input_thresh" : "-inf",
                "target_offset" : "0.00"
            }
        """.trimIndent()

        val stats = Loudness.parseLoudnormJson(log)!!

        assertEquals(Double.NEGATIVE_INFINITY, stats.inputI, 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, stats.inputTp, 0.0)
        assertTrue(Loudness.isNearSilent(stats))
    }

    @Test
    fun nearSilentThresholdCatchesQuietMixesOnly() {
        val normal = LoudnessStats(
            inputI = -23.0,
            inputTp = -8.0,
            inputLra = 2.0,
            inputThresh = -33.0,
            targetOffset = 0.5,
        )
        val quiet = normal.copy(inputI = -51.0, inputTp = -40.0)
        val peakStarved = normal.copy(inputI = -30.0, inputTp = -50.5)

        assertFalse(Loudness.isNearSilent(normal))
        assertTrue(Loudness.isNearSilent(quiet))
        assertTrue(Loudness.isNearSilent(peakStarved))
    }

    @Test
    fun measuredTargetBoundaryIsNotTreatedAsSilence() {
        val atThreshold = LoudnessStats(
            inputI = -50.0,
            inputTp = -50.0,
            inputLra = 0.0,
            inputThresh = -60.0,
            targetOffset = 0.0,
        )
        assertTrue(Loudness.isNearSilent(atThreshold))
    }
}
