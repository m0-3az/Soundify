package com.soundify.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.StemMixer
import com.soundify.app.audio.WavExporter
import com.soundify.app.data.Project
import com.soundify.app.data.Stem
import com.soundify.app.data.StemRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class AudioEngineTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun exportsMixedWav() {
        val project = prepareProject()
        val output = File(context.cacheDir, "instrumentation-export.wav")
        WavExporter(project).export(output) {}

        val bytes = output.readBytes()
        assertTrue(bytes.size > 44)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertTrue(output.length() >= 44L + 44100L * 2 * 2)
        assertNonSilent(bytes)
    }

    @Test
    fun exportsMp3Stems() {
        val project = prepareProject(
            directoryName = "instrumentation-mp3",
            extension = "mp3",
            format = "audio/mpeg",
            assetDirectory = "compressed"
        )
        val output = File(context.cacheDir, "instrumentation-mp3.wav")
        WavExporter(project).export(output) {}
        assertTrue(output.length() > 44)
        assertNonSilent(output.readBytes())
    }

    @Test
    fun exportsM4aStems() {
        val project = prepareProject(
            directoryName = "instrumentation-m4a",
            extension = "m4a",
            format = "audio/mp4a-latm",
            assetDirectory = "compressed"
        )
        val output = File(context.cacheDir, "instrumentation-m4a.wav")
        WavExporter(project).export(output) {}
        assertTrue(output.length() > 44)
        assertNonSilent(output.readBytes())
    }

    @Test
    fun playbackAdvances() {
        val project = prepareProject()
        val position = AtomicLong(0L)
        val mixer = StemMixer(
            project = project,
            onPosition = { position.set(it) },
            onFinished = {},
            onError = { throw AssertionError(it) }
        )
        try {
            mixer.start(0L)
            mixer.play()
            Thread.sleep(900)
            assertTrue("playback did not advance: ${position.get()}", position.get() > 0L)
        } finally {
            mixer.release()
        }
    }

    private fun assertNonSilent(bytes: ByteArray) {
        assertTrue("export contains only silence", bytes.drop(44).any { it.toInt() != 0 })
    }

    private fun prepareProject(
        directoryName: String = "instrumentation-stems",
        extension: String = "wav",
        format: String = "audio/raw",
        assetDirectory: String = "stems"
    ): Project {
        val directory = File(context.filesDir, directoryName).apply { mkdirs() }
        val names = listOf("vocals", "drums", "bass", "other")
        val roles = listOf(StemRole.VOCALS, StemRole.DRUMS, StemRole.BASS, StemRole.OTHER)

        val stems = names.mapIndexed { index, name ->
            val destination = File(directory, "$name.$extension")
            instrumentation.context.assets.open("$assetDirectory/$name.$extension").use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            Stem(
                role = roles[index],
                displayName = name,
                filePath = destination.absolutePath,
                format = format,
                sampleRate = 44100,
                channels = 2,
                durationMs = 1000,
                sortOrder = index
            )
        }

        return Project(
            name = "Instrumentation",
            sampleRate = 44100,
            durationMs = 1000,
            loopEndMs = 1000,
            stems = stems
        )
    }
}
