package com.soundify.app

import com.soundify.app.audio.MixMath
import com.soundify.app.data.Stem
import com.soundify.app.data.StemRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MixMathTest {

    @Test
    fun centerPanKeepsBothChannels() {
        assertEquals(1f to 1f, MixMath.panGains(0f))
    }

    @Test
    fun hardLeftSilencesRight() {
        assertEquals(1f to 0f, MixMath.panGains(-1f))
    }

    @Test
    fun hardRightSilencesLeft() {
        assertEquals(0f to 1f, MixMath.panGains(1f))
    }

    @Test
    fun mutedStemHasNoGain() {
        val stem = stem(muted = true)
        assertEquals(0f, MixMath.effectiveGain(stem, anySolo = false), 0.0001f)
    }

    @Test
    fun soloExcludesOtherStems() {
        val stem = stem(soloed = false)
        assertEquals(0f, MixMath.effectiveGain(stem, anySolo = true), 0.0001f)
    }

    @Test
    fun gainIsClampedToRange() {
        val stem = stem(gain = 5f)
        assertEquals(2f, MixMath.effectiveGain(stem, anySolo = false), 0.0001f)
    }

    private fun stem(
        gain: Float = 1f,
        muted: Boolean = false,
        soloed: Boolean = false
    ) = Stem(
        role = StemRole.VOCALS,
        displayName = "Vocals",
        filePath = "/tmp/vocals.wav",
        format = "audio/raw",
        sampleRate = 44100,
        channels = 2,
        durationMs = 1000,
        gain = gain,
        muted = muted,
        soloed = soloed
    )
}
