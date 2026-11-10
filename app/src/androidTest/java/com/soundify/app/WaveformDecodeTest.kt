package com.soundify.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.cleanvideo.WavStreamReader
import com.soundify.app.editor.Waveform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class WaveformDecodeTest {

    @Test
    fun decodesTwoSecondWavThenServesTheCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "waveform-decode-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(root, "tone.wav")
        val peaksFile = File(root, "peaks.bin")
        try {
            writeTone(source)
            val actualDurationMs = WavStreamReader(source).use { reader ->
                (reader.totalFrames * 1_000.0 / reader.sampleRate).roundToLong()
            }

            val first = Waveform.decode(context, source, peaksFile, actualDurationMs)

            assertNotNull(first)
            first!!
            assertTrue(first.peaks.isNotEmpty())
            assertEquals(Waveform.DEFAULT_BUCKETS, first.buckets)
            assertEquals(actualDurationMs, first.durationMs)
            assertTrue(
                "expected about 2000 ms, got $actualDurationMs",
                abs(actualDurationMs - 2_000L) <= 50L,
            )
            assertTrue(first.peaks.maxOrNull()!! > 0.4f)
            assertTrue(first.peaks.minOrNull()!! < -0.4f)
            assertTrue(peaksFile.isFile)
            assertTrue("cache file must not be empty", peaksFile.length() > 0L)
            assertFalse(File(root, "peaks.bin.tmp").exists())
            assertTrue(tempWavs(context).isEmpty())

            assertTrue("source must be deleted", source.delete())
            assertFalse("source must be absent for the cache hit", source.exists())
            val second = Waveform.decode(context, source, peaksFile, actualDurationMs)

            assertNotNull(second)
            assertEquals(actualDurationMs, second!!.durationMs)
            assertArrayEquals(first.peaks, second.peaks, 0f)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeTone(file: File) {
        val sampleRate = 44_100
        val frames = sampleRate * 2
        WavWriter(file, sampleRate = sampleRate, channels = 1).use { writer ->
            val samples = FloatArray(frames) { index ->
                (sin(2.0 * PI * 440.0 * index / sampleRate) * 0.5).toFloat()
            }
            writer.writeInterleaved(samples, frames)
        }
    }

    private fun tempWavs(context: Context): List<File> =
        context.cacheDir.listFiles { file -> file.isFile && file.name.startsWith("waveform-") }
            .orEmpty()
            .toList()
}
