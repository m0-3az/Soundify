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
