package com.soundify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.editor.EditorImporter
import com.soundify.app.editor.EditorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EditorImporterTest {

    @Test
    fun importsGeneratedWavAndDeduplicatesTheSameFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "editor-import-${UUID.randomUUID()}").apply { mkdirs() }
        val wav = File(context.cacheDir, "import-source-${UUID.randomUUID()}.wav")
        try {
            val frames = 44_100 * 2
            WavWriter(wav, sampleRate = 44_100, channels = 2).use { writer ->
                writer.writeInterleaved(FloatArray(frames * 2) { 0.1f }, frames)
            }
            val store = EditorStore(root)
            val importer = EditorImporter(context, store)
            val project = store.create("Import")
            val sourceDir = File(store.projectDir(project.id), "sources")

            val first = importer.import(project.id, Uri.fromFile(wav), "generated.wav")

            assertTrue(first.file.isFile)
            assertEquals(sourceDir, first.file.parentFile)
            assertTrue(
                "expected about 2000 ms, got ${first.durationMs}",
                abs(first.durationMs - 2_000L) <= 50L,
            )
            assertEquals(44_100, first.sampleRate)
            assertEquals(2, first.channels)
            assertEquals(64, first.hash.length)

            val second = importer.import(project.id, Uri.fromFile(wav), "generated.wav")

            assertEquals(first.hash, second.hash)
            assertEquals(first.file, second.file)
            assertEquals(1, sourceDir.listFiles()!!.count { it.isFile })

            val otherProject = store.create("Other import")
            val other = importer.import(otherProject.id, Uri.fromFile(wav), "generated.wav")
            val otherSourceDir = File(store.projectDir(otherProject.id), "sources")

            assertEquals(first.hash, other.hash)
            assertNotEquals(first.file, other.file)
            assertEquals(otherSourceDir, other.file.parentFile)
            assertEquals(1, otherSourceDir.listFiles()!!.count { it.isFile })
            assertArrayEquals(first.file.readBytes(), other.file.readBytes())
            assertFalse(File(root, "sources").exists())
        } finally {
            wav.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun importIntoADeletedProjectDoesNotRecreateIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "editor-import-${UUID.randomUUID()}").apply { mkdirs() }
        val wav = File(context.cacheDir, "import-source-${UUID.randomUUID()}.wav")
        try {
            WavWriter(wav, sampleRate = 44_100, channels = 2).use { writer ->
                writer.writeInterleaved(FloatArray(44_100 * 2) { 0.1f }, 44_100)
            }
            val store = EditorStore(root)
            val importer = EditorImporter(context, store)
            val project = store.create("Deleted")

            assertTrue(store.projectDir(project.id).deleteRecursively())

            val error = runCatching {
                importer.import(project.id, Uri.fromFile(wav), "generated.wav")
            }.exceptionOrNull()

            assertNotNull("importing into a deleted project must fail", error)
            assertFalse(
                "the import must not resurrect the deleted project directory",
                store.projectDir(project.id).exists(),
            )
            assertFalse(
                "the import temp must be cleaned up",
                File(context.cacheDir, "editor/import.tmp").exists(),
            )
        } finally {
            wav.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun fileWithoutAnAudioTrackLeavesNoSourceBehind() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "editor-import-${UUID.randomUUID()}").apply { mkdirs() }
        val bad = File(context.cacheDir, "import-bad-${UUID.randomUUID()}.wav")
        try {
            bad.writeText("not audio")
            val store = EditorStore(root)
            val importer = EditorImporter(context, store)
            val project = store.create("Invalid import")
            val sourceDir = File(store.projectDir(project.id), "sources")

            val error = try {
                importer.import(project.id, Uri.fromFile(bad), "bad.wav")
                null
            } catch (expected: IllegalStateException) {
                expected
            }

            assertEquals("This file has no readable audio track.", error?.message)
            assertTrue(sourceDir.listFiles().isNullOrEmpty())
            assertTrue(store.projectDir(project.id).isDirectory)
        } finally {
            bad.delete()
            root.deleteRecursively()
        }
    }
}
