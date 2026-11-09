package com.soundify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.soundify.app.cleanvideo.StreamingCleaner
import com.soundify.app.cleanvideo.Wav
import com.soundify.app.cleanvideo.WavData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class CleanVideoPipelineTest {
    @Test
    fun separatesShortVideo() = run("clip5.mp4", 10_000)

    @Test
    fun separatesLongerVideo() = run("clip60.mp4", 60_000)

    private fun run(asset: String, minimumVideoBytes: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "cleanvideo_$asset")
        instrumentation.context.assets.open("cleanvideo/$asset").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }

        val started = System.currentTimeMillis()
        val result = runBlocking {
            StreamingCleaner(context).clean(Uri.fromFile(video), asset) { _, _ -> }
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("cleaned wav missing", result.cleanedWav.length() > 100_000)
        assertTrue("mp3 missing", result.mp3.length() > 10_000)
        assertTrue("m4a missing", result.m4a.length() > 10_000)
        assertNotNull("video output missing", result.video)
        assertTrue("video output empty", result.video!!.length() > minimumVideoBytes)

        val inputWav = File(context.cacheDir, "input_$asset.wav")
        try {
            val session = FFmpegKit.execute(
                "-y -i ${video.absolutePath} -vn -ac 2 -ar 44100 -c:a pcm_s16le ${inputWav.absolutePath}"
            )
            assertTrue("could not decode input audio", ReturnCode.isSuccess(session.returnCode))
            val input = Wav.read(inputWav)
            val cleaned = Wav.read(result.cleanedWav)
            val inputRms = rmsDb(input)
            val cleanedRms = rmsDb(cleaned)
            val cleanedPeak = peak(cleaned)
            android.util.Log.i(
                "CleanVideoTest",
                "$asset elapsed=${elapsed}ms wav=${result.cleanedWav.length()} " +
                    "mp3=${result.mp3.length()} m4a=${result.m4a.length()} " +
                    "video=${result.video!!.length()} inputRms=${"%.2f".format(inputRms)} " +
                    "cleanedRms=${"%.2f".format(cleanedRms)} peak=${"%.3f".format(cleanedPeak)}",
            )
            assertTrue(
                "cleaned mix lost too much level (input ${"%.1f".format(inputRms)} dBFS, " +
                    "cleaned ${"%.1f".format(cleanedRms)} dBFS)",
                abs(inputRms - cleanedRms) < 8.0,
            )
            assertTrue("cleaned mix is clipping", cleanedPeak < 0.999f)
        } finally {
            inputWav.delete()
        }
    }

    private fun rmsDb(wav: WavData): Double {
        var sum = 0.0
        var count = 0L
        for (channel in wav.samples) {
            for (sample in channel) {
                sum += sample.toDouble() * sample
                count++
            }
        }
        return 20.0 * log10(sqrt(sum / count).coerceAtLeast(1e-12))
    }

    private fun peak(wav: WavData): Float =
        wav.samples.maxOf { channel -> channel.maxOf { kotlin.math.abs(it) } }
}
