package com.soundify.app.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class WaveformPeaksTest {

    private fun tempDir(): File = createTempDirectory("waveform").toFile()

    private fun writeCacheBytes(
        file: File,
        magic: String = "SNDP",
        version: Int = 1,
        buckets: Int,
        durationMs: Long = 2_000L,
        peaks: FloatArray = FloatArray(buckets * 2),
    ) {
        val buffer = ByteBuffer.allocate(20 + peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic.toByteArray(Charsets.US_ASCII))
        buffer.putInt(version)
        buffer.putInt(buckets)
        buffer.putLong(durationMs)
        peaks.forEach { buffer.putFloat(it) }
        file.writeBytes(buffer.array())
    }

    @Test
    fun emptyInputReturnsZeroPeaks() {
        val peaks = Waveform.buildPeaks(ShortArray(0), buckets = 4)

        assertArrayEquals(FloatArray(8), peaks, 0f)
    }

    @Test
    fun allZeroSamplesReturnZeroPeaks() {
        val peaks = Waveform.buildPeaks(ShortArray(1_000), buckets = 10)

        assertEquals(20, peaks.size)
        assertArrayEquals(FloatArray(20), peaks, 0f)
    }

    @Test
    fun sineBucketsMatchKnownExtremes() {
        val sampleCount = 800
        val samples = ShortArray(sampleCount) { index ->
            (sin(2.0 * PI * index / sampleCount) * 32767.0).roundToInt().toShort()
        }

        val peaks = Waveform.buildPeaks(samples, buckets = 8)

        assertEquals(16, peaks.size)
        val bucketSize = sampleCount / 8
        for (bucket in 0 until 8) {
            val slice = samples.copyOfRange(bucket * bucketSize, (bucket + 1) * bucketSize)
            assertEquals(
                "bucket $bucket minimum",
                slice.minOrNull()!!.toFloat() / 32768f,
                peaks[2 * bucket],
                1e-6f,
            )
            assertEquals(
                "bucket $bucket maximum",
                slice.maxOrNull()!!.toFloat() / 32768f,
                peaks[2 * bucket + 1],
                1e-6f,
            )
        }
        assertEquals(32767f / 32768f, peaks[2 * 2 + 1], 1e-6f)
        assertEquals(-32767f / 32768f, peaks[2 * 6], 1e-6f)
    }

    @Test
    fun fullScaleSamplesUseTheFullRange() {
        val peaks = Waveform.buildPeaks(
            shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE),
            buckets = 2,
        )

        assertArrayEquals(
            floatArrayOf(-1f, -1f, 32767f / 32768f, 32767f / 32768f),
            peaks,
            0f,
        )
    }

    @Test
    fun oneSampleInputProducesOneBucket() {
        val peaks = Waveform.buildPeaks(shortArrayOf(16_384), buckets = 2_000)

        assertArrayEquals(floatArrayOf(0.5f, 0.5f), peaks, 0f)
    }

    @Test
    fun bucketCountIsCappedBySampleCount() {
        val peaks = Waveform.buildPeaks(
            shortArrayOf(1_000, -1_000, 2_000),
            buckets = 2_000,
        )

        assertEquals(6, peaks.size)
        assertEquals(1_000f / 32768f, peaks[0], 0f)
        assertEquals(1_000f / 32768f, peaks[1], 0f)
        assertEquals(-1_000f / 32768f, peaks[2], 0f)
        assertEquals(-1_000f / 32768f, peaks[3], 0f)
        assertEquals(2_000f / 32768f, peaks[4], 0f)
        assertEquals(2_000f / 32768f, peaks[5], 0f)
    }

    @Test
    fun defaultBucketCountIsTwoThousand() {
        val peaks = Waveform.buildPeaks(ShortArray(4_000))

        assertEquals(4_000, peaks.size)
    }

    @Test
    fun cacheRoundTripsPeaksAndDuration() {
        val dir = tempDir()
        try {
            val file = File(dir, "peaks.bin")
            val peaks = floatArrayOf(-1f, 0.25f, 0f, 0.5f)

            Waveform.writeCache(file, peaks, durationMs = 2_345L)

            assertTrue(file.isFile)
            assertFalse(File(dir, "peaks.bin.tmp").exists())
            val data = Waveform.readCache(file)
            assertNotNull(data)
            assertEquals(2_345L, data!!.durationMs)
            assertArrayEquals(peaks, data.peaks, 0f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingCacheReturnsNull() {
        val dir = tempDir()
        try {
            assertNull(Waveform.readCache(File(dir, "absent.bin")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cacheWithWrongMagicIsRejected() {
        val dir = tempDir()
        try {
            val file = File(dir, "peaks.bin")
            writeCacheBytes(file, magic = "SNDX", buckets = 2)

            assertNull(Waveform.readCache(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cacheWithWrongVersionIsRejected() {
        val dir = tempDir()
        try {
            val file = File(dir, "peaks.bin")
            writeCacheBytes(file, version = 2, buckets = 2)

            assertNull(Waveform.readCache(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun truncatedCacheIsRejected() {
        val dir = tempDir()
        try {
            val file = File(dir, "peaks.bin")
            writeCacheBytes(file, buckets = 2)
            val bytes = file.readBytes()
            file.writeBytes(bytes.copyOf(bytes.size - 4))

            assertNull(Waveform.readCache(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cacheWithOnlyAHeaderIsRejected() {
        val dir = tempDir()
        try {
            val file = File(dir, "peaks.bin")
            file.writeBytes("SNDP".toByteArray(Charsets.US_ASCII))

            assertNull(Waveform.readCache(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun impossibleBucketCountsAreRejected() {
        val dir = tempDir()
        try {
            listOf(0, -1, Int.MAX_VALUE).forEachIndexed { index, buckets ->
                val file = File(dir, "peaks-$index.bin")
                writeCacheBytes(file, buckets = buckets, peaks = FloatArray(0))

                assertNull("buckets=$buckets", Waveform.readCache(file))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
