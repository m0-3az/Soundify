package com.soundify.app.cleanvideo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Iterative radix-2 FFT with an orthonormal (1/sqrt(N)) scaling on both the
 * forward and inverse transform. This mirrors torch.stft/istft(normalized=True).
 *
 * Not thread-safe: the scratch buffers are reused.
 */
class FftPlan(val n: Int) {
    private val rev = IntArray(n)
    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)
    private val tmpRe = DoubleArray(n)
    private val tmpIm = DoubleArray(n)
    val scale = 1.0 / sqrt(n.toDouble())

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 0 until n) {
            rev[i] = j
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
        }
        for (i in 0 until n / 2) {
            val angle = 2.0 * PI * i / n
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val r = rev[i]
            tmpRe[i] = re[r]
            tmpIm[i] = im[r]
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                var twiddle = 0
                while (k < half) {
                    val wr = cosTable[twiddle]
                    val wi = if (inverse) sinTable[twiddle] else -sinTable[twiddle]
                    val a = start + k
                    val b = a + half
                    val br = tmpRe[b]
                    val bi = tmpIm[b]
                    val vr = br * wr - bi * wi
                    val vi = br * wi + bi * wr
                    val ar = tmpRe[a]
                    val ai = tmpIm[a]
                    tmpRe[a] = ar + vr
                    tmpIm[a] = ai + vi
                    tmpRe[b] = ar - vr
                    tmpIm[b] = ai - vi
                    k++
                    twiddle += step
                }
                start += len
            }
            len = len shl 1
        }
        for (i in 0 until n) {
            re[i] = tmpRe[i] * scale
            im[i] = tmpIm[i] * scale
        }
    }

    companion object {
        private val plans = HashMap<Int, FftPlan>()
        private val threadPlans = ThreadLocal.withInitial { HashMap<Int, FftPlan>() }

        @Synchronized
        fun of(n: Int): FftPlan = plans.getOrPut(n) { FftPlan(n) }

        /** A per-thread plan, safe to use from the iSTFT worker pool. */
        fun ofThread(n: Int): FftPlan = threadPlans.get()!!.getOrPut(n) { FftPlan(n) }
    }
}
