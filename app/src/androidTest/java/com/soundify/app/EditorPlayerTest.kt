package com.soundify.app

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.audio.WavWriter
import com.soundify.app.editor.EditorClip
import com.soundify.app.editor.EditorPlayer
import com.soundify.app.editor.PlaybackItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class EditorPlayerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val players = mutableListOf<EditorPlayer>()
    private lateinit var root: File
    private lateinit var source: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "editor-player-${UUID.randomUUID()}").apply { mkdirs() }
        source = File(root, "tone.wav")
        writeTone(source)
    }

    @After
    fun tearDown() {
        players.forEach { player -> onMain { player.release() } }
        players.clear()
        root.deleteRecursively()
    }

    @Test
    fun playsTheTrimAndStopsAtTheTrimEnd() {
        val player = newPlayer()
        val finished = CountDownLatch(1)
        player.onFinished = { finished.countDown() }

        onMain { player.load(clip(trimStartMs = 500L, trimEndMs = 1_500L), source) }

        assertEquals("load must park at the trim start", 500L, player.positionMs.value)
        await("preview never started") { player.isPlaying.value }
        val startedAt = player.positionMs.value
        assertTrue("expected playback near 500 ms, got $startedAt", startedAt in 500L..900L)
        assertTrue("preview never finished", finished.await(10, TimeUnit.SECONDS))

        val endedAt = player.positionMs.value
        assertEquals("preview must stop at the trim end", 1_500L, endedAt)
        assertTrue("preview reported time past the trim end: $endedAt", endedAt <= 1_600L)
        assertFalse(player.isPlaying.value)
        assertEquals(0, player.currentIndex.value)
    }

    @Test
    fun gainChangesDoNotThrowAndPlaybackContinues() {
        val player = newPlayer()
        onMain {
            player.load(clip(trimStartMs = 0L, trimEndMs = 2_000L), source)
            // 0.5 linear gain.
            player.setGainDb(-6.0206f)
        }

        await("preview never started") { player.isPlaying.value }
        val before = player.positionMs.value
        onMain {
            player.setGainDb(-12f)
            // A boost is capped at unity for preview and must not throw.
            player.setGainDb(24f)
        }
        Thread.sleep(250)

        assertTrue(
            "playback stopped after gain changes at ${player.positionMs.value}",
            player.isPlaying.value || player.positionMs.value > before,
        )
    }

    @Test
    fun unsupportedPlaybackParamsFallBackWithoutCrashing() {
        val player = newPlayer()
        onMain {
            player.load(clip(trimStartMs = 0L, trimEndMs = 2_000L), source)
            // PlaybackParams rejects a negative speed; the player must catch it,
            // report the fallback and keep playing at 1x.
            player.setSpeed(-1f)
        }

        await("preview never started") { player.isPlaying.value }
        await("previewUnavailable was not reported") { player.previewUnavailable.value }
        val before = player.positionMs.value
        Thread.sleep(250)
        assertTrue(
            "playback stopped after the fallback: ${player.positionMs.value}",
            player.positionMs.value > before,
        )

        onMain { player.setSpeed(1f) }
        assertFalse("returning to 1x must clear previewUnavailable", player.previewUnavailable.value)
        assertTrue("the player must still be playing", player.isPlaying.value)
    }

    @Test
    fun advancesThroughTheQueueInOrderAndStopsAfterTheLastClip() {
        val player = newPlayer()
        val changes = mutableListOf<Int>()
        val finished = CountDownLatch(1)
        player.onClipChanged = { changes += it }
        player.onFinished = { finished.countDown() }

        onMain {
            player.load(
                listOf(
                    PlaybackItem(clip(0L, 350L, id = "first"), source),
                    PlaybackItem(clip(0L, 450L, id = "second"), source),
                ),
            )
        }

        assertTrue("queue never finished", finished.await(10, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1), changes)
        assertEquals(1, player.currentIndex.value)
        assertEquals("the queue must stop at the last clip", 450L, player.positionMs.value)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun seekIsClampedToTheTrimRange() {
        val player = newPlayer()
        onMain { player.load(clip(trimStartMs = 500L, trimEndMs = 1_500L), source) }
        await("preview never started") { player.isPlaying.value }

        onMain { player.pause() }
        assertFalse(player.isPlaying.value)
        onMain { player.seekToSourceMs(0L) }
        assertEquals("seek must clamp up to the trim start", 500L, player.positionMs.value)
        onMain { player.seekToSourceMs(9_999L) }
        assertEquals("seek must clamp down to the trim end", 1_500L, player.positionMs.value)

        onMain { player.resume() }
        await("resume never started") { player.isPlaying.value }
        val resumedAt = player.positionMs.value
        assertTrue(
            "resume at the trim end must restart from the trim start, got $resumedAt",
            resumedAt in 500L..900L,
        )
    }

    @Test
    fun seekBeforePrepareIsAppliedWhenPlaybackStarts() {
        val player = newPlayer()
        onMain {
            player.load(clip(trimStartMs = 0L, trimEndMs = 2_000L), source)
            // prepareAsync has not returned yet, so this seek must be remembered.
            player.seekToSourceMs(1_000L)
        }

        await("preview never started") { player.isPlaying.value }
        val startedAt = player.positionMs.value
        assertTrue(
            "a seek before prepare was lost; playback started at $startedAt",
            startedAt >= 900L,
        )
    }

    @Test
    fun releaseStopsPlaybackAndIsSafeTwice() {
        val player = newPlayer()
        val finished = CountDownLatch(1)
        player.onFinished = { finished.countDown() }
        onMain { player.load(clip(0L, 2_000L), source) }
        await("preview never started") { player.isPlaying.value }

        onMain {
            player.release()
            player.release()
        }

        assertFalse(player.isPlaying.value)
        assertEquals(0L, player.positionMs.value)
        assertEquals(0, player.currentIndex.value)
        assertFalse(
            "release must not report a finished sequence",
            finished.await(100, TimeUnit.MILLISECONDS),
        )
    }

    private fun newPlayer(): EditorPlayer = EditorPlayer().also { players += it }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(
        message: String,
        timeoutMs: Long = 10_000L,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        fail(message)
    }

    private fun clip(trimStartMs: Long, trimEndMs: Long, id: String = "tone"): EditorClip =
        EditorClip(
            id = id,
            sourceHash = "tone-hash",
            sourceFile = "sources/tone.wav",
            displayName = "tone.wav",
            sourceDurationMs = 2_000L,
            sourceSampleRate = 44_100,
            sourceChannels = 1,
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
        )

    private fun writeTone(file: File) {
        val sampleRate = 44_100
        val frames = sampleRate * 2
        WavWriter(file, sampleRate = sampleRate, channels = 1).use { writer ->
            val samples = FloatArray(frames) { index ->
                (sin(2.0 * PI * 440.0 * index / sampleRate) * 0.5).toFloat()
            }
            writer.writeInterleaved(samples, frames)
        }
    }
}
