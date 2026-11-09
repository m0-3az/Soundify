package com.soundify.app.cleanvideo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CleanVideoStorageTest {

    @Test
    fun deletesOnlyTimestampedChildrenOfTheExportRoot() {
        val root = createTempDirectory("cleanvideo-root").toFile()
        val runDir = File(root, "1234567890").apply { mkdirs() }
        val stem = File(runDir, "clean.wav").apply { writeText("stem") }
        val outside = createTempDirectory("cleanvideo-outside").toFile()
        val outsideFile = File(outside, "keep.txt").apply { writeText("keep") }

        try {
            assertTrue(CleanVideoStorage.deleteExportDir(root, runDir))
            assertFalse(runDir.exists())
            assertTrue(outsideFile.exists())

            // The root itself must never be deleted through this path.
            assertFalse(CleanVideoStorage.deleteExportDir(root, root))
            assertTrue(root.exists())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
            stem.delete()
        }
    }

    @Test
    fun refusesDirectoriesOutsideTheExportRoot() {
        val root = createTempDirectory("cleanvideo-root").toFile()
        val otherRoot = createTempDirectory("cleanvideo-other").toFile()
        val otherRun = File(otherRoot, "1234567890").apply { mkdirs() }
        val keep = File(otherRun, "voice.wav").apply { writeText("keep") }

        try {
            assertFalse(CleanVideoStorage.deleteExportDir(root, otherRun))
            assertTrue(keep.exists())
            assertFalse(CleanVideoStorage.deleteExportDir(root, null))
        } finally {
            root.deleteRecursively()
            otherRoot.deleteRecursively()
        }
    }
}
