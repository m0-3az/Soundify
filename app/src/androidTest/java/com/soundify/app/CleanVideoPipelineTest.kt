package com.soundify.app

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.CleanStage
import com.soundify.app.cleanvideo.CleanVideoExporter
import com.soundify.app.cleanvideo.Loudness
import com.soundify.app.cleanvideo.StreamingCleaner
import com.soundify.app.cleanvideo.VideoCompatibility
import com.soundify.app.cleanvideo.VideoFormat
import com.soundify.app.cleanvideo.Wav
import com.soundify.app.cleanvideo.WavData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun videoWithoutAudioFailsClearly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val withAudio = copyAsset(instrumentation, "cleanvideo/clip5.mp4", "cleanvideo_noaudio_src.mp4")
        val silent = File(context.cacheDir, "cleanvideo_noaudio.mp4")
        val strip = FFmpegKit.execute(
            "-y -i ${withAudio.absolutePath} -map 0:v:0 -c:v copy -an ${silent.absolutePath}"
        )
        assertTrue("could not create a video without audio", ReturnCode.isSuccess(strip.returnCode))

        val exports = File(context.filesDir, "exports/cleanvideo")
        val before = exportNames(exports)
        try {
            val error = runCatching {
                StreamingCleaner(context).clean(Uri.fromFile(silent), "noaudio.mp4") { }
            }.exceptionOrNull()

            assertTrue("expected a clear no-audio failure, got $error", error is IllegalStateException)
            assertEquals("This video has no audio track.", error?.message)
            assertEquals("a failed run left a stem directory", before, exportNames(exports))
        } finally {
            withAudio.delete()
            silent.delete()
            deleteNewExports(exports, before)
        }
    }

    @Test
    fun cancellingRemovesPartialStems() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = copyAsset(instrumentation, "cleanvideo/clip5.mp4", "cleanvideo_cancel.mp4")
        val exports = File(context.filesDir, "exports/cleanvideo")
        val before = exportNames(exports)
        val firstSeparatingChunk = CompletableDeferred<Unit>()

        try {
            val job = launch(Dispatchers.Default) {
                StreamingCleaner(context).clean(Uri.fromFile(video), "clip5.mp4") { progress ->
                    if (progress.stage == CleanStage.SEPARATING) {
                        firstSeparatingChunk.complete(Unit)
                    }
                }
            }
            withTimeout(60_000) { firstSeparatingChunk.await() }
            job.cancelAndJoin()

            val created = exportNames(exports) - before
            assertTrue("cancelled run left stem directories: $created", created.isEmpty())
            val leftovers = created
                .flatMap { name -> File(exports, name).listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile }
            assertTrue("cancelled run left partial stem files: $leftovers", leftovers.isEmpty())
        } finally {
            video.delete()
            deleteNewExports(exports, before)
        }
    }

    private fun run(asset: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = copyAsset(instrumentation, "cleanvideo/$asset", "cleanvideo_$asset")
        val exports = File(context.filesDir, "exports/cleanvideo")
        val before = exportNames(exports)
        val progressDetails = mutableListOf<String>()
        var stemDir: File? = null

        val inputWav = File(context.cacheDir, "input_$asset.wav")
        val mp3 = File(context.cacheDir, "export_$asset.mp3")
        val voiceWav = File(context.cacheDir, "export_${asset}_voice.wav")
        val videoDefault = File(context.cacheDir, "export_$asset.default.mp4")
        val videoAlternate = File(context.cacheDir, "export_$asset.alternate.mkv")
        val contentVideo = File(context.cacheDir, "export_$asset.content.mp4")
        val providerSource = File(context.filesDir, "exports/test-source-$asset")

        try {
            val started = System.currentTimeMillis()
            val result = runBlocking {
                StreamingCleaner(context).clean(Uri.fromFile(video), asset) { progress ->
                    progressDetails += progress.detail
                }
            }
            stemDir = result.clean.file.parentFile
            val elapsed = System.currentTimeMillis() - started

            assertTrue("voice wav missing", result.voice.file.length() > 100_000)
            assertTrue("sfx wav missing", result.sfx.file.length() > 100_000)
            assertTrue("music wav missing", result.music.file.length() > 100_000)
            assertTrue("clean wav missing", result.clean.file.length() > 100_000)

            assertProgressOrder(progressDetails)

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

            val loudness = measureLoudness(result.cleanedWav)
            assertTrue("clean mix should have been normalized for $asset", result.cleanNote == null)
            assertTrue(
                "clean mix integrated loudness was ${loudness.inputI}, expected -16 ±1 LU",
                abs(loudness.inputI + 16.0) <= 1.0,
            )
            assertTrue(
                "clean mix true peak was ${loudness.inputTp} dBTP, expected <= -1.5",
                loudness.inputTp <= -1.4,
            )

            val exporter = CleanVideoExporter(context)

            exporter.encodeAudio(result.clean, AudioFormat.MP3, mp3)
            assertTrue("MP3 export missing", mp3.length() > 10_000)

            // Non-clean stems restore +6 dB on export; verify the samples moved.
            exporter.encodeAudio(result.voice, AudioFormat.WAV, voiceWav)
            val exportedVoice = Wav.read(voiceWav)
            val exportedVoiceRms = rmsDb(exportedVoice)
            val expectedVoiceRms = result.voice.rmsDb + 6.0
            assertTrue(
                "voice export did not restore the save gain " +
                    "(stem $result.voice.rmsDb dBFS, exported $exportedVoiceRms dBFS)",
                abs(exportedVoiceRms - expectedVoiceRms) < 1.0,
            )
            assertTrue(
                "voice export exceeded the -1 dBFS limiter: ${peak(exportedVoice)}",
                peak(exportedVoice) <= 0.892f,
            )

            val format = VideoCompatibility.default(result.videoCodec)
            exporter.encodeVideo(video, result.clean, format, videoDefault)
            assertTrue("video export missing", videoDefault.length() > 10_000)
            assertVideoMatchesSource(videoDefault, result.videoDurationMs)

            val alternate = VideoCompatibility.ordered(result.videoCodec).last()
            assertTrue("no alternate container to test", alternate != format)
            exporter.encodeVideo(video, result.clean, alternate, videoAlternate)
            assertTrue("alternate video export missing", videoAlternate.length() > 10_000)
            assertVideoMatchesSource(videoAlternate, result.videoDurationMs)

            // Exercise the content-URI path used by the save flow (fd input).
            providerSource.parentFile?.mkdirs()
            video.inputStream().use { inputStream ->
                providerSource.outputStream().use { inputStream.copyTo(it) }
            }
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                providerSource,
            )
            val contentResult = result.copy(sourceUri = contentUri)
            runBlocking {
                exporter.exportVideo(
                    contentResult,
                    result.clean,
                    format,
                    Uri.fromFile(contentVideo),
                )
            }
            assertTrue("content-uri video export missing", contentVideo.length() > 10_000)
            assertVideoMatchesSource(contentVideo, result.videoDurationMs)
        } finally {
            stemDir?.let { dir ->
                if (dir.canonicalPath.startsWith(exports.canonicalPath)) {
                    dir.deleteRecursively()
                }
            }
            inputWav.delete()
            mp3.delete()
            voiceWav.delete()
            videoDefault.delete()
            videoAlternate.delete()
            contentVideo.delete()
            providerSource.delete()
            video.delete()
            deleteNewExports(exports, before)
        }
    }

    private fun assertProgressOrder(details: List<String>) {
        val copy = details.indexOfFirst { it.contains("Copying the video") }
        val extract = details.indexOfFirst { it.contains("Extracting audio") }
        val loudness = details.indexOfFirst { it.contains("Matching loudness") }
        val done = details.indexOfFirst { it == "Done" }
        assertTrue("missing copy progress", copy >= 0)
        assertTrue("copy label was emitted after extraction", copy < extract)
        assertTrue("loudness label missing", loudness > extract)
        assertTrue("done label missing", done > loudness)
    }

    private fun measureLoudness(file: File) = run {
        val session = FFmpegKit.execute(
            "-hide_banner -i ${file.absolutePath} " +
                "-af loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json -f null -"
        )
        assertTrue("could not measure the clean mix", ReturnCode.isSuccess(session.returnCode))
        Loudness.parseLoudnormJson(session.output ?: "")
            ?: error("loudnorm did not print a complete measurement")
    }

    private fun assertVideoMatchesSource(output: File, expectedDurationMs: Long) {
        val info = FFprobeKit.getMediaInformation(output.absolutePath).mediaInformation
            ?: error("could not probe ${output.name}")
        val duration = info.duration?.toDoubleOrNull() ?: error("no duration for ${output.name}")
        assertTrue(
            "${output.name} duration was ${duration}s, expected ${expectedDurationMs / 1000.0}s",
            abs(duration * 1000.0 - expectedDurationMs) < 400.0,
        )
        assertTrue("${output.name} has no video stream", info.streams.orEmpty().any { it.type == "video" })
        assertTrue("${output.name} has no audio stream", info.streams.orEmpty().any { it.type == "audio" })
    }

    private fun copyAsset(
        instrumentation: android.app.Instrumentation,
        asset: String,
        targetName: String,
    ): File {
        val file = File(instrumentation.targetContext.cacheDir, targetName)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun exportNames(exports: File): Set<String> =
        exports.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

    private fun deleteNewExports(exports: File, before: Set<String>) {
        (exportNames(exports) - before).forEach { File(exports, it).deleteRecursively() }
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
