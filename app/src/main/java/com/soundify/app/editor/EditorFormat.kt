package com.soundify.app.editor

enum class EditorFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    WAV("WAV (lossless)", "wav", "audio/wav"),
    FLAC("FLAC (lossless)", "flac", "audio/flac"),
    MP3("MP3 192 kbps", "mp3", "audio/mpeg"),
    M4A("M4A AAC 192 kbps", "m4a", "audio/mp4"),
    ;

    /**
     * Rough size of [durationMs] of this format, including a 10% container
     * allowance. Rounded up so a free-space check never under-estimates.
     */
    fun estimatedBytes(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val bytes = durationMs * bytesPerSecond * CONTAINER_OVERHEAD_NUMERATOR
        return (bytes + CONTAINER_OVERHEAD_DENOMINATOR - 1L) / CONTAINER_OVERHEAD_DENOMINATOR
    }

    private val bytesPerSecond: Long
        get() = when (this) {
            WAV -> EXPORT_SAMPLE_RATE * EXPORT_CHANNELS * BYTES_PER_SAMPLE
            FLAC -> EXPORT_SAMPLE_RATE * EXPORT_CHANNELS * BYTES_PER_SAMPLE * FLAC_PERCENT / 100L
            MP3, M4A -> BITRATE_BITS_PER_SECOND / 8L
        }

    private companion object {
        const val EXPORT_SAMPLE_RATE = 44_100L
        const val EXPORT_CHANNELS = 2L
        const val BYTES_PER_SAMPLE = 2L
        const val FLAC_PERCENT = 60L
        const val BITRATE_BITS_PER_SECOND = 192_000L
        const val CONTAINER_OVERHEAD_NUMERATOR = 11L
        const val CONTAINER_OVERHEAD_DENOMINATOR = 10_000L
    }
}
