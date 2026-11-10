package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class EditorStoreTest {

    private fun root(): File = createTempDirectory("editor-store").toFile()

    private fun clip(
        id: String = "clip",
        sourceHash: String = "hash",
        speed: Float = 1f,
    ) = EditorClip(
        id = id,
        sourceHash = sourceHash,
        sourceFile = "sources/$sourceHash.wav",
        displayName = "clip.wav",
        sourceDurationMs = 2_000L,
        sourceSampleRate = 44_100,
        sourceChannels = 2,
        speed = speed,
    )

    private fun project(
        id: String,
        name: String = "Project $id",
        clips: List<EditorClip> = listOf(clip()),
    ) = EditorProject(id = id, name = name, clips = clips)

    @Test
    fun projectSurvivesSaveAndReload() {
        val root = root()
        try {
            val store = EditorStore(root)
            val project = project("round-trip", name = "Round trip")

            store.save(project)

            val loaded = EditorStore(root).load(project.id)
            assertNotNull(loaded)
            assertEquals(project.copy(updatedAt = loaded!!.updatedAt), loaded)
            assertFalse(File(store.projectDir(project.id), "project.json.tmp").exists())
            assertTrue(File(store.projectDir(project.id), "project.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun listIsNewestFirstAndCountsProjectBytes() {
        val root = root()
        try {
            val store = EditorStore(root)
            store.save(project("older"))
            Thread.sleep(5L)
            store.save(project("newer"))

            val summaries = store.list()

            assertEquals(listOf("newer", "older"), summaries.map { it.id })
            assertTrue(summaries.all { it.bytes > 0L })
            assertEquals(store.bytesOf("older"), summaries.single { it.id == "older" }.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptProjectJsonIsSkippedByListAndLoadReturnsNull() {
        val root = root()
        try {
            val store = EditorStore(root)
            val dir = store.projectDir("broken").apply { mkdirs() }
            File(dir, "project.json").writeText("{not json")

            assertNull(store.load("broken"))
            assertTrue(store.list().isEmpty())
            assertTrue(File(dir, "project.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteRefusesTraversalBlankAndTheRootItself() {
        val root = root()
        val outside = createTempDirectory("editor-outside").toFile()
        try {
            val store = EditorStore(root)
            val keep = File(outside, "keep.txt").apply { writeText("keep") }

            assertFalse(store.delete("../${outside.name}"))
            assertFalse(store.delete("/"))
            assertFalse(store.delete(""))
            assertFalse(store.delete("   "))
            assertFalse(store.delete("."))
            assertFalse(store.delete(root.absolutePath))
            assertTrue(outside.isDirectory)
            assertTrue(keep.isFile)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesOnlyTheProjectDirectory() {
        val root = root()
        try {
            val store = EditorStore(root)
            val keep = store.create("Keep")
            val drop = store.create("Drop")

            assertTrue(store.delete(drop.id))

            assertNull(store.load(drop.id))
            assertNotNull(store.load(keep.id))
            assertFalse(store.projectDir(drop.id).exists())
            assertTrue(store.projectDir(keep.id).isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun registerSourceDeduplicatesIdenticalHashesAndKeepsOneFile() {
        val root = root()
        try {
            val store = EditorStore(root)
            val incoming = File(root, "incoming").apply { mkdirs() }
            val first = File(incoming, "first.tmp").apply { writeText("audio bytes") }
            val second = File(incoming, "second.tmp").apply { writeText("audio bytes") }

            val created = store.registerSource(first, "abc123", "wav")
            val deduped = store.registerSource(second, "abc123", "wav")

            assertTrue(created.created)
            assertFalse(deduped.created)
            assertEquals(created.file, deduped.file)
            assertTrue(created.file.isFile)
            assertEquals("audio bytes", created.file.readText())
            assertFalse(first.exists())
            assertFalse(second.exists())
            assertEquals(1, File(root, "sources").listFiles()!!.count { it.isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun futureSchemaIsLoadedAsIsAndReported() {
        val root = root()
        try {
            val store = EditorStore(root)
            val future = project(
                id = "future",
                name = "Future",
                clips = listOf(clip(id = "future-clip", speed = 5f)),
            ).copy(schemaVersion = 2)

            store.save(future)
            val loaded = store.load(future.id)

            assertNotNull(loaded)
            assertTrue(loaded!!.isFutureSchema)
            assertEquals(5f, loaded.clips.single().speed, 0f)
        } finally {
            root.deleteRecursively()
        }
    }
}
