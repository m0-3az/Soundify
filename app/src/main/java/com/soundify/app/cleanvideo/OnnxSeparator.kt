package com.soundify.app.cleanvideo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Runs the exported MRX mask model. Inputs are magnitude spectrograms with
 * shape [2, bins, frames] for FFT sizes 1024/2048/8192. Outputs are nine real
 * masks in source-major order: music(3), speech(3), sfx(3).
 *
 * Masks stay in ONNX Runtime's native output buffers; the caller reads them
 * through [MaskSet.buffer] and closes the set when done. This avoids copying
 * ~140 MB of masks onto the Java heap.
 */
class OnnxSeparator(modelPath: String, threads: Int = 4) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session: OrtSession = env.createSession(modelPath, options)

    fun run(
        mag1024: FloatArray,
        mag2048: FloatArray,
        mag8192: FloatArray,
        frames: Int,
    ): MaskSet {
        val shape1024 = longArrayOf(2, 513, frames.toLong())
        val shape2048 = longArrayOf(2, 1025, frames.toLong())
        val shape8192 = longArrayOf(2, 4097, frames.toLong())
        val t1024 = OnnxTensor.createTensor(env, FloatBuffer.wrap(mag1024), shape1024)
        val t2048 = OnnxTensor.createTensor(env, FloatBuffer.wrap(mag2048), shape2048)
        val t8192 = OnnxTensor.createTensor(env, FloatBuffer.wrap(mag8192), shape8192)
        try {
            val result = session.run(
                mapOf(
                    "mag_1024" to t1024,
                    "mag_2048" to t2048,
                    "mag_8192" to t8192,
                )
            )
            try {
                val buffers = Array(NAMES.size) { i ->
                    val value = result.get(NAMES[i]).orElseThrow {
                        IllegalStateException("missing ONNX output ${NAMES[i]}")
                    }
                    (value as OnnxTensor).floatBuffer.apply { rewind() }
                }
                return MaskSet(result, buffers)
            } catch (t: Throwable) {
                result.close()
                throw t
            }
        } finally {
            t1024.close()
            t2048.close()
            t8192.close()
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    class MaskSet internal constructor(
        private val result: OrtSession.Result,
        private val buffers: Array<FloatBuffer>,
    ) : Closeable {
        fun buffer(index: Int): FloatBuffer = buffers[index]

        override fun close() {
            result.close()
        }
    }

    companion object {
        val NAMES = arrayOf(
            "music_1024", "music_2048", "music_8192",
            "speech_1024", "speech_2048", "speech_8192",
            "sfx_1024", "sfx_2048", "sfx_8192",
        )
        val BINS = intArrayOf(513, 1025, 4097)
        val FFTS = intArrayOf(1024, 2048, 8192)
    }
}
