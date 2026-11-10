package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorPlaybackQueueTest {

    @Test
    fun singleItemQueueStopsAtTheOnlyItem() {
        val queue = PlaybackQueue(listOf(item("one")), startIndex = 0)

        assertEquals(0, queue.index)
        assertEquals("one", queue.current.clip.id)
        assertFalse(queue.advance())
        assertEquals(0, queue.index)
    }

    @Test
    fun queueAdvancesInOrderAndStopsAtTheLastItem() {
        val queue = PlaybackQueue(
            listOf(item("one"), item("two"), item("three")),
            startIndex = 0,
        )

        assertTrue(queue.advance())
        assertEquals(1, queue.index)
        assertEquals("two", queue.current.clip.id)

        assertTrue(queue.advance())
        assertEquals(2, queue.index)
        assertEquals("three", queue.current.clip.id)

        assertFalse("the queue must not loop", queue.advance())
        assertEquals(2, queue.index)
        assertFalse(queue.advance())
        assertEquals(2, queue.index)
    }

    @Test
    fun startIndexIsCoercedIntoTheQueueBounds() {
        val items = listOf(item("one"), item("two"))

        assertEquals(0, PlaybackQueue(items, startIndex = -4).index)
        assertEquals(1, PlaybackQueue(items, startIndex = 9).index)
        assertEquals("two", PlaybackQueue(items, startIndex = 9).current.clip.id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyQueueIsRejected() {
        PlaybackQueue(emptyList(), startIndex = 0)
    }

    @Test
    fun previewGainIsLinearAndCappedAtUnity() {
        assertEquals(1f, previewGain(0f), 0f)
        assertEquals(0.5f, previewGain(-6.0206f), 1e-3f)
        assertEquals(0.1f, previewGain(-20f), 1e-4f)
        assertEquals("a boost must not make preview louder than unity", 1f, previewGain(6f), 0f)
        assertEquals("a boost must not make preview louder than unity", 1f, previewGain(24f), 0f)
        assertEquals(0f, previewGain(Float.NEGATIVE_INFINITY), 0f)
        assertEquals("a non-finite gain falls back to unity", 1f, previewGain(Float.NaN), 0f)
    }

    @Test
    fun pitchRatioUsesTwelveToneEqualTemperament() {
        assertEquals(1f, pitchRatio(0), 0f)
        assertEquals(2f, pitchRatio(12), 1e-5f)
        assertEquals(0.5f, pitchRatio(-12), 1e-5f)
        assertEquals(1.4983f, pitchRatio(7), 1e-3f)
    }

    private fun item(id: String): PlaybackItem = PlaybackItem(
        clip = EditorClip(
            id = id,
            sourceHash = "hash-$id",
            sourceFile = "sources/$id.wav",
            displayName = "$id.wav",
            sourceDurationMs = 2_000L,
            sourceSampleRate = 44_100,
            sourceChannels = 2,
            trimStartMs = 0L,
            trimEndMs = 2_000L,
        ),
        file = File("$id.wav"),
    )
}
