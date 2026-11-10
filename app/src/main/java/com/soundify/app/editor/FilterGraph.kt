package com.soundify.app.editor

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class FilterPlan(
    val filterComplex: String,
    val outputLabel: String = FilterGraph.OUTPUT_LABEL,
    val expectedDurationMs: Long,
    val outputArgs: String,
    /** Sanitized clips rendered by the graph, in branch order. */
    val inputClips: List<EditorClip>,
)

/**
 * Pure builder for the FFmpeg filter graph of an editor export.
 *
 * [build] sanitizes the project first, so invalid values never reach FFmpeg.
 * Branch `i` reads input `[i:a]` and renders `FilterPlan.inputClips[i]`, so the
 * caller must pass `FilterPlan.inputClips` as inputs `0..n-1` in that order.
 * A single clip skips the join and its crossfade is ignored; multiple clips
 * are joined with each later clip's (capped) crossfade.
 */
object FilterGraph {

    const val OUTPUT_LABEL = "[out]"

    fun build(
        project: EditorProject,
        clips: List<EditorClip>,
        format: EditorFormat,
    ): FilterPlan {
        val sanitized = project.sanitized().clips.associateBy { it.id }
        val ordered = clips.mapNotNull { sanitized[it.id] }
        require(ordered.isNotEmpty()) { "An export needs at least one clip with audio." }

        val crossfades = ordered.zipWithNext().map { (previous, next) ->
            crossfadeBetween(previous, next)
        }
        val (joins, mixLabel) = joinStatements(ordered.size, crossfades)
        val statements = ordered.mapIndexed { index, clip ->
            "[$index:a]${clip.chain()}[a$index]"
        } + joins + "$mixLabel$LIMITER$OUTPUT_LABEL"

        return FilterPlan(
            filterComplex = statements.joinToString(";"),
            outputLabel = OUTPUT_LABEL,
            expectedDurationMs = (ordered.sumOf { it.renderedDurationMs } - crossfades.sum())
                .coerceAtLeast(0L),
            outputArgs = outputArgs(format),
            inputClips = ordered,
        )
    }

    private fun crossfadeBetween(previous: EditorClip, next: EditorClip): Long {
        val cap = minOf(previous.renderedDurationMs, next.renderedDurationMs) / 2L
        return next.crossfadeMs.coerceIn(0L, cap)
    }

    private fun joinStatements(count: Int, crossfades: List<Long>): Pair<List<String>, String> {
        if (count == 1) return emptyList<String>() to "[a0]"
        if (crossfades.all { it == 0L }) {
            val inputs = (0 until count).joinToString("") { "[a$it]" }
            return listOf("${inputs}concat=n=$count:v=0:a=1[mix]") to "[mix]"
        }
        val statements = mutableListOf<String>()
        var current = "[a0]"
        for (index in 1 until count) {
            val output = "[x$index]"
            val join = if (crossfades[index - 1] > 0L) {
                "acrossfade=d=${seconds(crossfades[index - 1])}:c1=tri:c2=tri"
            } else {
                "concat=n=2:v=0:a=1"
            }
            statements += "$current[a$index]$join$output"
            current = output
        }
        return statements to current
    }

    private fun EditorClip.chain(): String {
        val filters = mutableListOf(
            "atrim=start=${seconds(trimStartMs)}:end=${seconds(trimEndMs)}",
            "asetpts=PTS-STARTPTS",
            AFORMAT,
        )
        when (denoise) {
            DenoiseMode.OFF -> Unit
            DenoiseMode.LIGHT -> filters += "afftdn=nr=6:nf=-30"
            DenoiseMode.STRONG -> filters += "afftdn=nr=12:nf=-25"
        }
        if (clarity) {
            filters += "highpass=f=90"
            filters += "equalizer=f=2500:t=q:w=1.2:g=2"
            filters += CLARITY_COMPRESSOR
        }
        filters += eqBands()
        if (hasCustomCompressor) {
            filters += "acompressor=threshold=${linear(compThresholdDb)}" +
                ":ratio=${decimal(compRatio.toDouble(), 2)}" +
                ":makeup=${linear(compMakeupDb)}"
        }
        filters += "volume=${linear(gainDb)}"
        if (speed != 1f) {
            filters += "atempo=${decimal(speed.toDouble(), 6)}"
        }
        if (pitchSemitones != 0) {
            filters += "asetrate=$pitchRate"
            filters += "aresample=$SAMPLE_RATE"
            filters += "atempo=${decimal(2.0.pow(-pitchSemitones / 12.0), 6)}"
        }
        if (fadeInMs > 0L) {
            filters += "afade=t=in:st=0:d=${seconds(fadeInMs)}"
        }
        if (fadeOutMs > 0L) {
            filters += "afade=t=out:st=${seconds(renderedDurationMs - fadeOutMs)}" +
                ":d=${seconds(fadeOutMs)}"
        }
        filters += "aresample=$SAMPLE_RATE"
        filters += AFORMAT
        filters += "asetpts=PTS-STARTPTS"
        return filters.joinToString(",")
    }

    private fun EditorClip.eqBands(): List<String> {
        val gains = when (eqPreset) {
            EqPreset.FLAT -> listOf(eqLowDb, eqMidDb, eqHighDb)
                .takeIf { values -> values.any { it != 0f } }
            EqPreset.VOICE -> listOf(-2f, 3f, 1f)
            EqPreset.BASS -> listOf(4f, 0f, -1f)
            EqPreset.TREBLE -> listOf(-1f, 0f, 4f)
            EqPreset.WARM -> listOf(2f, 1f, -2f)
        } ?: return emptyList()
        return EQ_FREQUENCIES.zip(gains)
            .filter { (_, gain) -> gain != 0f }
            .map { (frequency, gain) ->
                "equalizer=f=$frequency:t=q:w=1:g=${decimal(gain.toDouble(), 2)}"
            }
    }

    private val EditorClip.hasCustomCompressor: Boolean
        get() = abs(compThresholdDb - DEFAULT_COMP_THRESHOLD) > EPSILON ||
            abs(compRatio - DEFAULT_COMP_RATIO) > EPSILON ||
            abs(compMakeupDb - DEFAULT_COMP_MAKEUP) > EPSILON

    private val EditorClip.pitchRate: Int
        get() = (SAMPLE_RATE * 2.0.pow(pitchSemitones / 12.0)).roundToInt()

    private fun outputArgs(format: EditorFormat): String = when (format) {
        EditorFormat.WAV -> "-ar 44100 -ac 2 -c:a pcm_s16le"
        EditorFormat.FLAC -> "-ar 44100 -ac 2 -c:a flac"
        EditorFormat.MP3 -> "-ar 44100 -ac 2 -c:a libmp3lame -b:a 192k"
        EditorFormat.M4A -> "-ar 44100 -ac 2 -c:a aac -b:a 192k"
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

    private fun linear(db: Float): String =
        String.format(Locale.ROOT, "%.6f", 10.0.pow(db / 20.0))

    private fun decimal(value: Double, maxDecimals: Int): String =
        BigDecimal.valueOf(value)
            .setScale(maxDecimals, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    private const val SAMPLE_RATE = 44_100
    private const val AFORMAT =
        "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo"
    private const val LIMITER = "alimiter=limit=0.891:attack=5:release=50:level=false"

    // Clarity adds a fixed gentle 2:1 control above -18 dBFS (0.125893 linear).
    private const val CLARITY_COMPRESSOR = "acompressor=threshold=0.125893:ratio=2"

    // Fixed three-band centres; zero-gain bands are omitted.
    private val EQ_FREQUENCIES = listOf(100, 1_000, 8_000)

    private const val EPSILON = 1e-4f
    private const val DEFAULT_COMP_THRESHOLD = -18f
    private const val DEFAULT_COMP_RATIO = 3f
    private const val DEFAULT_COMP_MAKEUP = 0f
}
