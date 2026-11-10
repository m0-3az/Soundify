package com.soundify.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.cleanvideo.Ffmpeg
import com.soundify.app.cleanvideo.WavStreamReader
import com.arthenica.ffmpegkit.FFprobeKit
import com.soundify.app.editor.EditorClip
import com.soundify.app.editor.EditorExporter
import com.soundify.app.editor.EditorFormat
import com.soundify.app.editor.EditorProject
import com.soundify.app.editor.EditorStore
import com.soundify.app.editor.DenoiseMode
import com.soundify.app.editor.EqPreset
import com.soundify.app.editor.renderedDurationMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.math.max
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class EditorExportTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportsTwoSecondWavThroughTheSafCopy() {
        val fixture = Fixture(amplitude = 0.99f)
        val target = safTarget()
        val progress = mutableListOf<Int>()
        try {
            val exporter = EditorExporter(context, fixture.store)
            val result = runBlocking {
                exporter.export(
                    fixture.project,
                    fixture.project.clips,
                    EditorFormat.WAV,
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target.file,
                    ),
                    onProgress = { progress += it },
                )
            }

            assertTrue("export failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals("export progress must finish at 100", 100, progress.last())

            val exported = readContent(target.uri)
            try {
                val decoded = decode(exported)
                assertEquals(44_100, decoded.sampleRate)
                assertEquals(2, decoded.channels)
                assertTrue(
                    "expected 88200 frames (±1), got ${decoded.frameCount}",
                    abs(decoded.frameCount - 88_200) <= 1,
                )
                val peak = decoded.peak()
                assertTrue("exported audio is silent", peak > 0.8f)
                assertTrue("-1 dBFS limiter exceeded: $peak", peak <= 0.892f)
            } finally {
                exported.delete()
            }
        } finally {
            fixture.cleanup()
            target.file.delete()
        }
    }

    @Test
    fun exportWithoutEffectsMatchesTheTrimmedSource() {
        val fixture = Fixture(amplitude = 0.5f)
        val output = File(fixture.root, "null-test.wav")
        try {
            val clip = fixture.project.clips.single()
                .copy(trimStartMs = 500L, trimEndMs = 1_500L)
            val trimmed = fixture.project.copy(clips = listOf(clip))
            val exporter = EditorExporter(context, fixture.store)

            runBlocking {
                exporter.exportToFile(
                    trimmed,
                    trimmed.clips,
                    EditorFormat.WAV,
                    output,
                )
            }

            val exported = decode(output)
            val source = decode(fixture.source)
            assertEquals(44_100, exported.sampleRate)
            assertEquals(2, exported.channels)
            assertTrue(
                "expected 44100 frames (±1), got ${exported.frameCount}",
                abs(exported.frameCount - 44_100) <= 1,
            )

            // The limiter must compensate its lookahead so the trim stays
            // sample-aligned with the source.
            val trimStartFrame = 22_050
            val lag = bestLag(exported.frames[0], source.frames[0], trimStartFrame)
            assertTrue("export is not sample-aligned: lag $lag", lag <= 1)

            var maxDifference = 0f
            for (channel in 0 until exported.channels) {
                val rendered = exported.frames[channel]
                val original = source.frames[channel]
                for (index in rendered.indices) {
                    val difference = abs(rendered[index] - original[trimStartFrame + index])
                    maxDifference = max(maxDifference, difference)
                }
            }
            assertTrue(
                "the no-effects export must match the source trim, differed by $maxDifference",
                maxDifference <= 2e-4f,
            )
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun allEffectsGraphEncodesOnDevice() {
        val fixture = Fixture(amplitude = 0.5f)
        val output = File(fixture.root, "all-effects.wav")
        try {
            val clip = fixture.project.clips.single().copy(
                trimStartMs = 300L,
                trimEndMs = 1_700L,
                fadeInMs = 200L,
                fadeOutMs = 200L,
                gainDb = -3f,
                speed = 1.25f,
                pitchSemitones = 3,
                denoise = DenoiseMode.LIGHT,
                clarity = true,
                eqPreset = EqPreset.WARM,
                compThresholdDb = -20f,
                compRatio = 4f,
                compMakeupDb = 2f,
            )
            val project = fixture.project.copy(clips = listOf(clip))
            val exporter = EditorExporter(context, fixture.store)

            runBlocking {
                exporter.exportToFile(project, project.clips, EditorFormat.WAV, output)
            }

            val decoded = decode(output)
            assertEquals(44_100, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            val expectedFrames = clip.renderedDurationMs * 44_100L / 1_000L
            assertTrue(
                "expected about $expectedFrames frames, got ${decoded.frameCount}",
                abs(decoded.frameCount - expectedFrames) <= 1_000L,
            )
            assertTrue("the all-effects export is silent", decoded.peak() > 0.01f)
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun everyFormatEncodesOnDevice() {
        val fixture = Fixture(amplitude = 0.5f, seconds = 1.0)
        try {
            EditorFormat.entries.forEach { format ->
                val output = File(fixture.root, "format-test.${format.extension}")
                val exporter = EditorExporter(context, fixture.store)

                runBlocking {
                    exporter.exportToFile(
                        fixture.project,
                        fixture.project.clips,
                        format,
                        output,
                    )
                }

                assertTrue("${format.name} export is missing", output.isFile)
                assertTrue("${format.name} export is empty", output.length() > 0L)
                val information = FFprobeKit.getMediaInformation(output.absolutePath)
                    .mediaInformation
                val stream = information.streams.orEmpty()
                    .firstOrNull { it.type == "audio" }
                assertNotNull("${format.name} has no audio stream", stream)
                assertEquals(
                    "${format.name} sample rate",
                    "44100",
                    stream!!.sampleRate,
                )
                assertEquals(
                    "${format.name} channels",
                    2,
                    stream.getNumberProperty("channels")?.toInt(),
                )
                output.delete()
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun lowStorageFailsBeforeStartingFfmpeg() {
        val fixture = Fixture(amplitude = 0.5f)
        val output = File(fixture.root, "no-space.wav")
        var progressCalls = 0
        try {
            val exporter = EditorExporter(context, fixture.store, freeSpace = { 0L })

            val error = runCatching {
                runBlocking {
                    exporter.exportToFile(
                        fixture.project,
                        fixture.project.clips,
                        EditorFormat.WAV,
                        output,
                        onProgress = { progressCalls++ },
                    )
                }
            }.exceptionOrNull()

            assertNotNull("low storage must fail the export", error)
            assertEquals(
                "Not enough storage. Free some space and try again.",
                error!!.message,
            )
            assertEquals("FFmpeg must not start on low storage", 0, progressCalls)
            assertFalse("no output may be written before the space check", output.exists())
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun cancelLeavesNoTempFileAndKeepsTheProject() {
        val fixture = Fixture(amplitude = 0.5f, seconds = 10.0)
        val exportDir = File(context.cacheDir, "editor/export")
        val tempFilesBefore = exportDir.listFiles().orEmpty().toSet()
        val target = safTarget()
        try {
            val exporter = EditorExporter(context, fixture.store)

            val result = runBlocking {
                exporter.export(
                    fixture.project,
                    fixture.project.clips,
                    EditorFormat.WAV,
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target.file,
                    ),
                    onCancelReady = { cancel -> cancel() },
                )
            }

            assertEquals("Export was cancelled.", result.exceptionOrNull()?.message)
            assertEquals(
                "the cancelled export left a temp file behind",
                tempFilesBefore,
                exportDir.listFiles().orEmpty().toSet(),
            )
            assertFalse("the cancelled export wrote a target", target.file.exists())
            val reloaded = fixture.store.load(fixture.project.id)
            assertNotNull("the project must survive a cancelled export", reloaded)
            assertEquals(fixture.project.clips, reloaded!!.clips)
        } finally {
            fixture.cleanup()
            target.file.delete()
        }
    }

    @Test
    fun cancelAfterEncodingNeverWritesTheTarget() {
        val fixture = Fixture(amplitude = 0.5f)
        val target = safTarget()
        val exportDir = File(context.cacheDir, "editor/export")
        val tempFilesBefore = exportDir.listFiles().orEmpty().toSet()
        lateinit var cancel: () -> Unit
        try {
            val exporter = EditorExporter(context, fixture.store)

            val result = runBlocking {
                exporter.export(
                    fixture.project,
                    fixture.project.clips,
                    EditorFormat.WAV,
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target.file,
                    ),
                    onProgress = { progress -> if (progress == 100) cancel() },
                    onCancelReady = { action -> cancel = action },
                )
            }

            assertEquals("Export was cancelled.", result.exceptionOrNull()?.message)
            assertFalse("a cancelled export must not touch the target", target.file.exists())
            assertEquals(
                "the cancelled export left a temp file behind",
                tempFilesBefore,
                exportDir.listFiles().orEmpty().toSet(),
            )
        } finally {
            fixture.cleanup()
            target.file.delete()
        }
    }

    @Test
    fun scopeCancellationWaitsForFfmpegAndLeavesNoTemp() {
        val fixture = Fixture(amplitude = 0.5f, seconds = 120.0)
        val target = safTarget()
        val exportDir = File(context.cacheDir, "editor/export")
        val tempFilesBefore = exportDir.listFiles().orEmpty().toSet()
        val ready = CompletableDeferred<Unit>()
        try {
            val exporter = EditorExporter(context, fixture.store)
            val job = runBlocking {
                val exportJob = launch(Dispatchers.IO) {
                    exporter.export(
                        fixture.project,
                        fixture.project.clips,
                        EditorFormat.WAV,
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            target.file,
                        ),
                        onCancelReady = { ready.complete(Unit) },
                    )
                }
                withTimeout(15_000) { ready.await() }
                // Cancel only after FFmpeg has created its temp file, so the
                // cleanup races a genuinely running session.
                withTimeout(15_000) {
                    while (
                        exportJob.isActive &&
                        exportDir.listFiles().orEmpty().toSet() == tempFilesBefore
                    ) {
                        delay(5)
                    }
                }
                exportJob.cancelAndJoin()
                exportJob
            }

            assertTrue("the export coroutine must be cancelled", job.isCancelled)
            assertFalse("a cancelled export must not write the target", target.file.exists())
            assertEquals(
                "the cancelled export left a temp file behind",
                tempFilesBefore,
                exportDir.listFiles().orEmpty().toSet(),
            )
        } finally {
            fixture.cleanup()
            target.file.delete()
        }
    }

    @Test
    fun missingSourceFailsBeforeStartingFfmpeg() {
        val fixture = Fixture(amplitude = 0.5f)
        val output = File(fixture.root, "missing-source.wav")
        var progressCalls = 0
        try {
            assertTrue("the test source must be deletable", fixture.source.delete())
            val exporter = EditorExporter(context, fixture.store)

            val error = runCatching {
                runBlocking {
                    exporter.exportToFile(
                        fixture.project,
                        fixture.project.clips,
                        EditorFormat.WAV,
                        output,
                        onProgress = { progressCalls++ },
                    )
                }
            }.exceptionOrNull()

            assertEquals(
                "A source file is missing. Re-import this clip and try again.",
                error?.message,
            )
            assertEquals("FFmpeg must not start without a source", 0, progressCalls)
            assertFalse(output.exists())
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun asyncFailuresCarryTheFfmpegReturnCode() = runBlocking {
        val message = CompletableDeferred<String>()
        Ffmpeg.runAsync(
            "-not_a_real_ffmpeg_option",
            onStatistics = {},
            onComplete = { success, text ->
                assertFalse("the bogus command must fail", success)
                message.complete(text)
            },
        )

        val text = withTimeout(30_000) { message.await() }

        assertTrue("expected the return code in: $text", text.contains("FFmpeg failed (1)."))
    }

    private fun safTarget(): Target {
        val file = File(context.filesDir, "exports/editor-${UUID.randomUUID()}.wav")
        file.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Target(file, uri)
    }

    private fun readContent(uri: Uri): File {
        val copy = File(context.cacheDir, "saf-read-${UUID.randomUUID()}.wav")
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Could not read the saved document")
        input.use { source ->
            copy.outputStream().use { source.copyTo(it) }
        }
        return copy
    }

    private fun bestLag(rendered: FloatArray, source: FloatArray, sourceStart: Int): Int {
        val probeStart = 2_000
        val probeEnd = 10_000
        var bestLag = 0
        var bestError = Double.MAX_VALUE
        for (lag in 0..441) {
            var error = 0.0
            for (index in probeStart until probeEnd) {
                error += abs(rendered[index] - source[sourceStart + index - lag])
            }
            if (error < bestError) {
                bestError = error
                bestLag = lag
            }
        }
        return bestLag
    }

    private fun decode(file: File): Decoded = WavStreamReader(file).use { reader ->
        val frameCount = reader.totalFrames.toInt()
        val frames = Array(reader.channels) { FloatArray(frameCount) }
        var offset = 0
        while (offset < frameCount) {
            val count = minOf(65_536, frameCount - offset)
            val chunk = reader.read(offset.toLong(), count)
            for (channel in 0 until reader.channels) {
                System.arraycopy(chunk[channel], 0, frames[channel], offset, count)
            }
            offset += count
        }
        Decoded(reader.sampleRate, reader.channels, frames)
    }

    private fun writeTone(file: File, seconds: Double, amplitude: Float) {
        val sampleRate = 44_100
        val frameCount = (sampleRate * seconds).toInt()
        val chunkFrames = sampleRate
        val samples = FloatArray(chunkFrames * 2)
        WavWriter(file, sampleRate = sampleRate, channels = 2).use { writer ->
            var frame = 0
            while (frame < frameCount) {
                val count = minOf(chunkFrames, frameCount - frame)
                for (index in 0 until count) {
                    val position = frame + index
                    val value =
                        (sin(2.0 * PI * 440.0 * position / sampleRate) * amplitude).toFloat()
                    samples[2 * index] = value
                    samples[2 * index + 1] = value
                }
                writer.writeInterleaved(samples, count)
                frame += count
            }
        }
    }

    private data class Decoded(
        val sampleRate: Int,
        val channels: Int,
        val frames: Array<FloatArray>,
    ) {
        val frameCount: Int get() = frames.first().size

        fun peak(): Float = frames.maxOf { channel -> channel.maxOf { abs(it) } }
    }

    private class Target(val file: File, val uri: Uri)

    private inner class Fixture(
        amplitude: Float,
        seconds: Double = 2.0,
    ) {
        val root: File = File(context.cacheDir, "editor-export-${UUID.randomUUID()}")
            .apply { mkdirs() }
        val store = EditorStore(root)
        val project: EditorProject
        val source: File

        init {
            val created = store.create("Export fixture")
            source = store.sourceFile(created.id, HASH, "wav")
            writeTone(source, seconds, amplitude)
            val durationMs = (seconds * 1_000.0).toLong()
            val clip = EditorClip(
                sourceHash = HASH,
                sourceFile = "sources/$HASH.wav",
                displayName = "tone.wav",
                sourceDurationMs = durationMs,
                sourceSampleRate = 44_100,
                sourceChannels = 2,
            )
            store.save(created.copy(clips = listOf(clip)))
            project = store.load(created.id)!!
        }

        fun cleanup() {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val HASH = "tone-hash"
    }
}
