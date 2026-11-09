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
    private val number = Regex(
        "\"(input_i|input_tp|input_lra|input_thresh|target_offset)\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?"
    )

    /**
     * Parses the JSON block FFmpeg writes to its log during a loudnorm measurement
     * pass. Returns null for silence or truncated output so the caller can fall
     * back to a single-pass normalization instead of failing the export.
     */
    fun parseLoudnormJson(log: String): LoudnessStats? {
        val values = number.findAll(log).associate { match ->
            match.groupValues[1] to match.groupValues[2].toDoubleOrNull()
        }
        return LoudnessStats(
            inputI = values["input_i"] ?: return null,
            inputTp = values["input_tp"] ?: return null,
            inputLra = values["input_lra"] ?: return null,
            inputThresh = values["input_thresh"] ?: return null,
            targetOffset = values["target_offset"] ?: return null,
        )
    }
}
