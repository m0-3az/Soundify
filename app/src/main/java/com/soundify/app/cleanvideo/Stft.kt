package com.soundify.app.cleanvideo

import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Complex spectrogram for one or more channels, laid out as [channel][bin][frame]. */
class StereoSpec(
    val nFft: Int,
    val channels: Int,
    val bins: Int,
    val frames: Int,
) {
    val re = FloatArray(channels * bins * frames)
    val im = FloatArray(channels * bins * frames)
    var mag: FloatArray? = FloatArray(channels * bins * frames)

    fun index(channel: Int, bin: Int, frame: Int): Int =
        (channel * bins + bin) * frames + frame
}

object Windows {
    private val periodic = HashMap<Int, FloatArray>()
    private val nonPeriodic = HashMap<Int, FloatArray>()

    @Synchronized
    fun hann(n: Int): FloatArray = periodic.getOrPut(n) {
        FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / n)).toFloat() }
    }

    @Synchronized
    fun hannNonPeriodic(n: Int): FloatArray = nonPeriodic.getOrPut(n) {
        if (n <= 1) FloatArray(max(n, 1)) { 1f }
        else FloatArray(n) { i -> (0.5 - 0.5 * cos(PI * i / (n - 1))).toFloat() }
    }
}

object Stft {
    const val HOP = 256

    /** audio: [channels][samples], all channels the same length. */
    fun forward(audio: Array<FloatArray>, nFft: Int, hop: Int = HOP): StereoSpec {
        require(audio.isNotEmpty()) { "no channels" }
        val channels = audio.size
        val length = audio[0].size
        require(audio.all { it.size == length }) { "channel lengths differ" }
        val frames = 1 + (length - 1) / hop
        val bins = nFft / 2 + 1
        val spec = StereoSpec(nFft, channels, bins, frames)
        val plan = FftPlan.ofThread(nFft)
        val window = Windows.hann(nFft)
        val pad = nFft / 2
        val frameRe = DoubleArray(nFft)
        val frameIm = DoubleArray(nFft)

        for (ch in 0 until channels) {
            val padded = reflectPad(audio[ch], pad)
            for (t in 0 until frames) {
                val offset = t * hop
                for (i in 0 until nFft) {
                    frameRe[i] = (padded[offset + i] * window[i]).toDouble()
                    frameIm[i] = 0.0
                }
                plan.transform(frameRe, frameIm, inverse = false)
                for (k in 0 until bins) {
                    val idx = spec.index(ch, k, t)
                    spec.re[idx] = frameRe[k].toFloat()
                    spec.im[idx] = frameIm[k].toFloat()
                }
            }
        }

        val mag = spec.mag!!
        for (i in mag.indices) {
            val r = spec.re[i]
            val m = spec.im[i]
            mag[i] = sqrt(r * r + m * m)
        }
        return spec
    }

    /**
     * Apply a real mask to the complex spectrogram and invert.
     * Returns [channels][length].
     */
    fun inverse(
        spec: StereoSpec,
        mask: FloatBuffer,
        hop: Int = HOP,
        length: Int,
    ): Array<FloatArray> {
        val nFft = spec.nFft
        val bins = spec.bins
        val frames = spec.frames
        val channels = spec.channels
        val plan = FftPlan.ofThread(nFft)
        val window = Windows.hann(nFft)
        val pad = nFft / 2
        val fullRe = DoubleArray(nFft)
        val fullIm = DoubleArray(nFft)
        val result = Array(channels) { FloatArray(length) }

        for (ch in 0 until channels) {
            val out = FloatArray(length + nFft)
            val weight = FloatArray(length + nFft)
            for (t in 0 until frames) {
                for (k in 0 until bins) {
                    val idx = spec.index(ch, k, t)
                    val m = mask.get(idx)
                    fullRe[k] = (spec.re[idx] * m).toDouble()
                    fullIm[k] = (spec.im[idx] * m).toDouble()
                }
                for (k in bins until nFft) {
                    val mirror = nFft - k
                    val idx = spec.index(ch, mirror, t)
                    val m = mask.get(idx)
                    fullRe[k] = (spec.re[idx] * m).toDouble()
                    fullIm[k] = (-spec.im[idx] * m).toDouble()
                }
                plan.transform(fullRe, fullIm, inverse = true)
                val offset = t * hop
                for (i in 0 until nFft) {
                    val w = window[i]
                    out[offset + i] += (fullRe[i] * w).toFloat()
                    weight[offset + i] += w * w
                }
            }
            for (i in out.indices) {
                out[i] /= max(weight[i], 1e-8f)
            }
            out.copyInto(result[ch], 0, pad, pad + length)
        }
        return result
    }

    private fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        if (pad <= 0) return x
        if (x.size <= pad + 1) {
            val out = FloatArray(x.size + 2 * pad)
            x.copyInto(out, pad)
            return out
        }
        val out = FloatArray(x.size + 2 * pad)
        for (i in 0 until pad) out[i] = x[pad - i]
        x.copyInto(out, pad)
        for (i in 0 until pad) out[pad + x.size + i] = x[x.size - 2 - i]
        return out
    }
}
