package com.soundify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.editor.EditorImporter
import com.soundify.app.editor.EditorStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

            val first = importer.import(Uri.fromFile(wav), "generated.wav")

            assertTrue(first.file.isFile)
            assertEquals("sources", first.file.parentFile?.name)
            assertTrue(
                "expected about 2000 ms, got ${first.durationMs}",
                abs(first.durationMs - 2_000L) <= 50L,
            )
            assertEquals(44_100, first.sampleRate)
            assertEquals(2, first.channels)
            assertEquals(64, first.hash.length)

            val second = importer.import(Uri.fromFile(wav), "generated.wav")

            assertEquals(first.hash, second.hash)
            assertEquals(first.file, second.file)
            assertEquals(1, File(root, "sources").listFiles()!!.count { it.isFile })
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

            val error = try {
                importer.import(Uri.fromFile(bad), "bad.wav")
                null
            } catch (expected: IllegalStateException) {
                expected
            }

            assertEquals("This file has no readable audio track.", error?.message)
            assertTrue(File(root, "sources").listFiles().isNullOrEmpty())
        } finally {
            bad.delete()
            root.deleteRecursively()
        }
    }
}
