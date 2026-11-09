package com.soundify.app.audio

import com.soundify.app.data.Stem

object MixMath {

    fun panGains(pan: Float): Pair<Float, Float> {
        val p = pan.coerceIn(-1f, 1f)
        return if (p < 0f) 1f to (1f + p) else (1f - p) to 1f
    }

    fun effectiveGain(stem: Stem, anySolo: Boolean): Float {
        if (stem.muted) return 0f
        if (anySolo && !stem.soloed) return 0f
        return stem.gain.coerceIn(0f, 2f)
    }
}
