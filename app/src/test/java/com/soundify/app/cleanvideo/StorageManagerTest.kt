package com.soundify.app.cleanvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class StorageManagerTest {

    @Test
    fun usageSplitsProjectsCleanVideoAndOtherExports() {
        val files = createTempDirectory("files").toFile()
        val projects = File(files, "projects").apply { mkdirs() }
        File(projects, "p.json").writeText("12345")
        val exports = File(files, "exports").apply { mkdirs() }
        val cleanVideo = File(exports, "cleanvideo").apply { mkdirs() }
        File(cleanVideo, "clean.wav").writeText("123")
        File(exports, "mix.mp3").writeText("1234567")
        val cache = createTempDirectory("cache").toFile()
        File(cache, "cleanvideo").apply { mkdirs(); File(this, "work.wav").writeText("12") }
        val model = File(files, "mrx_mask.onnx").apply { writeText("1234567890") }

        try {
            val usage = StorageUsage.of(projects, exports, cache, model)
            assertEquals(5L, usage.projectsBytes)
            assertEquals(3L, usage.cleanVideoBytes)
            assertEquals(7L, usage.otherExportsBytes)
            assertEquals(2L, usage.cacheBytes)
            assertEquals(10L, usage.modelBytes)
            assertEquals(17L, usage.userDataBytes)
            assertEquals(27L, usage.totalBytes)
        } finally {
            files.deleteRecursively()
            cache.deleteRecursively()
        }
    }

    @Test
    fun cleaningTemporaryFilesEmptiesTheCacheAndKeepsUserData() {
        val cache = createTempDirectory("cache").toFile()
        val keep = createTempDirectory("files").toFile()
        File(cache, "cleanvideo").apply { mkdirs(); File(this, "work.wav").writeText("1234") }
        File(cache, "export.tmp").writeText("123456")
        File(keep, "project.json").writeText("keep")

        try {
            val freed = StorageCleanup.cleanTemporary(cache)
            assertEquals(10L, freed)
            assertTrue(cache.listFiles().isNullOrEmpty())
            assertTrue(File(keep, "project.json").exists())
        } finally {
            cache.deleteRecursively()
            keep.deleteRecursively()
        }
    }

    @Test
    fun deletingCleanVideoResultsKeepsOtherExports() {
        val exports = createTempDirectory("exports").toFile()
        val root = File(exports, "cleanvideo").apply { mkdirs() }
        val store = CleanVideoHistoryStore(root)
        store.save(
            CleanVideoHistoryEntry(
                id = "run",
                fingerprint = "fp",
                displayName = "run.mp4",
                createdAt = 1L,
                durationMs = 1_000,
                stems = emptyList(),
                totalBytes = 100,
            )
        )
        File(root, "run").apply { mkdirs(); File(this, "clean.wav").writeText("12345") }
        val other = File(exports, "mix.mp3").apply { writeText("keep") }

        try {
            val freed = StorageCleanup.deleteCleanVideoResults(root)
            assertEquals(5L, freed)
            assertFalse(File(root, "run").exists())
            assertTrue(other.exists())
            assertTrue(store.list().isEmpty())
        } finally {
            exports.deleteRecursively()
        }
    }
}
