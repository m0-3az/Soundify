package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkRangesTest {

    @Test
    fun lastChunkStopsThePlanWhenItIsLongerThanTheHop() {
        // 10.381 s at 44.1 kHz: the final chunk starts at 5 s and is 5.381 s long.
        // The old loop advanced by the hop after a last chunk and scheduled a
        // bogus third chunk, which then failed with "missing overlap tail".
        val ranges = chunkRanges(totalFrames = 457_802, chunk = 264_600, hop = 220_500)

        assertEquals(2, ranges.size)
        assertEquals(0L, ranges[0].start)
        assertEquals(264_600, ranges[0].count)
        assertTrue(ranges[0].isFirst)
        assertFalse(ranges[0].isLast)

        assertEquals(220_500L, ranges[1].start)
        assertEquals(237_302, ranges[1].count)
        assertFalse(ranges[1].isFirst)
        assertTrue(ranges[1].isLast)
    }

    @Test
    fun planEndsAtTheFileEndForAnExactChunk() {
        val ranges = chunkRanges(totalFrames = 264_600, chunk = 264_600, hop = 220_500)

        assertEquals(1, ranges.size)
        assertEquals(0L, ranges[0].start)
        assertEquals(264_600, ranges[0].count)
        assertTrue(ranges[0].isFirst)
        assertTrue(ranges[0].isLast)
    }

    @Test
    fun shortFileIsASingleFullRange() {
        val ranges = chunkRanges(totalFrames = 1_000, chunk = 264_600, hop = 220_500)

        assertEquals(1, ranges.size)
        assertEquals(1_000, ranges[0].count)
        assertTrue(ranges[0].isFirst)
        assertTrue(ranges[0].isLast)
    }

    @Test
    fun rangesAdvanceByTheHopAndTheLastOneReachesTheEnd() {
        val total = 537_633L
        val ranges = chunkRanges(totalFrames = total, chunk = 264_600, hop = 220_500)

        assertEquals(3, ranges.size)
        assertEquals(listOf(0L, 220_500L, 441_000L), ranges.map { it.start })
        assertEquals(total, ranges.last().start + ranges.last().count)
        assertTrue(ranges.last().isLast)
        assertTrue(ranges.dropLast(1).none { it.isLast })
    }

    @Test
    fun emptyFileHasNoRanges() {
        assertTrue(chunkRanges(totalFrames = 0, chunk = 264_600, hop = 220_500).isEmpty())
    }
}
