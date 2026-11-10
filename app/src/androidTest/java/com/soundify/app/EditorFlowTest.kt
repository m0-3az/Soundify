package com.soundify.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.cleanvideo.WavStreamReader
import com.soundify.app.editor.EditorFormat
import com.soundify.app.editor.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class EditorFlowTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: File
    private lateinit var export: File
    private lateinit var viewModel: EditorViewModel
    private var projectId: String? = null

    @Before
    fun setUp() {
        val root = File(context.cacheDir, "editor-flow-${UUID.randomUUID()}").apply { mkdirs() }
        source = File(root, "tone.wav")
        export = File(context.filesDir, "exports/editor-flow-${UUID.randomUUID()}.wav")
        export.parentFile?.mkdirs()
        writeTone(source, seconds = 2.0)
        viewModel = EditorViewModel(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        projectId?.let { id ->
            viewModel.delete(id)
            await("the test project was not deleted") {
                viewModel.projects.value.none { it.id == id }
            }
        }
        source.parentFile?.deleteRecursively()
        export.delete()
    }

    @Test
    fun importTrimAndExportProduceTheTrimmedDuration() = runBlocking {
        val originalHash = sha256(source)
        val uri = Uri.fromFile(source)

        viewModel.createFromAudio(uri, "tone.wav")
        await("the import did not produce an active project") {
            viewModel.active.value != null
        }
        val project = viewModel.active.value ?: error("no active project")
        projectId = project.id
        val clip = project.clips.single()

        viewModel.edit("trim:${clip.id}") { current ->
            current.copy(
                clips = listOf(
                    current.clips.single().copy(trimStartMs = 500L, trimEndMs = 1_500L),
                ),
            )
        }
        assertEquals(500L, viewModel.active.value!!.clips.single().trimStartMs)
        assertEquals(1_500L, viewModel.active.value!!.clips.single().trimEndMs)

        val target = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            export,
        )
        val result = viewModel.export(EditorFormat.WAV, target)
        assertTrue("export failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        WavStreamReader(export).use { reader ->
            assertEquals(44_100, reader.sampleRate)
            assertEquals(2, reader.channels)
            assertTrue(
                "expected 44100 frames (±1), got ${reader.totalFrames}",
                abs(reader.totalFrames - 44_100L) <= 1L,
            )
        }

        assertEquals("the picked original must never be modified", originalHash, sha256(source))

        val appCopy = viewModel.sourceFile(project.id, clip)
        assertTrue("the app copy must exist before deletion", appCopy.isFile)

        viewModel.delete(project.id)
        await("the project was not deleted") {
            viewModel.projects.value.none { it.id == project.id }
        }
        assertFalse("deleting an edit must remove only the app copy", appCopy.exists())
        assertTrue("the picked original must survive project deletion", source.isFile)
        projectId = null
    }

    private fun writeTone(file: File, seconds: Double) {
        val sampleRate = 44_100
        val frames = (sampleRate * seconds).toInt()
        WavWriter(file, sampleRate = sampleRate, channels = 2).use { writer ->
            val samples = FloatArray(frames * 2) { index ->
                val frame = index / 2
                (sin(2.0 * PI * 440.0 * frame / sampleRate) * 0.5).toFloat()
            }
            writer.writeInterleaved(samples, frames)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun await(message: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }
}
