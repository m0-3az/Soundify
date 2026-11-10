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

val EditorProject.isFutureSchema: Boolean
    get() = schemaVersion > EditorProject.CURRENT_SCHEMA

val EditorProject.durationMs: Long
    get() = (clips.sumOf { it.renderedDurationMs } - clips.sumOf { it.crossfadeMs })
        .coerceAtLeast(0L)

val EditorClip.renderedDurationMs: Long
    get() {
        val trimmed = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        if (trimmed == 0L) return 1L
        val effectiveSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        return (trimmed / effectiveSpeed).roundToLong().coerceAtLeast(1L)
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
