package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VideoFingerprintTest {

    private val head = byteArrayOf(1, 2, 3, 4)
    private val tail = byteArrayOf(9, 8, 7)

    @Test
    fun identicalInputProducesTheSameFingerprint() {
        assertEquals(
            VideoFingerprint.of(1_000, head, tail, 12_000),
            VideoFingerprint.of(1_000, head, tail, 12_000),
        )
    }

    @Test
    fun contentSizeAndDurationAllChangeTheFingerprint() {
        val base = VideoFingerprint.of(1_000, head, tail, 12_000)

        assertNotEquals(base, VideoFingerprint.of(1_001, head, tail, 12_000))
        assertNotEquals(base, VideoFingerprint.of(1_000, head + 5, tail, 12_000))
        assertNotEquals(base, VideoFingerprint.of(1_000, head, tail + 6, 12_000))
        assertNotEquals(base, VideoFingerprint.of(1_000, head, tail, 12_001))
    }
}
