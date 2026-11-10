package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun deleteRefusesTraversalBlankReservedNamesAndTheRootItself() {
        val root = root()
        val outside = createTempDirectory("editor-outside").toFile()
        try {
            val store = EditorStore(root)
            val keep = File(outside, "keep.txt").apply { writeText("keep") }
            val sources = File(root, "sources").apply { mkdirs() }
            val peaks = File(root, "peaks").apply { mkdirs() }
            val projectJson = File(root, "project.json").apply { writeText("{}") }
            val hidden = File(root, ".hidden").apply { mkdirs() }

            assertFalse(store.delete("../${outside.name}"))
            assertFalse(store.delete("/"))
            assertFalse(store.delete(""))
            assertFalse(store.delete("   "))
            assertFalse(store.delete("."))
            assertFalse(store.delete(root.absolutePath))
            assertFalse(store.delete("sources"))
            assertFalse(store.delete("peaks"))
            assertFalse(store.delete("project.json"))
            assertFalse(store.delete(".hidden"))
            assertTrue(outside.isDirectory)
            assertTrue(keep.isFile)
            assertTrue(sources.isDirectory)
            assertTrue(peaks.isDirectory)
            assertTrue(projectJson.isFile)
            assertTrue(hidden.isDirectory)
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
            val incoming = File(root, "incoming").apply { mkdirs() }
            val keep = store.create("Keep")
            val drop = store.create("Drop")
            val keepSource = store.registerSource(
                keep.id,
                File(incoming, "keep.tmp").apply { writeText("keep audio") },
                "keep-hash",
                "wav",
            )
            val dropSource = store.registerSource(
                drop.id,
                File(incoming, "drop.tmp").apply { writeText("drop audio") },
                "drop-hash",
                "wav",
            )
            val keepPeaks = store.peaksFile(keep.id, "keep-hash").apply { writeBytes(byteArrayOf(1)) }
            val dropPeaks = store.peaksFile(drop.id, "drop-hash").apply { writeBytes(byteArrayOf(2)) }

            assertTrue(store.delete(drop.id))

            assertNull(store.load(drop.id))
            assertNotNull(store.load(keep.id))
            assertFalse(store.projectDir(drop.id).exists())
            assertFalse(dropSource.file.exists())
            assertFalse(dropPeaks.exists())
            assertTrue(store.projectDir(keep.id).isDirectory)
            assertTrue(keepSource.file.isFile)
            assertTrue(keepPeaks.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun registerSourceDeduplicatesIdenticalHashesWithinAProject() {
        val root = root()
        try {
            val store = EditorStore(root)
            val project = store.create("Project")
            val incoming = File(root, "incoming").apply { mkdirs() }
            val first = File(incoming, "first.tmp").apply { writeText("audio bytes") }
            val second = File(incoming, "second.tmp").apply { writeText("audio bytes") }

            val created = store.registerSource(project.id, first, "abc123", "wav")
            val deduped = store.registerSource(project.id, second, "abc123", "wav")

            assertTrue(created.created)
            assertFalse(deduped.created)
            assertEquals(created.file, deduped.file)
            assertTrue(created.file.isFile)
            assertEquals("audio bytes", created.file.readText())
            assertFalse(first.exists())
            assertFalse(second.exists())
            val sourceDir = File(store.projectDir(project.id), "sources")
            assertEquals(sourceDir, created.file.parentFile)
            assertEquals(1, sourceDir.listFiles()!!.count { it.isFile })

            val other = store.create("Other")
            val third = File(incoming, "third.tmp").apply { writeText("audio bytes") }
            val otherCopy = store.registerSource(other.id, third, "abc123", "wav")

            assertTrue(otherCopy.created)
            assertFalse(created.file == otherCopy.file)
            assertTrue(otherCopy.file.isFile)
            assertEquals(File(store.projectDir(other.id), "sources"), otherCopy.file.parentFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bytesOfIncludesProjectSourcesAndPeaks() {
        val root = root()
        try {
            val store = EditorStore(root)
            val project = store.create("Bytes")
            val incoming = File(root, "incoming").apply { mkdirs() }
            val source = store.registerSource(
                project.id,
                File(incoming, "audio.tmp").apply { writeText("0123456789") },
                "bytes-hash",
                "wav",
            )
            val peaks = store.peaksFile(project.id, "bytes-hash").apply {
                writeBytes(ByteArray(7))
            }
            val projectJson = File(store.projectDir(project.id), "project.json")

            assertEquals(
                projectJson.length() + source.file.length() + peaks.length(),
                store.bytesOf(project.id),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sourceAndPeaksRefuseAProjectThatNoLongerExists() {
        val root = root()
        try {
            val store = EditorStore(root)
            val temp = File(root, "incoming.tmp").apply { writeText("audio bytes") }

            assertThrows(IllegalArgumentException::class.java) {
                store.sourceFile("missing", "hash", "wav")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.peaksFile("missing", "hash")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.registerSource("missing", temp, "hash", "wav")
            }

            assertFalse(
                "a refused source must not recreate the deleted project directory",
                File(root, "missing").exists(),
            )
            assertTrue("the caller still owns the temp file", temp.isFile)
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
