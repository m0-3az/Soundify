package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavStreamTest {

    @Test
    fun readsFloat32Wav() {
        val file = File.createTempFile("soundify-float", ".wav")
        try {
            writeWav(file, format = 3, bits = 32, channels = 2, sampleRate = 44100) { buffer ->
                buffer.putFloat(0.25f)
                buffer.putFloat(-0.5f)
                buffer.putFloat(0.75f)
                buffer.putFloat(-1f)
            }

            WavStreamReader(file).use { reader ->
                assertEquals(2, reader.channels)
                assertEquals(44100, reader.sampleRate)
                assertEquals(2L, reader.totalFrames)
                val samples = reader.read(0, 2)
                assertEquals(0.25f, samples[0][0], 0.0001f)
                assertEquals(0.75f, samples[0][1], 0.0001f)
                assertEquals(-0.5f, samples[1][0], 0.0001f)
                assertEquals(-1f, samples[1][1], 0.0001f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun stillReadsPcm16Wav() {
        val file = File.createTempFile("soundify-pcm16", ".wav")
        try {
            writeWav(file, format = 1, bits = 16, channels = 1, sampleRate = 48000) { buffer ->
                buffer.putShort(16384)
                buffer.putShort(-16384)
            }

            WavStreamReader(file).use { reader ->
                assertEquals(1, reader.channels)
                assertEquals(48000, reader.sampleRate)
                val samples = reader.read(0, 2)
                assertEquals(0.5f, samples[0][0], 0.0001f)
                assertEquals(-0.5f, samples[0][1], 0.0001f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun writerAppliesHeadroomGain() {
        val file = File.createTempFile("soundify-gain", ".wav")
        try {
            WavStreamWriter(file, channels = 2, sampleRate = 44100, gain = 0.5f).use { writer ->
                writer.writeAll(
                    arrayOf(
                        floatArrayOf(1f, -1f),
                        floatArrayOf(0.5f, -0.5f),
                    )
                )
            }

            WavStreamReader(file).use { reader ->
                val samples = reader.read(0, 2)
                assertEquals(0.5f, samples[0][0], 0.0001f)
                assertEquals(-0.5f, samples[0][1], 0.0001f)
                assertEquals(0.25f, samples[1][0], 0.0001f)
                assertEquals(-0.25f, samples[1][1], 0.0001f)
            }
        } finally {
            file.delete()
        }
    }

    private fun writeWav(
        file: File,
        format: Int,
        bits: Int,
        channels: Int,
        sampleRate: Int,
        writeSamples: (ByteBuffer) -> Unit,
    ) {
        val bytesPerSample = bits / 8
        val data = ByteBuffer.allocate(bytesPerSample * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        writeSamples(data)
        val audio = data.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + audio.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(format.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * bytesPerSample)
        header.putShort((channels * bytesPerSample).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(audio.size)
        file.writeBytes(header.array() + audio)
    }
}
