package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
