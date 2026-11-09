package com.soundify.app.cleanvideo

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

data class LevelStats(
    val rmsDb: Float,
    val peakDb: Float,
)

fun levelDb(value: Double): Float =
    (20.0 * log10(value.coerceAtLeast(1e-6))).toFloat()

data class WavData(
    val sampleRate: Int,
    val channels: Int,
    val samples: Array<FloatArray>,
)

object Wav {
    /** Streams a WAV and returns its RMS and sample peak without loading it all. */
    fun analyze(file: File): LevelStats = WavStreamReader(file).use { reader ->
        var sumSquares = 0.0
        var peak = 0f
        var count = 0L
        val chunkFrames = 1 shl 20
        var start = 0L
        while (start < reader.totalFrames) {
            val countHere = min(chunkFrames.toLong(), reader.totalFrames - start).toInt()
            val channels = reader.read(start, countHere)
            for (channel in channels) {
                for (sample in channel) {
                    sumSquares += sample.toDouble() * sample
                    val value = abs(sample)
                    if (value > peak) peak = value
                    count++
                }
            }
            start += countHere
        }
        val rms = if (count == 0L) 0.0 else sqrt(sumSquares / count)
        LevelStats(levelDb(rms), levelDb(peak.toDouble()))
    }

    fun read(file: File): WavData {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "file too small" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not RIFF" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "not WAVE" }

        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = intLE(bytes, offset + 4)
            val body = offset + 8
            if (id == "fmt ") {
                audioFormat = shortLE(bytes, body).toInt() and 0xffff
                channels = shortLE(bytes, body + 2).toInt() and 0xffff
                sampleRate = intLE(bytes, body + 4)
                bits = shortLE(bytes, body + 14).toInt() and 0xffff
                if (audioFormat == 0xFFFE && size >= 40) {
                    audioFormat = shortLE(bytes, body + 24).toInt() and 0xffff
                }
            } else if (id == "data") {
                dataOffset = body
                dataSize = size
                break
            }
            offset = body + size + (size and 1)
        }
        require(dataOffset >= 0 && channels > 0 && sampleRate > 0) { "missing fmt/data" }
        val bytesPerSample = bits / 8
        require(bytesPerSample in 2..4) { "unsupported bits: $bits" }
        val frameBytes = channels * bytesPerSample
        val frames = dataSize / frameBytes
        val out = Array(channels) { FloatArray(frames) }

        for (frame in 0 until frames) {
            val base = dataOffset + frame * frameBytes
            for (ch in 0 until channels) {
                val p = base + ch * bytesPerSample
                val value = when {
                    audioFormat == 3 && bits == 32 -> Float.fromBits(intLE(bytes, p))
                    audioFormat == 1 && bits == 16 ->
                        shortLE(bytes, p) / 32768.0f
                    audioFormat == 1 && bits == 24 -> {
                        val v = (bytes[p].toInt() and 0xff) or
                            ((bytes[p + 1].toInt() and 0xff) shl 8) or
                            ((bytes[p + 2].toInt() and 0xff) shl 16)
                        val signed = if (v and 0x800000 != 0) v or 0xff000000.toInt() else v
                        signed / 8388608.0f
                    }
                    audioFormat == 1 && bits == 32 ->
                        intLE(bytes, p) / 2147483648.0f
                    else -> throw IllegalArgumentException("unsupported WAV $audioFormat/$bits")
                }
                out[ch][frame] = value
            }
        }
        return WavData(sampleRate, channels, out)
    }

    fun writeFloat(file: File, samples: Array<FloatArray>, sampleRate: Int) {
        val channels = samples.size
        val frames = samples[0].size
        val dataSize = frames * channels * 4
        val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            for (ch in 0 until channels) buf.putFloat(samples[ch][i])
        }
        writeFile(file, buf.array(), channels, sampleRate, 3, 32)
    }

    fun writePcm16(file: File, samples: Array<FloatArray>, sampleRate: Int) {
        val channels = samples.size
        val frames = samples[0].size
        val dataSize = frames * channels * 2
        val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            for (ch in 0 until channels) {
                val v = (samples[ch][i].coerceIn(-1f, 1f) * 32767f).toInt()
                buf.putShort(v.toShort())
            }
        }
        writeFile(file, buf.array(), channels, sampleRate, 1, 16)
    }

    private fun writeFile(
        file: File,
        data: ByteArray,
        channels: Int,
        sampleRate: Int,
        audioFormat: Int,
        bits: Int,
    ) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(audioFormat.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bits / 8)
        header.putShort((channels * bits / 8).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(data.size)
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(header.array())
            out.write(data)
        }
    }

    private fun intLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun shortLE(b: ByteArray, off: Int): Short =
        (((b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8))).toShort()
}
