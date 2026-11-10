package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProbeTest {

    private fun stream(
        type: String?,
        codec: String? = null,
        attachedPic: Boolean = false,
    ) = MediaStreamInfo(type = type, codec = codec, attachedPic = attachedPic)

    @Test
    fun audioOnlyFileHasNoVideoTrack() {
        val probe = MediaProbe.classify(
            listOf(stream(type = "audio", codec = "mp3")),
            durationMs = 61_000L,
        )

        assertTrue(probe.hasAudio)
        assertFalse(probe.hasVideo)
        assertNull(probe.videoCodec)
        assertEquals(61_000L, probe.durationMs)
    }

    @Test
    fun realVideoStreamIsReportedWithItsCodec() {
        val probe = MediaProbe.classify(
            listOf(
                stream(type = "audio", codec = "aac"),
                stream(type = "video", codec = "h264"),
            ),
            durationMs = 12_000L,
        )

        assertTrue(probe.hasAudio)
        assertTrue(probe.hasVideo)
        assertEquals("h264", probe.videoCodec)
    }

    @Test
    fun coverArtOnAnAudioFileIsNotVideo() {
        val probe = MediaProbe.classify(
            listOf(
                stream(type = "audio", codec = "flac"),
                stream(type = "video", codec = "mjpeg", attachedPic = true),
            ),
            durationMs = 180_000L,
        )

        assertTrue(probe.hasAudio)
        assertFalse(probe.hasVideo)
        assertNull(probe.videoCodec)
    }

    @Test
    fun coverArtNextToARealVideoKeepsTheRealVideoCodec() {
        val probe = MediaProbe.classify(
            listOf(
                stream(type = "video", codec = "mjpeg", attachedPic = true),
                stream(type = "audio", codec = "aac"),
                stream(type = "video", codec = "hevc"),
            ),
            durationMs = 30_000L,
        )

        assertTrue(probe.hasAudio)
        assertTrue(probe.hasVideo)
        assertEquals("hevc", probe.videoCodec)
    }

    @Test
    fun emptyAndUnknownStreamTypesAreNeitherAudioNorVideo() {
        val empty = MediaProbe.classify(emptyList(), durationMs = 0L)
        val unknown = MediaProbe.classify(
            listOf(
                stream(type = null),
                stream(type = "data", codec = "bin"),
            ),
            durationMs = 1_000L,
        )

        assertFalse(empty.hasAudio)
        assertFalse(empty.hasVideo)
        assertNull(empty.videoCodec)
        assertFalse(unknown.hasAudio)
        assertFalse(unknown.hasVideo)
        assertNull(unknown.videoCodec)
    }
}
