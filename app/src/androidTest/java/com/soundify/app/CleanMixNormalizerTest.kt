package com.soundify.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.soundify.app.cleanvideo.CleanMixNormalizer
import com.soundify.app.cleanvideo.Ffmpeg
import com.soundify.app.cleanvideo.Loudness
import com.soundify.app.cleanvideo.Wav
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class CleanMixNormalizerTest {

    @Test
    fun nearSilentMixIsNotAmplified() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "near-silent-in.wav")
        val output = File(context.cacheDir, "near-silent-out.wav")
        val samples = Array(2) { channel ->
            FloatArray(5 * 44100) { i ->
                // A few LSBs of noise, far below the -50 LUFS guard.
                val tick = if ((i + channel) % 50 == 0) 0.00005f else -0.00004f
                tick
            }
        }
        try {
            Wav.writePcm16(input, samples, 44100)
            val before = Wav.analyze(input)

            val outcome = CleanMixNormalizer().normalize(input, output)

            assertFalse("near-silent mix was normalized", outcome.applied)
            assertTrue(
                "note should explain the skipped gain: ${outcome.detail}",
                outcome.detail.contains("silent", ignoreCase = true),
            )
            val after = Wav.analyze(output)
            assertTrue(
                "output was amplified by more than 1 dB " +
                    "(input ${before.rmsDb}, output ${after.rmsDb})",
                after.rmsDb <= before.rmsDb + 1f,
            )
            assertTrue("output was attenuated unexpectedly", after.rmsDb >= before.rmsDb - 1f)
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun normalMixStillMatchesTheLoudnessTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "normal-in.wav")
        val output = File(context.cacheDir, "normal-out.wav")
        val samples = Array(2) {
            FloatArray(5 * 44100) { i ->
                (0.15 * sin(2.0 * PI * 1000.0 * i / 44100.0)).toFloat()
            }
        }
        try {
            Wav.writePcm16(input, samples, 44100)

            val outcome = CleanMixNormalizer().normalize(input, output)

            assertTrue("a normal mix should be normalized", outcome.applied)
            val session = FFmpegKit.execute(
                "-hide_banner -i ${output.absolutePath} " +
                    "-af loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json -f null -"
            )
            assertTrue("could not measure the normalized mix", ReturnCode.isSuccess(session.returnCode))
            val stats = Loudness.parseLoudnormJson(session.output ?: "")
                ?: error("loudnorm did not print a complete measurement")
            assertTrue(
                "normalized mix was ${stats.inputI} LUFS, expected -16 ±1",
                abs(stats.inputI + 16.0) <= 1.0,
            )
            assertTrue(
                "normalized true peak was ${stats.inputTp} dBTP, expected <= -1.5",
                stats.inputTp <= -1.4,
            )
        } finally {
            input.delete()
            output.delete()
        }
    }

    @Test
    fun ffmpegFailureCarriesTheLogTail() {
        val error = runCatching {
            Ffmpeg.run("-y -i /definitely/missing/soundify-input.mp4 -f null -")
        }.exceptionOrNull()

        val message = error?.message.orEmpty()
        assertTrue("no exception message", message.isNotBlank())
        assertTrue("the FFmpeg log tail is missing: $message", message.contains("No such file"))
    }
}
