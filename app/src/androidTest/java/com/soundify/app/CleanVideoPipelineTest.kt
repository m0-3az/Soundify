package com.soundify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.CleanVideoExporter
import com.soundify.app.cleanvideo.StreamingCleaner
import com.soundify.app.cleanvideo.VideoCompatibility
import com.soundify.app.cleanvideo.Wav
import com.soundify.app.cleanvideo.WavData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    fun separatesShortVideo() = run("clip5.mp4")

    @Test
    fun separatesLongerVideo() = run("clip60.mp4")

    @Test
    fun cancellingDoesNotWriteFinalOutput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "cleanvideo_cancel.mp4")
        instrumentation.context.assets.open("cleanvideo/clip5.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val exports = File(context.filesDir, "exports/cleanvideo")
        val before = exports.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val job = launch(Dispatchers.Default) {
            StreamingCleaner(context).clean(Uri.fromFile(video), "clip5.mp4") { _, _ -> }
        }
        delay(750)
        job.cancelAndJoin()

        val created = (exports.listFiles()?.map { it.name }?.toSet() ?: emptySet()) - before
        val finished = created.filter { name ->
            File(exports, name).listFiles()?.any { it.name == "clean.wav" } == true
        }
        assertTrue("cancelled run wrote a final output: $finished", finished.isEmpty())
        created.forEach { File(exports, it).deleteRecursively() }
        video.delete()
        Unit
    }

    private fun run(asset: String) {
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

        assertTrue("voice wav missing", result.voice.file.length() > 100_000)
        assertTrue("sfx wav missing", result.sfx.file.length() > 100_000)
        assertTrue("music wav missing", result.music.file.length() > 100_000)
        assertTrue("clean wav missing", result.clean.file.length() > 100_000)

        val inputWav = File(context.cacheDir, "input_$asset.wav")
        val mp3 = File(context.cacheDir, "export_$asset.mp3")
        val videoOut = File(
            context.cacheDir,
            "export_$asset.${VideoCompatibility.default(result.videoCodec).extension}",
        )
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
                "$asset elapsed=${elapsed}ms clean=${result.clean.file.length()} " +
                    "voice=${result.voice.file.length()} sfx=${result.sfx.file.length()} " +
                    "music=${result.music.file.length()} inputRms=${"%.2f".format(inputRms)} " +
                    "cleanedRms=${"%.2f".format(cleanedRms)} peak=${"%.3f".format(cleanedPeak)}",
            )
            assertTrue(
                "cleaned mix lost too much level (input ${"%.1f".format(inputRms)} dBFS, " +
                    "cleaned ${"%.1f".format(cleanedRms)} dBFS)",
                abs(inputRms - cleanedRms) < 8.0,
            )
            assertTrue("cleaned mix is clipping", cleanedPeak < 0.999f)

            val exporter = CleanVideoExporter(context)
            exporter.encodeAudio(result.clean, AudioFormat.MP3, mp3)
            assertTrue("MP3 export missing", mp3.length() > 10_000)
            val format = VideoCompatibility.default(result.videoCodec)
            exporter.encodeVideo(video, result.clean, format, videoOut)
            assertTrue("video export missing", videoOut.length() > 10_000)
        } finally {
            inputWav.delete()
            mp3.delete()
            videoOut.delete()
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
