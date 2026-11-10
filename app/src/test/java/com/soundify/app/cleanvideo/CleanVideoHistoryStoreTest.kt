package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CleanVideoHistoryStoreTest {

    private fun storeIn(root: File) = CleanVideoHistoryStore(root)

    private fun entry(
        id: String,
        fingerprint: String = "fp-$id",
        createdAt: Long = 1_000L,
        bytes: Long = 100L,
        hasVideo: Boolean = true,
    ) = CleanVideoHistoryEntry(
        id = id,
        fingerprint = fingerprint,
        displayName = "$id.mp4",
        createdAt = createdAt,
        durationMs = 12_000,
        videoCodec = "h264",
        cleanNote = null,
        stems = listOf(
            CleanVideoHistoryStem("CLEAN", "clean.wav", 12_000, -18.0f, -2.0f),
        ),
        totalBytes = bytes,
        sourceUri = "content://media/$id",
        appVersion = "0.4.0",
        hasVideo = hasVideo,
    )

    @Test
    fun savesEntriesNewestFirst() {
        val root = createTempDirectory("history").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("older", createdAt = 1_000))
            store.save(entry("newer", createdAt = 2_000))

            assertEquals(listOf("newer", "older"), store.list().map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savingTheSameIdReplacesTheEntry() {
        val root = createTempDirectory("history").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("run", bytes = 100))
            store.save(entry("run", bytes = 250))

            assertEquals(1, store.list().size)
            assertEquals(250L, store.list().single().totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun findsTheEntryForAFingerprint() {
        val root = createTempDirectory("history").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("run", fingerprint = "abc"))

            assertEquals("run", store.find("abc")?.id)
            assertNull(store.find("missing"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesTheEntryAndItsStemDirectory() {
        val root = createTempDirectory("history").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("run"))
            val dir = File(root, "run").apply { mkdirs() }
            File(dir, "clean.wav").writeText("stem")

            assertTrue(store.delete("run"))
            assertTrue(store.list().isEmpty())
            assertFalse(dir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteAllRemovesRunDirectoriesButKeepsTheRootAndHistoryFile() {
        val root = createTempDirectory("history").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("one"))
            store.save(entry("two"))
            File(root, "one").apply { mkdirs(); File(this, "clean.wav").writeText("a") }
            File(root, "two").apply { mkdirs(); File(this, "clean.wav").writeText("b") }
            val keep = File(root, "notes.txt").apply { writeText("keep") }

            assertTrue(store.deleteAllRuns())
            assertTrue(store.list().isEmpty())
            assertFalse(File(root, "one").exists())
            assertFalse(File(root, "two").exists())
            assertTrue(root.exists())
            assertTrue(File(root, "history.json").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesIdsThatEscapeTheRoot() {
        val root = createTempDirectory("history").toFile()
        val outside = createTempDirectory("outside").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("../outside"))

            assertFalse(store.delete("../outside"))
            assertTrue(outside.exists())
            assertNotNull(store.find("fp-../outside"))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun audioOnlyEntriesRoundTripWithHasVideoFalse() {
        val root = createTempDirectory("history").toFile()
        try {
            val store = storeIn(root)
            store.save(entry("audio", hasVideo = false))

            assertFalse(store.list().single().hasVideo)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyEntriesWithoutHasVideoDecodeAsVideo() {
        val root = createTempDirectory("history").toFile()
        try {
            File(root, "history.json").writeText(
                """
                [
                  {
                    "id": "legacy",
                    "fingerprint": "fp-legacy",
                    "displayName": "legacy.mp4",
                    "createdAt": 1000,
                    "durationMs": 12000,
                    "videoCodec": "h264",
                    "stems": [],
                    "totalBytes": 100,
                    "sourceUri": "content://media/legacy",
                    "appVersion": "0.4.0"
                  }
                ]
                """.trimIndent(),
            )

            val entry = storeIn(root).list().single()

            assertEquals("legacy", entry.id)
            assertTrue(entry.hasVideo)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptHistoryFileLoadsAsEmptyInsteadOfCrashing() {
        val root = createTempDirectory("history").toFile()
        try {
            File(root, "history.json").writeText("{not json")
            assertTrue(storeIn(root).list().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
