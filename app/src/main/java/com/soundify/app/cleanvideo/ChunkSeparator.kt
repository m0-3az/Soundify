package com.soundify.app.cleanvideo

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Separates one 44.1 kHz stereo chunk into music / speech / sfx.
 * Masks stay in native ONNX buffers; the three source reconstructions run in
 * parallel and each worker owns its FFT plans.
 */
class ChunkSeparator(modelPath: String) : Closeable {
    private val onnx = OnnxSeparator(modelPath)
    private val ffts = intArrayOf(1024, 2048, 8192)
    private val workers: ExecutorService = Executors.newFixedThreadPool(3)

    /** input: [2][samples]. Returns [3 sources][2 channels][samples]. */
    fun separate(input: Array<FloatArray>): Array<Array<FloatArray>> {
        require(input.size == 2) { "stereo input required" }
        val length = input[0].size
        val specs = Array(3) { i -> Stft.forward(input, ffts[i]) }
        val frames = specs[0].frames
        var mags: Array<FloatArray>? = Array(3) { i -> specs[i].mag!! }
        val masks = onnx.run(mags!![0], mags!![1], mags!![2], frames)
        for (i in 0 until 3) specs[i].mag = null
        mags = null

        val result = Array(3) { Array(2) { FloatArray(length) } }
        try {
            val jobs = (0 until 3).map { src ->
                workers.submit(Callable {
                    for (res in 0 until 3) {
                        val part = Stft.inverse(
                            specs[res],
                            masks.buffer(src * 3 + res),
                            Stft.HOP,
                            length,
                        )
                        for (ch in 0 until 2) {
                            val a = result[src][ch]
                            val p = part[ch]
                            for (i in 0 until length) a[i] += p[i]
                        }
                    }
                })
            }
            jobs.forEach { it.get() }
        } finally {
            masks.close()
        }
        return result
    }

    override fun close() {
        workers.shutdownNow()
        onnx.close()
    }
}
