package com.soundify.app.ui

import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleanVideoScreenTest {

    @Test
    fun progressTweenFollowsTheRemainingChunkTime() {
        assertEquals(5_000, progressAnimationMillis(etaSeconds = 50, chunkIndex = 5, chunkCount = 15))
        assertEquals(1_000, progressAnimationMillis(etaSeconds = 1, chunkIndex = 9, chunkCount = 10))
        assertEquals(10_000, progressAnimationMillis(etaSeconds = 100, chunkIndex = 1, chunkCount = 2))
        assertEquals(700, progressAnimationMillis(etaSeconds = null, chunkIndex = 0, chunkCount = 10))
    }

    @Test
    fun pendingSaveKeysSurviveActivityRecreation() {
        val audio = PendingSave.Audio(AudioFormat.FLAC)
        val video = PendingSave.Video(VideoFormat.MKV)

        assertEquals(audio, pendingSave(audio.key()))
        assertEquals(video, pendingSave(video.key()))
        assertNull(pendingSave(null))
        assertNull(pendingSave("audio:NOT_A_FORMAT"))
        assertNull(pendingSave("unknown"))
    }
}
