package com.soundify.app.cleanvideo

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Streaming reader for the 16-bit PCM WAV produced by FFmpeg. Only the header
 * is parsed up front; audio is read chunk by chunk so 20-minute files do not
 * need to fit in RAM.
 */
class WavStreamReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val sampleRate: Int
    val channels: Int
    val totalFrames: Long
    private val dataOffset: Long
    private val bytesPerSample: Int
    private val floatSamples: Boolean

    init {
        val headerSize = min(raf.length(), 64 * 1024L).toInt()
        val header = ByteArray(headerSize)
        raf.readFully(header)
        require(headerSize >= 44 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "Not a RIFF WAV: ${file.name}"
        }
        require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAVE file" }

        var audioFormat = 0
        var ch = 0
        var rate = 0
        var bits = 0
        var offset = 12
        var data = -1L
        var dataSize = 0
        while (offset + 8 <= header.size) {
            val id = String(header, offset, 4, Charsets.US_ASCII)
            val size = intLE(header, offset + 4)
            val body = offset + 8
            if (id == "fmt ") {
                audioFormat = shortLE(header, body).toInt() and 0xffff
                ch = shortLE(header, body + 2).toInt() and 0xffff
                rate = intLE(header, body + 4)
                bits = shortLE(header, body + 14).toInt() and 0xffff
                if (audioFormat == 0xFFFE && size >= 40) {
                    audioFormat = shortLE(header, body + 24).toInt() and 0xffff
                }
            } else if (id == "data") {
                data = body.toLong()
                dataSize = size
                break
            }
            offset = body + size + (size and 1)
        }
        require(data >= 0 && ch in 1..2 && rate > 0) { "Missing fmt/data chunk" }
        require(
            (audioFormat == 1 && bits == 16) || (audioFormat == 3 && bits == 32)
        ) { "Expected 16-bit PCM or 32-bit float, got $audioFormat/$bits" }
        sampleRate = rate
        channels = ch
        bytesPerSample = bits / 8
        floatSamples = audioFormat == 3
        dataOffset = data
        totalFrames = dataSize.toLong() / (channels * bytesPerSample)
    }

    /** Reads up to [count] frames starting at [startFrame]. */
    fun read(startFrame: Long, count: Int): Array<FloatArray> {
        val n = min(count.toLong(), totalFrames - startFrame).toInt().coerceAtLeast(0)
        if (n == 0) return Array(channels) { FloatArray(0) }
        val bytes = ByteArray(n * channels * bytesPerSample)
        raf.seek(dataOffset + startFrame * channels * bytesPerSample)
        raf.readFully(bytes)
        val out = Array(channels) { FloatArray(n) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            for (c in 0 until channels) {
                out[c][i] = if (floatSamples) {
                    buffer.getFloat()
                } else {
                    buffer.getShort() / 32768.0f
                }
            }
        }
        return out
    }

    override fun close() {
        raf.close()
    }

    private fun intLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun shortLE(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)).toShort()
}

/** Streaming 16-bit PCM WAV writer with a patched header at the end. */
class WavStreamWriter(
    file: File,
    private val channels: Int,
    private val sampleRate: Int,
    private val gain: Float = 1f,
) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(header(0))
        raf.seek(44)
    }

    fun writeAll(chunk: Array<FloatArray>) = write(chunk, 0, chunk[0].size)

    fun write(chunk: Array<FloatArray>, offset: Int, count: Int) {
        if (count <= 0) return
        val bytes = ByteArray(count * channels * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            for (c in 0 until channels) {
                val v = ((chunk[c][offset + i] * gain).coerceIn(-1f, 1f) * 32767f).toInt()
                buffer.putShort(v.toShort())
            }
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        raf.seek(4)
        raf.write(intBytes((36 + dataBytes).toInt()))
        raf.seek(40)
        raf.write(intBytes(dataBytes.toInt()))
        raf.close()
    }

    private fun header(dataSize: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + dataSize)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * channels * 2)
        b.putShort((channels * 2).toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataSize)
        return b.array()
    }

    private fun intBytes(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
