package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CleanVideoExporterTest {

    @Test
    fun h264PrefersMp4AndDoesNotOfferWebm() {
        val formats = VideoCompatibility.ordered("h264")

        assertEquals(VideoFormat.MP4, formats.first())
        assertTrue(formats.contains(VideoFormat.MKV))
        assertFalse(formats.contains(VideoFormat.WEBM))
    }

    @Test
    fun vp9PrefersMkvAndOffersWebm() {
        val formats = VideoCompatibility.ordered("vp9")

        assertEquals(VideoFormat.MKV, formats.first())
        assertTrue(formats.contains(VideoFormat.WEBM))
        assertFalse(formats.contains(VideoFormat.MP4))
    }

    @Test
    fun unknownCodecFallsBackToMkv() {
        assertEquals(VideoFormat.MKV, VideoCompatibility.default(null))
    }

    @Test
    fun writerStatsMatchStreamingAnalysis() {
        val file = File.createTempFile("soundify-stats", ".wav")
        try {
            val writer = WavStreamWriter(file, channels = 1, sampleRate = 44100)
            writer.writeAll(arrayOf(floatArrayOf(1f, -1f, 0.5f, -0.5f)))
            val written = writer.stats()
            writer.close()

            val analyzed = Wav.analyze(file)
            assertEquals(written.rmsDb, analyzed.rmsDb, 0.01f)
            assertEquals(written.peakDb, analyzed.peakDb, 0.01f)
            assertEquals(-2.04f, analyzed.rmsDb, 0.1f)
            assertEquals(0f, analyzed.peakDb, 0.01f)
        } finally {
            file.delete()
        }
    }
}
