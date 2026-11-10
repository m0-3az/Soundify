package com.soundify.app.editor

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.roundToLong

@Serializable
enum class DenoiseMode { OFF, LIGHT, STRONG }

@Serializable
enum class EqPreset { FLAT, VOICE, BASS, TREBLE, WARM }

@Serializable
data class EditorClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceHash: String,
    val sourceFile: String,
    val displayName: String,
    val sourceDurationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val gainDb: Float = 0f,
    val speed: Float = 1f,
    val pitchSemitones: Int = 0,
    val denoise: DenoiseMode = DenoiseMode.OFF,
    val clarity: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,
    val compThresholdDb: Float = -18f,
    val compRatio: Float = 3f,
    val compMakeupDb: Float = 0f,
    val crossfadeMs: Long = 0L,
)

@Serializable
data class EditorProject(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val outputSampleRate: Int = 44_100,
    val outputChannels: Int = 2,
    val clips: List<EditorClip> = emptyList(),
) {
    fun sanitized(): EditorProject {
        val seenIds = HashSet<String>()
        val clipsWithoutCrossfade = clips.mapNotNull { clip ->
            if (clip.sourceHash.isBlank()) return@mapNotNull null
            if (!seenIds.add(clip.id)) return@mapNotNull null
            clip.sanitized()
        }
        val cleanClips = clipsWithoutCrossfade.mapIndexed { index, clip ->
            if (index == 0) {
                clip.copy(crossfadeMs = 0L)
            } else {
                val shorter = minOf(
                    clipsWithoutCrossfade[index - 1].renderedDurationMs,
                    clip.renderedDurationMs,
                )
                clip.copy(crossfadeMs = clip.crossfadeMs.coerceIn(0L, shorter / 2L))
            }
        }
        return copy(clips = cleanClips)
    }

    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

/**
 * Splits [clipId] at [atSourceMs] into two clips that share the source and keep
 * the outer fades. The split point is a hard join: the second half's crossfade
 * is 0. The project is returned unchanged when the clip is missing or shorter
 * than two [MIN_CLIP_MS] halves.
 */
fun EditorProject.splitClip(clipId: String, atSourceMs: Long): EditorProject {
    val index = clips.indexOfFirst { it.id == clipId }
    if (index < 0) return this
    val clip = clips[index]
    val minSplit = clip.trimStartMs + MIN_CLIP_MS
    val maxSplit = clip.trimEndMs - MIN_CLIP_MS
    if (maxSplit < minSplit) return this
    val split = atSourceMs.coerceIn(minSplit, maxSplit)
    val speedFactor = if (clip.speed.isFinite() && clip.speed > 0f) clip.speed else 1f
    val firstRendered = ((split - clip.trimStartMs) / speedFactor).roundToLong().coerceAtLeast(1L)
    val secondRendered = ((clip.trimEndMs - split) / speedFactor).roundToLong().coerceAtLeast(1L)
    val first = clip.copy(
        id = UUID.randomUUID().toString(),
        trimEndMs = split,
        fadeInMs = clip.fadeInMs.coerceIn(0L, firstRendered),
        fadeOutMs = 0L,
        crossfadeMs = if (index == 0) 0L else clip.crossfadeMs,
    )
    val second = clip.copy(
        id = UUID.randomUUID().toString(),
        trimStartMs = split,
        fadeInMs = 0L,
        fadeOutMs = clip.fadeOutMs.coerceIn(0L, secondRendered),
        crossfadeMs = 0L,
    )
    val updated = clips.toMutableList()
    updated.removeAt(index)
    updated.add(index, first)
    updated.add(index + 1, second)
    return copy(clips = updated)
}

val EditorProject.isFutureSchema: Boolean
    get() = schemaVersion > EditorProject.CURRENT_SCHEMA

val EditorProject.durationMs: Long
    get() = (clips.sumOf { it.renderedDurationMs } - clips.sumOf { it.crossfadeMs })
        .coerceAtLeast(0L)

val EditorClip.renderedDurationMs: Long
    get() {
        val trimmed = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        if (trimmed == 0L) return 1L
        return (trimmed / speedFactor).roundToLong().coerceAtLeast(1L)
    }

/** Project-relative time for [sourceMs] inside the clip at [clipIndex]. */
fun EditorProject.positionAt(clipIndex: Int, sourceMs: Long): Long {
    if (clips.isEmpty()) return 0L
    val index = clipIndex.coerceIn(0, clips.lastIndex)
    val clip = clips[index]
    val before = clips.subList(0, index).sumOf { it.renderedDurationMs }
    val offset = ((sourceMs - clip.trimStartMs).coerceAtLeast(0L) / clip.speedFactor)
        .roundToLong()
    return (before + offset).coerceIn(0L, durationMs)
}

/** Clip index and source position for a project-relative [positionMs]. */
fun EditorProject.locate(positionMs: Long): Pair<Int, Long> {
    if (clips.isEmpty()) return 0 to 0L
    var remaining = positionMs.coerceAtLeast(0L)
    clips.forEachIndexed { index, clip ->
        val rendered = clip.renderedDurationMs
        if (remaining < rendered || index == clips.lastIndex) {
            val offset = (remaining.coerceAtMost(rendered) * clip.speedFactor).roundToLong()
            val source = (clip.trimStartMs + offset).coerceIn(clip.trimStartMs, clip.trimEndMs)
            return index to source
        }
        remaining -= rendered
    }
    return clips.lastIndex to clips.last().trimEndMs
}

/**
 * Position of [sourceMs] as a fraction of the whole source. The waveform draws
 * the full source envelope, so the playhead and the trim selection must use
 * the same coordinate system.
 */
fun waveformFraction(sourceMs: Long, sourceDurationMs: Long): Float {
    if (sourceDurationMs <= 0L) return 0f
    return (sourceMs.toFloat() / sourceDurationMs).coerceIn(0f, 1f)
}

/**
 * Source position for a tap at [fraction] of the whole source, clamped to the
 * clip's trim range.
 */
fun waveformSourceMs(
    fraction: Float,
    sourceDurationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
): Long {
    val position = (fraction.coerceIn(0f, 1f) * sourceDurationMs.coerceAtLeast(1L))
        .roundToLong()
    return position.coerceIn(trimStartMs, trimEndMs)
}

private const val MIN_CLIP_MS = 250L

private fun EditorClip.sanitized(): EditorClip {
    val duration = sourceDurationMs.coerceAtLeast(MIN_CLIP_MS)
    val trimStart = trimStartMs.coerceIn(0L, duration - MIN_CLIP_MS)
    val trimEnd = trimEndMs.coerceIn(trimStart + MIN_CLIP_MS, duration)
    val cleanSpeed = speed.finiteOr(1f).coerceIn(0.5f, 2f)
    val rendered = ((trimEnd - trimStart) / cleanSpeed).roundToLong().coerceAtLeast(1L)
    val fadeIn = fadeInMs.coerceIn(0L, rendered)
    val fadeOut = fadeOutMs.coerceIn(0L, rendered - fadeIn)
    return copy(
        sourceDurationMs = duration,
        trimStartMs = trimStart,
        trimEndMs = trimEnd,
        fadeInMs = fadeIn,
        fadeOutMs = fadeOut,
        gainDb = gainDb.finiteOr(0f).coerceIn(-24f, 24f),
        speed = cleanSpeed,
        pitchSemitones = pitchSemitones.coerceIn(-12, 12),
        eqLowDb = eqLowDb.finiteOr(0f).coerceIn(-12f, 12f),
        eqMidDb = eqMidDb.finiteOr(0f).coerceIn(-12f, 12f),
        eqHighDb = eqHighDb.finiteOr(0f).coerceIn(-12f, 12f),
        compThresholdDb = compThresholdDb.finiteOr(-18f).coerceIn(-40f, 0f),
        compRatio = compRatio.finiteOr(3f).coerceIn(1f, 10f),
        compMakeupDb = compMakeupDb.finiteOr(0f).coerceIn(0f, 12f),
        crossfadeMs = crossfadeMs.coerceAtLeast(0L),
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

private val EditorClip.speedFactor: Float
    get() = if (speed.isFinite() && speed > 0f) speed else 1f
