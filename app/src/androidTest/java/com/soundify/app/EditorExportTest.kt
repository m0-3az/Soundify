package com.soundify.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.cleanvideo.WavStreamReader
import com.soundify.app.editor.EditorClip
import com.soundify.app.editor.EditorExporter
import com.soundify.app.editor.EditorFormat
import com.soundify.app.editor.EditorProject
import com.soundify.app.editor.EditorStore
import kotlinx.coroutines.runBlocking
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

            // The mandatory -1 dBFS lookahead limiter delays the signal by its
            // attack time. Find that constant delay, then null the rest.
            val trimStartFrame = 22_050
            val latency = bestLag(exported.frames[0], source.frames[0], trimStartFrame)
            assertTrue("unexpected limiter latency: $latency samples", latency <= 441)

            var maxDifference = 0f
            val edge = 512
            for (channel in 0 until exported.channels) {
                val rendered = exported.frames[channel]
                val original = source.frames[channel]
                for (index in latency + edge until rendered.size - edge) {
                    val sourceIndex = trimStartFrame + index - latency
                    maxDifference = max(maxDifference, abs(rendered[index] - original[sourceIndex]))
                }
            }
            assertTrue(
                "the no-effects export must match the source trim, differed by $maxDifference " +
                    "after $latency samples of limiter latency",
                maxDifference <= 2e-4f,
            )
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
        WavWriter(file, sampleRate = sampleRate, channels = 2).use { writer ->
            val samples = FloatArray(frameCount * 2)
            for (frame in 0 until frameCount) {
                val value = (sin(2.0 * PI * 440.0 * frame / sampleRate) * amplitude).toFloat()
                samples[2 * frame] = value
                samples[2 * frame + 1] = value
            }
            writer.writeInterleaved(samples, frameCount)
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
