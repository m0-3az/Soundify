package com.soundify.app.cleanvideo

import java.io.File

data class NormalizationOutcome(
    val applied: Boolean,
    val detail: String,
)

/**
 * Two-pass loudness match for the cleaned mix.
 *
 * The first pass measures the mix; the second applies a linear gain with a
 * true-peak limit. Near-silent mixes (and unreadable measurements) are copied
 * at the existing 6 dB headroom instead of being amplified by tens of dB,
 * which would only make noise audible.
 */
class CleanMixNormalizer {

    fun normalize(input: File, output: File): NormalizationOutcome {
        val measure = Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(input)} " +
                "-af loudnorm=I=$TARGET_LUFS:TP=$TARGET_TP:LRA=11:print_format=json -f null -"
        )
        val stats = Loudness.parseLoudnormJson(measure)
            ?: return keepRaw(input, output, "Loudness could not be measured; kept the unboosted clean mix.")

        if (Loudness.isNearSilent(stats)) {
            return keepRaw(
                input,
                output,
                "The clean mix is nearly silent; kept it unboosted to avoid amplifying noise.",
            )
        }

        val filter = "loudnorm=I=$TARGET_LUFS:TP=$TARGET_TP:LRA=11" +
            ":measured_I=${stats.inputI}" +
            ":measured_TP=${stats.inputTp}" +
            ":measured_LRA=${stats.inputLra}" +
            ":measured_thresh=${stats.inputThresh}" +
            ":offset=${stats.targetOffset}:linear=true"
        Ffmpeg.run(
            "-y -i ${Ffmpeg.quote(input)} -af \"$filter\" -ar 44100 -c:a pcm_s16le ${Ffmpeg.quote(output)}"
        )
        return NormalizationOutcome(
            applied = true,
            detail = "Matched the clean mix to -16 LUFS / -1.5 dBTP.",
        )
    }

    private fun keepRaw(input: File, output: File, detail: String): NormalizationOutcome {
        input.copyTo(output, overwrite = true)
        return NormalizationOutcome(applied = false, detail = detail)
    }

    companion object {
        const val TARGET_LUFS = -16.0
        const val TARGET_TP = -1.5
    }
}
