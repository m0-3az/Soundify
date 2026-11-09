package com.soundify.app

import com.soundify.app.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @Test
    fun writesValidPcmHeaderAndData() {
        val file = File.createTempFile("soundify-test", ".wav")
        try {
            val writer = WavWriter(file, sampleRate = 44100, channels = 2)
            writer.writeInterleaved(FloatArray(20), frames = 10)
            writer.close()

            val bytes = file.readBytes()
            assertEquals(44 + 40, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))

            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2, header.getShort(22).toInt())
            assertEquals(44100, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(40, header.getInt(40))
            assertEquals(bytes.size - 8, header.getInt(4))
        } finally {
            file.delete()
        }
    }

    @Test
    fun clampsSamples() {
        val file = File.createTempFile("soundify-test", ".wav")
        try {
            val writer = WavWriter(file, sampleRate = 48000, channels = 2)
            writer.writeInterleaved(floatArrayOf(2f, -2f), frames = 1)
            writer.close()

            val bytes = file.readBytes()
            val samples = ByteBuffer.wrap(bytes, 44, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            assertEquals(32767, samples.get(0).toInt())
            assertEquals(-32767, samples.get(1).toInt())
            assertTrue(file.length() > 44)
        } finally {
            file.delete()
        }
    }
}
