package com.soundify.app.cleanvideo

import kotlin.math.pow

/**
 * The MRX model's first layer is a BatchNorm with running statistics from
 * DnR audio normalized to -27 LUFS. Feeding it native-level phone audio
 * (often -14 LUFS) is outside that distribution and degrades the masks.
 *
 * The official reference implementation applies this gain before inference
 * and divides each stem by it afterwards. [scaleInPlace] does both; the
 * scale is bounded and near-silent input is left alone so a bad measurement
 * cannot amplify noise.
 */
internal object InferenceGain {
    const val TARGET_LUFS = -27.0
    const val MIN_GAIN = 0.01f
    const val MAX_GAIN = 10f

    fun gainFor(measuredLufs: Double?): Float {
        if (measuredLufs == null || !measuredLufs.isFinite()) return 1f
        if (measuredLufs <= Loudness.NEAR_SILENT_LUFS) return 1f
        val gain = 10.0.pow((TARGET_LUFS - measuredLufs) / 20.0)
        return gain.toFloat().coerceIn(MIN_GAIN, MAX_GAIN)
    }

    fun scaleInPlace(samples: Array<FloatArray>, gain: Float) {
        if (gain == 1f) return
        for (channel in samples) {
            for (i in channel.indices) {
                channel[i] *= gain
            }
        }
    }
}
