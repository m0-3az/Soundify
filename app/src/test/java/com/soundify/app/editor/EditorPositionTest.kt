package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorPositionTest {

    @Test
    fun positionAtSumsThePreviousClipsAndScalesForSpeed() {
        val project = project(
            clip(id = "one", trimStart = 0, trimEnd = 2_000, speed = 1f),
            clip(id = "two", trimStart = 500, trimEnd = 1_500, speed = 1f),
        )

        assertEquals(0L, project.positionAt(0, 0L))
        assertEquals(1_000L, project.positionAt(0, 1_000L))
        assertEquals("the second clip starts after the first", 2_000L, project.positionAt(1, 500L))
        assertEquals(2_500L, project.positionAt(1, 1_000L))
    }

    @Test
    fun positionAtUsesRenderedTimeForSpeedAdjustedClips() {
        val project = project(clip(id = "fast", trimStart = 0, trimEnd = 2_000, speed = 2f))

        assertEquals(500L, project.positionAt(0, 1_000L))
        assertEquals(1_000L, project.positionAt(0, 2_000L))
    }

    @Test
    fun locateFindsTheClipAndSourcePosition() {
        val project = project(
            clip(id = "one", trimStart = 0, trimEnd = 2_000),
            clip(id = "two", trimStart = 500, trimEnd = 1_500),
        )

        assertEquals(0 to 0L, project.locate(0L))
        assertEquals(0 to 1_000L, project.locate(1_000L))
        assertEquals("the exact boundary belongs to the next clip", 1 to 500L, project.locate(2_000L))
        assertEquals(1 to 1_000L, project.locate(2_500L))
    }

    @Test
    fun locateClampsOutsideTheProject() {
        val project = project(
            clip(id = "one", trimStart = 0, trimEnd = 2_000),
            clip(id = "two", trimStart = 500, trimEnd = 1_500),
        )

        assertEquals(0 to 0L, project.locate(-500L))
        assertEquals(1 to 1_500L, project.locate(99_999L))
    }

    @Test
    fun locateMapsRenderedTimeBackToSourceTime() {
        val project = project(clip(id = "fast", trimStart = 0, trimEnd = 2_000, speed = 2f))

        assertEquals(0 to 1_000L, project.locate(500L))
    }

    @Test
    fun waveformFractionsSpanTheWholeSourceNotTheTrim() {
        assertEquals(0.2f, waveformFraction(2_000L, 10_000L), 0f)
        assertEquals(0.4f, waveformFraction(4_000L, 10_000L), 0f)
        assertEquals(1f, waveformFraction(99_000L, 10_000L), 0f)
        assertEquals(0f, waveformFraction(2_000L, 0L), 0f)
    }

    @Test
    fun waveformTapsMapToSourceTimeAndClampToTheTrim() {
        assertEquals(
            "a tap on the trim start must seek to the trim start",
            2_000L,
            waveformSourceMs(0.2f, 10_000L, trimStartMs = 2_000L, trimEndMs = 4_000L),
        )
        assertEquals(
            "a tap before the selection must clamp up to the trim start",
            2_000L,
            waveformSourceMs(0f, 10_000L, trimStartMs = 2_000L, trimEndMs = 4_000L),
        )
        assertEquals(
            "a tap after the selection must clamp down to the trim end",
            4_000L,
            waveformSourceMs(1f, 10_000L, trimStartMs = 2_000L, trimEndMs = 4_000L),
        )
        assertEquals(
            3_000L,
            waveformSourceMs(0.3f, 10_000L, trimStartMs = 2_000L, trimEndMs = 4_000L),
        )
    }

    private fun project(vararg clips: EditorClip): EditorProject =
        EditorProject(name = "position test", clips = clips.toList())

    private fun clip(
        id: String,
        trimStart: Long,
        trimEnd: Long,
        speed: Float = 1f,
    ): EditorClip = EditorClip(
        id = id,
        sourceHash = "hash-$id",
        sourceFile = "sources/$id.wav",
        displayName = "$id.wav",
        sourceDurationMs = 2_000L,
        sourceSampleRate = 44_100,
        sourceChannels = 2,
        trimStartMs = trimStart,
        trimEndMs = trimEnd,
        speed = speed,
    )
}
