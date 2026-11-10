package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorProjectTest {

    private fun clip(
        id: String = "clip",
        sourceHash: String = "hash",
        sourceDurationMs: Long = 10_000L,
        trimStartMs: Long = 0L,
        trimEndMs: Long = sourceDurationMs,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        gainDb: Float = 0f,
        speed: Float = 1f,
        pitchSemitones: Int = 0,
        eqLowDb: Float = 0f,
        eqMidDb: Float = 0f,
        eqHighDb: Float = 0f,
        compThresholdDb: Float = -18f,
        compRatio: Float = 3f,
        compMakeupDb: Float = 0f,
        crossfadeMs: Long = 0L,
    ) = EditorClip(
        id = id,
        sourceHash = sourceHash,
        sourceFile = "sources/$sourceHash.wav",
        displayName = "clip.wav",
        sourceDurationMs = sourceDurationMs,
        sourceSampleRate = 44_100,
        sourceChannels = 2,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        gainDb = gainDb,
        speed = speed,
        pitchSemitones = pitchSemitones,
        eqLowDb = eqLowDb,
        eqMidDb = eqMidDb,
        eqHighDb = eqHighDb,
        compThresholdDb = compThresholdDb,
        compRatio = compRatio,
        compMakeupDb = compMakeupDb,
        crossfadeMs = crossfadeMs,
    )

    private fun project(vararg clips: EditorClip) =
        EditorProject(name = "Test project", clips = clips.toList())

    @Test
    fun renderedDurationAppliesSpeed() {
        val fast = clip(sourceDurationMs = 4_000L, speed = 2f)
        val slow = clip(sourceDurationMs = 4_000L, speed = 0.5f)

        assertEquals(2_000L, fast.renderedDurationMs)
        assertEquals(8_000L, slow.renderedDurationMs)
    }

    @Test
    fun projectDurationSubtractsCrossfadesAndNeverGoesNegative() {
        val first = clip(id = "a", sourceDurationMs = 2_000L)
        val second = clip(id = "b", sourceDurationMs = 2_000L, crossfadeMs = 500L)

        assertEquals(3_500L, project(first, second).durationMs)
        assertEquals(0L, project(clip(crossfadeMs = 20_000L)).durationMs)
    }

    @Test
    fun sanitizedClampsEveryEditableRange() {
        val raw = project(
            clip(
                trimStartMs = -50L,
                trimEndMs = 20_000L,
                fadeInMs = -10L,
                fadeOutMs = 20_000L,
                gainDb = 100f,
                speed = 9f,
                pitchSemitones = 50,
                eqLowDb = -50f,
                eqMidDb = 50f,
                eqHighDb = 50f,
                compThresholdDb = 10f,
                compRatio = 0.1f,
                compMakeupDb = -4f,
                crossfadeMs = 900L,
            ),
        )

        val sanitized = raw.sanitized().clips.single()

        assertEquals(0L, sanitized.trimStartMs)
        assertEquals(10_000L, sanitized.trimEndMs)
        assertEquals(2f, sanitized.speed, 0f)
        assertEquals(5_000L, sanitized.renderedDurationMs)
        assertEquals(0L, sanitized.fadeInMs)
        assertEquals(5_000L, sanitized.fadeOutMs)
        assertEquals(24f, sanitized.gainDb, 0f)
        assertEquals(12, sanitized.pitchSemitones)
        assertEquals(-12f, sanitized.eqLowDb, 0f)
        assertEquals(12f, sanitized.eqMidDb, 0f)
        assertEquals(12f, sanitized.eqHighDb, 0f)
        assertEquals(0f, sanitized.compThresholdDb, 0f)
        assertEquals(1f, sanitized.compRatio, 0f)
        assertEquals(0f, sanitized.compMakeupDb, 0f)
        assertEquals(0L, sanitized.crossfadeMs)
    }

    @Test
    fun sanitizedFixesInvertedTrim() {
        val sanitized = project(clip(trimStartMs = 9_000L, trimEndMs = 1_000L))
            .sanitized()
            .clips
            .single()

        assertEquals(9_000L, sanitized.trimStartMs)
        assertEquals(9_250L, sanitized.trimEndMs)
        assertEquals(250L, sanitized.renderedDurationMs)
    }

    @Test
    fun sanitizedCapsFadesToTheRenderedDuration() {
        val sanitized = project(
            clip(sourceDurationMs = 2_000L, fadeInMs = 1_500L, fadeOutMs = 1_500L),
        )
            .sanitized()
            .clips
            .single()

        assertEquals(1_500L, sanitized.fadeInMs)
        assertEquals(500L, sanitized.fadeOutMs)
        assertEquals(sanitized.renderedDurationMs, sanitized.fadeInMs + sanitized.fadeOutMs)
    }

    @Test
    fun sanitizedCapsCrossfadeToHalfTheShorterAdjacentClip() {
        val first = clip(id = "a", sourceDurationMs = 4_000L, crossfadeMs = 900L)
        val second = clip(id = "b", sourceDurationMs = 2_000L, crossfadeMs = 2_000L)

        val sanitized = project(first, second).sanitized()

        assertEquals(0L, sanitized.clips[0].crossfadeMs)
        assertEquals(1_000L, sanitized.clips[1].crossfadeMs)
        assertEquals(5_000L, sanitized.durationMs)
    }

    @Test
    fun sanitizedDropsDuplicateIdsAndBlankSourceHashes() {
        val keep = clip(id = "keep", sourceHash = "one")
        val duplicate = clip(id = "keep", sourceHash = "two")
        val blankHash = clip(id = "blank", sourceHash = "  ")
        val other = clip(id = "other", sourceHash = "three")

        val sanitized = project(keep, duplicate, blankHash, other).sanitized()

        assertEquals(listOf("keep", "other"), sanitized.clips.map { it.id })
    }

    @Test
    fun sanitizedKeepsTheFirstBlankIdAndDropsLaterBlankDuplicates() {
        val first = clip(id = "", sourceHash = "one")
        val second = clip(id = "", sourceHash = "two")

        val sanitized = project(first, second).sanitized()

        assertEquals(1, sanitized.clips.size)
        assertEquals("", sanitized.clips.single().id)
    }

    @Test
    fun sanitizedSurvivesNonFiniteValuesAndTinySources() {
        val sanitized = project(
            clip(
                sourceDurationMs = 10L,
                speed = Float.NaN,
                gainDb = Float.NEGATIVE_INFINITY,
                eqLowDb = Float.POSITIVE_INFINITY,
                compRatio = Float.NaN,
                fadeInMs = Long.MAX_VALUE,
                fadeOutMs = Long.MAX_VALUE,
            ),
        ).sanitized()

        val clean = sanitized.clips.single()
        assertEquals(1, sanitized.clips.size)
        assertEquals(250L, clean.sourceDurationMs)
        assertEquals(250L, clean.renderedDurationMs)
        assertEquals(1f, clean.speed, 0f)
        assertEquals(0f, clean.gainDb, 0f)
        assertEquals(0f, clean.eqLowDb, 0f)
        assertEquals(3f, clean.compRatio, 0f)
        assertEquals(250L, clean.fadeInMs)
        assertEquals(0L, clean.fadeOutMs)
    }

    @Test
    fun futureSchemaIsReported() {
        assertFalse(EditorProject(name = "current").isFutureSchema)
        assertTrue(EditorProject(schemaVersion = 2, name = "future").isFutureSchema)
    }
}
