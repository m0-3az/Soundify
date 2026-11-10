package com.soundify.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.cleanvideo.StreamingCleaner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MediaProbeAndroidTest {

    @Test
    fun wavProbeIsAudioOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.cacheDir, "probe-audio.wav")
        try {
            val frames = 44_100
            WavWriter(wav, sampleRate = 44_100, channels = 2).use { writer ->
                writer.writeInterleaved(FloatArray(frames * 2) { 0.1f }, frames)
            }

            val probe = StreamingCleaner(context).probeVideo(wav)

            assertTrue(probe.hasAudio)
            assertFalse(probe.hasVideo)
            assertNull(probe.videoCodec)
            assertTrue(
                "expected about 1000 ms, got ${probe.durationMs}",
                abs(probe.durationMs - 1_000L) <= 50L,
            )
        } finally {
            wav.delete()
        }
    }
}
