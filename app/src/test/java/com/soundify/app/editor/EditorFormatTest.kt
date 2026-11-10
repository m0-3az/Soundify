package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorFormatTest {

    @Test
    fun entriesCarryTheLabelExtensionAndMime() {
        assertEquals(
            listOf("WAV", "FLAC", "MP3", "M4A"),
            EditorFormat.entries.map { it.name },
        )
        assertEquals("WAV (lossless)", EditorFormat.WAV.label)
        assertEquals("wav", EditorFormat.WAV.extension)
        assertEquals("audio/wav", EditorFormat.WAV.mime)
        assertEquals("FLAC (lossless)", EditorFormat.FLAC.label)
        assertEquals("flac", EditorFormat.FLAC.extension)
        assertEquals("audio/flac", EditorFormat.FLAC.mime)
        assertEquals("MP3 192 kbps", EditorFormat.MP3.label)
        assertEquals("mp3", EditorFormat.MP3.extension)
        assertEquals("audio/mpeg", EditorFormat.MP3.mime)
        assertEquals("M4A AAC 192 kbps", EditorFormat.M4A.label)
        assertEquals("m4a", EditorFormat.M4A.extension)
        assertEquals("audio/mp4", EditorFormat.M4A.mime)
    }

    @Test
    fun wavEstimateIsCdQualityPlusTenPercent() {
        // 44_100 Hz * 2 channels * 2 bytes * 2 s = 352_800, plus 10%.
        assertEquals(388_080L, EditorFormat.WAV.estimatedBytes(2_000L))
    }

    @Test
    fun flacEstimateIsSixtyPercentOfWavPlusTenPercent() {
        // 352_800 * 0.6 = 211_680, plus 10%.
        assertEquals(232_848L, EditorFormat.FLAC.estimatedBytes(2_000L))
    }

    @Test
    fun compressedEstimatesUseThe192KbpsBitrate() {
        // 192_000 / 8 * 2 s = 48_000, plus 10%.
        assertEquals(52_800L, EditorFormat.MP3.estimatedBytes(2_000L))
        assertEquals(52_800L, EditorFormat.M4A.estimatedBytes(2_000L))
    }

    @Test
    fun estimatesRoundPartialBytesUp() {
        assertEquals(195L, EditorFormat.WAV.estimatedBytes(1L))
        assertEquals(117L, EditorFormat.FLAC.estimatedBytes(1L))
        assertEquals(27L, EditorFormat.MP3.estimatedBytes(1L))
        assertEquals(27L, EditorFormat.M4A.estimatedBytes(1L))
    }

    @Test
    fun nonPositiveDurationsEstimateNothing() {
        assertEquals(0L, EditorFormat.WAV.estimatedBytes(0L))
        assertEquals(0L, EditorFormat.M4A.estimatedBytes(-5_000L))
    }
}
