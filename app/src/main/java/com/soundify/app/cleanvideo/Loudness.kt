package com.soundify.app.cleanvideo

/** Measurements printed by FFmpeg's loudnorm filter in `print_format=json` mode. */
data class LoudnessStats(
    val inputI: Double,
    val inputTp: Double,
    val inputLra: Double,
    val inputThresh: Double,
    val targetOffset: Double,
)

object Loudness {
    /** Below this integrated loudness the mix is treated as silence/noise. */
    const val NEAR_SILENT_LUFS = -50.0
    /** Below this true peak the mix is treated as silence/noise. */
    const val NEAR_SILENT_TP = -50.0

    private val number = Regex(
        "\"(input_i|input_tp|input_lra|input_thresh|target_offset)\"\\s*:\\s*\"?(-?(?:[0-9]+(?:\\.[0-9]+)?|inf))\"?"
    )

    /**
     * Parses the JSON block FFmpeg writes to its log during a loudnorm measurement
     * pass. Returns null for a truncated/unreadable block; silence is reported as
     * `-inf` and parsed normally so the caller can skip amplification.
     */
    fun parseLoudnormJson(log: String): LoudnessStats? {
        val values = number.findAll(log).associate { match ->
            match.groupValues[1] to parseNumber(match.groupValues[2])
        }
        return LoudnessStats(
            inputI = values["input_i"] ?: return null,
            inputTp = values["input_tp"] ?: return null,
            inputLra = values["input_lra"] ?: return null,
            inputThresh = values["input_thresh"] ?: return null,
            targetOffset = values["target_offset"] ?: return null,
        )
    }

    /**
     * A clean mix this quiet is effectively silence. Running loudnorm on it
     * would add 30+ dB of gain and make separation noise audible.
     */
    fun isNearSilent(stats: LoudnessStats): Boolean =
        stats.inputI <= NEAR_SILENT_LUFS || stats.inputTp <= NEAR_SILENT_TP

    private fun parseNumber(raw: String): Double? = when {
        raw.equals("-inf", ignoreCase = true) -> Double.NEGATIVE_INFINITY
        raw.equals("inf", ignoreCase = true) -> Double.POSITIVE_INFINITY
        else -> raw.toDoubleOrNull()
    }
}
