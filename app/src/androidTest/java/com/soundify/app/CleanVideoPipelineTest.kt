package com.soundify.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.cleanvideo.StreamingCleaner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanVideoPipelineTest {
    @Test
    fun separatesShortVideo() = run("clip5.mp4", 10_000)

    @Test
    fun separatesLongerVideo() = run("clip60.mp4", 60_000)

    private fun run(asset: String, minimumVideoBytes: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "cleanvideo_$asset")
        instrumentation.context.assets.open("cleanvideo/$asset").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }

        val started = System.currentTimeMillis()
        val result = runBlocking {
            StreamingCleaner(context).clean(Uri.fromFile(video), asset) { _, _ -> }
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("cleaned wav missing", result.cleanedWav.length() > 100_000)
        assertTrue("mp3 missing", result.mp3.length() > 10_000)
        assertTrue("m4a missing", result.m4a.length() > 10_000)
        assertNotNull("video output missing", result.video)
        assertTrue("video output empty", result.video!!.length() > minimumVideoBytes)
        android.util.Log.i(
            "CleanVideoTest",
            "$asset elapsed=${elapsed}ms wav=${result.cleanedWav.length()} " +
                "mp3=${result.mp3.length()} m4a=${result.m4a.length()} video=${result.video!!.length()}",
        )
    }
}
