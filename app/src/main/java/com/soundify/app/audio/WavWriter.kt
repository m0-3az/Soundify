package com.soundify.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Writes a 16-bit PCM stereo WAV file. Header sizes are patched on close.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 2
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L

    init {
        require(channels in 1..2) { "Only mono and stereo are supported" }
        writeHeader()
    }

    fun writeInterleaved(samples: FloatArray, frames: Int) {
        val sampleCount = frames * channels
        require(samples.size >= sampleCount)
        val bytes = ByteArray(sampleCount * 2)
        var offset = 0
        for (i in 0 until sampleCount) {
            val value = (samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            bytes[offset++] = (value.toInt() and 0xFF).toByte()
            bytes[offset++] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        try {
            val fileLength = raf.length()
            raf.seek(4)
            raf.write(le32((fileLength - 8).toInt()))
            raf.seek(40)
            raf.write(le32(dataBytes.toInt()))
        } finally {
            raf.close()
        }
    }

    private fun writeHeader() {
        raf.setLength(0)
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.write(le32(0))
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.write(le32(16))
        raf.write(le16(1))
        raf.write(le16(channels))
        raf.write(le32(sampleRate))
        raf.write(le32(byteRate))
        raf.write(le16(blockAlign))
        raf.write(le16(16))
        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.write(le32(0))
    }

    private fun le16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
