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
    fun mpeg2VideoNeverRecommendsMp4() {
        val formats = VideoCompatibility.ordered("mpeg2video")

        assertEquals(VideoFormat.MKV, formats.first())
        assertFalse(formats.contains(VideoFormat.MP4))
        assertTrue(formats.all { VideoCompatibility.supports(it, "mpeg2video") })
    }

    @Test
    fun theoraOnlyOffersMatroska() {
        assertEquals(VideoFormat.MKV, VideoCompatibility.default("theora"))
        assertEquals(listOf(VideoFormat.MKV), VideoCompatibility.ordered("theora"))
    }

    @Test
    fun everyRecommendedFormatIsSupported() {
        val codecs = listOf(
            null,
            "h264",
            "hevc",
            "av1",
            "mpeg4",
            "mpeg2video",
            "theora",
            "vp8",
            "vp9",
            "prores",
        )
        for (codec in codecs) {
            val ordered = VideoCompatibility.ordered(codec)
            assertEquals("default must be first for $codec", VideoCompatibility.default(codec), ordered.first())
            assertTrue("ordered() contained an unsupported format for $codec", ordered.all {
                VideoCompatibility.supports(it, codec)
            })
            assertTrue(
                "default() returned an unsupported format for $codec",
                VideoCompatibility.supports(VideoCompatibility.default(codec), codec),
            )
        }
    }

    @Test
    fun nonCleanStemsDisplayTheirRestoredSaveLevel() {
        val voice = StemResult(CleanStem.VOICE, File("voice.wav"), 1_000L, -24f, -6f)
        val clean = StemResult(CleanStem.CLEAN, File("clean.wav"), 1_000L, -16f, -1.5f)

        assertEquals(6f, voice.exportGainDb, 0.001f)
        assertEquals(-18f, voice.displayRmsDb, 0.001f)
        assertEquals(0f, voice.displayPeakDb, 0.001f)
        assertEquals(0f, clean.exportGainDb, 0.001f)
        assertEquals(-16f, clean.displayRmsDb, 0.001f)
        assertEquals(-1.5f, clean.displayPeakDb, 0.001f)
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
