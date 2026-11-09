package com.soundify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.cleanvideo.CleanStem
import com.soundify.app.cleanvideo.CleanVideoController
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.CleanVideoStorage
import com.soundify.app.cleanvideo.StemResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanVideoStorageAndroidTest {

    @Test
    fun selectingNewVideoDeletesThePreviousStemDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CleanVideoController.reset(context)
        val dir = CleanVideoStorage.newExportDir(context)
        try {
            CleanVideoController.finish(fakeResult(dir))

            CleanVideoController.reset(context)

            assertFalse("previous stem directory was kept", dir.exists())
            assertNull(CleanVideoController.state.value.result)
        } finally {
            CleanVideoController.reset(context)
            dir.deleteRecursively()
        }
    }

    @Test
    fun deletingStemsRemovesTheDirectoryButKeepsTheSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CleanVideoController.reset(context)
        val dir = CleanVideoStorage.newExportDir(context)
        val source = File(context.cacheDir, "delete-stems-source.mp4").apply { writeText("source") }
        try {
            CleanVideoController.finish(fakeResult(dir, source))

            CleanVideoController.deleteResult(context)

            assertFalse("stem directory was kept", dir.exists())
            assertNull(CleanVideoController.state.value.result)
            assertTrue("the source video must not be deleted", source.exists())
        } finally {
            CleanVideoController.reset(context)
            dir.deleteRecursively()
            source.delete()
        }
    }

    private fun fakeResult(dir: File, source: File = File(dir, "source.mp4")): CleanVideoResult {
        fun stem(kind: CleanStem): StemResult = StemResult(
            kind = kind,
            file = File(dir, "${kind.name.lowercase()}.wav").apply { writeText("stem") },
            durationMs = 1_000L,
            rmsDb = -24f,
            peakDb = -6f,
        )
        return CleanVideoResult(
            voice = stem(CleanStem.VOICE),
            sfx = stem(CleanStem.SFX),
            music = stem(CleanStem.MUSIC),
            clean = stem(CleanStem.CLEAN),
            sourceUri = Uri.fromFile(source),
            sourceName = source.name,
            videoCodec = "h264",
            videoDurationMs = 1_000L,
        )
    }
}
