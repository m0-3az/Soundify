package com.soundify.app.cleanvideo

import kotlin.math.min

/**
 * One chunk of a streamed separation. [start] is in frames at 44.1 kHz.
 * Neighbouring ranges overlap by `chunk - hop`; the final range always ends
 * exactly at the file end.
 */
internal data class ChunkRange(
    val start: Long,
    val count: Int,
    val isFirst: Boolean,
    val isLast: Boolean,
)

/**
 * Plans the chunks for a file of [totalFrames].
 *
 * Stops after the chunk that reaches the end. The previous loop advanced by
 * [hop] even after a final chunk, which scheduled a bogus extra chunk and
 * failed with "missing overlap tail" whenever the last chunk was longer than
 * the hop (e.g. a 10.381 s file).
 */
internal fun chunkRanges(totalFrames: Long, chunk: Int, hop: Int): List<ChunkRange> {
    require(chunk > 0) { "chunk must be positive" }
    require(hop > 0) { "hop must be positive" }
    require(hop <= chunk) { "hop must not exceed the chunk" }
    if (totalFrames <= 0L) return emptyList()

    val ranges = ArrayList<ChunkRange>()
    var start = 0L
    while (start < totalFrames) {
        val count = min(chunk.toLong(), totalFrames - start).toInt()
        val isLast = start + count >= totalFrames
        ranges += ChunkRange(start, count, isFirst = start == 0L, isLast = isLast)
        if (isLast) break
        start += hop
    }
    return ranges
}
