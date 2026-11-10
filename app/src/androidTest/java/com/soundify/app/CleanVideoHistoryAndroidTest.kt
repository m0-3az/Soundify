package com.soundify.app

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.cleanvideo.CleanStem
import com.soundify.app.cleanvideo.CleanVideoHistoryEntry
import com.soundify.app.cleanvideo.CleanVideoHistoryStore
import com.soundify.app.cleanvideo.CleanVideoHistoryStem
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.CleanVideoStorage
import com.soundify.app.cleanvideo.StemResult
import com.soundify.app.cleanvideo.VideoFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanVideoHistoryAndroidTest {

    @Test
    fun historySurvivesANewStoreInstanceAndDeletesItsStems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = CleanVideoStorage.exportRoot(context)
        val id = "history-test-${System.currentTimeMillis()}"
        val dir = File(root, id).apply { mkdirs() }
        val stem = File(dir, "clean.wav").apply { writeText("stem-bytes") }
        val entry = CleanVideoHistoryEntry(
            id = id,
            fingerprint = "fingerprint-$id",
            displayName = "history-test.mp4",
            createdAt = System.currentTimeMillis(),
            durationMs = 1_000,
            stems = listOf(CleanVideoHistoryStem("CLEAN", "clean.wav", 1_000, -18f, -2f)),
            totalBytes = stem.length(),
        )
        try {
            CleanVideoHistoryStore(root).save(entry)

            val reloaded = CleanVideoHistoryStore(root)
            assertNotNull(reloaded.find(entry.fingerprint))
            assertTrue(reloaded.delete(id))
            assertFalse(dir.exists())
            assertFalse(reloaded.list().any { it.id == id })
        } finally {
            dir.deleteRecursively()
            CleanVideoHistoryStore(root).delete(id)
        }
    }

    @Test
    fun entryForKeepsAnAudioOnlyResultAudioOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = CleanVideoStorage.exportRoot(context)
        val id = "entry-for-test-${System.currentTimeMillis()}"
        val dir = File(root, id).apply { mkdirs() }
        fun stem(kind: CleanStem) = StemResult(
            kind = kind,
            file = File(dir, "${kind.name.lowercase()}.wav").apply { writeText("x") },
            durationMs = 1_000,
            rmsDb = -18f,
            peakDb = -2f,
        )
        val result = CleanVideoResult(
            voice = stem(CleanStem.VOICE),
            sfx = stem(CleanStem.SFX),
            music = stem(CleanStem.MUSIC),
            clean = stem(CleanStem.CLEAN),
            sourceUri = Uri.parse("content://media/audio/$id"),
            sourceName = "song.mp3",
            videoCodec = null,
            videoDurationMs = 1_000,
            hasVideo = false,
        )
        try {
            val entry = CleanVideoHistoryStore.entryFor(result, "fp-$id", "0.5.0")

            assertEquals(id, entry.id)
            assertFalse(entry.hasVideo)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fingerprintOfARealContentUriIsStable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val exports = File(context.filesDir, "exports").apply { mkdirs() }
        val source = File(exports, "fingerprint-source.mp4").apply {
            writeBytes(ByteArray(700_000) { index -> (index % 251).toByte() })
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            source,
        )
        try {
            val first = VideoFingerprint.read(context, uri)
            val second = VideoFingerprint.read(context, uri)

            assertNotNull(first)
            assertEquals(first, second)
        } finally {
            source.delete()
        }
    }
}
