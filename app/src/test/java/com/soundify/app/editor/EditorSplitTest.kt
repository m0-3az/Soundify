package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditorSplitTest {

    @Test
    fun splitCreatesTwoClipsWhoseRenderedDurationsSumToTheTrim() {
        val project = projectWith(clip())

        val split = project.splitClip(CLIP_ID, atSourceMs = 1_000L)

        assertEquals(2, split.clips.size)
        val first = split.clips[0]
        val second = split.clips[1]
        assertNotEquals("each half needs its own id", first.id, second.id)
        assertEquals("first half starts at the original trim start", 0L, first.trimStartMs)
        assertEquals(1_000L, first.trimEndMs)
        assertEquals(1_000L, second.trimStartMs)
        assertEquals(2_000L, second.trimEndMs)
        assertEquals(
            "the halves must keep the source trim",
            project.clips.single().renderedDurationMs,
            first.renderedDurationMs + second.renderedDurationMs,
        )
    }

    @Test
    fun splitAtTheEdgesClampsToTheMinimumClipLength() {
        val project = projectWith(clip())

        val atStart = project.splitClip(CLIP_ID, atSourceMs = 0L)
        assertEquals(250L, atStart.clips[0].trimEndMs)
        assertEquals(250L, atStart.clips[1].trimStartMs)
        assertEquals(1_750L, atStart.clips[1].renderedDurationMs)

        val atEnd = project.splitClip(CLIP_ID, atSourceMs = 5_000L)
        assertEquals(1_750L, atEnd.clips[0].trimEndMs)
        assertEquals(250L, atEnd.clips[1].renderedDurationMs)
    }

    @Test
    fun splitKeepsTheRenderedDurationForSpeedAdjustedClips() {
        val fast = clip(speed = 2f)
        val project = projectWith(fast)

        val split = project.splitClip(CLIP_ID, atSourceMs = 1_000L)

        assertEquals(500L, split.clips[0].renderedDurationMs)
        assertEquals(500L, split.clips[1].renderedDurationMs)
        assertEquals(
            "a split must not change the playback length",
            fast.renderedDurationMs,
            split.clips.sumOf { it.renderedDurationMs },
        )
    }

    @Test
    fun theSecondHalfNeverCrossfades() {
        val project = projectWith(clip().copy(crossfadeMs = 200L))

        val split = project.splitClip(CLIP_ID, atSourceMs = 1_000L)

        assertEquals(
            "the split point is a hard join, not a crossfade",
            0L,
            split.clips[1].crossfadeMs,
        )
        assertEquals(0L, split.clips[0].crossfadeMs)
    }

    @Test
    fun outerFadesStayOnTheOuterEdges() {
        val project = projectWith(
            clip().copy(fadeInMs = 300L, fadeOutMs = 400L),
        )

        val split = project.splitClip(CLIP_ID, atSourceMs = 1_000L)

        assertEquals(300L, split.clips[0].fadeInMs)
        assertEquals("the split must not add a fade in its middle", 0L, split.clips[0].fadeOutMs)
        assertEquals(0L, split.clips[1].fadeInMs)
        assertEquals(400L, split.clips[1].fadeOutMs)
    }

    @Test
    fun splitIgnoresAnUnknownClipId() {
        val project = projectWith(clip())

        val split = project.splitClip("missing", atSourceMs = 1_000L)

        assertSame(project, split)
    }

    @Test
    fun clipsShorterThanTwoMinimumsCannotBeSplit() {
        val short = clip(trimStartMs = 0L, trimEndMs = 400L)
        val project = projectWith(short)

        val split = project.splitClip(CLIP_ID, atSourceMs = 200L)

        assertSame("a 400 ms clip cannot yield two 250 ms halves", project, split)
    }

    private fun projectWith(clip: EditorClip): EditorProject =
        EditorProject(name = "split test", clips = listOf(clip))

    private fun clip(
        trimStartMs: Long = 0L,
        trimEndMs: Long = 2_000L,
        speed: Float = 1f,
    ): EditorClip = EditorClip(
        id = CLIP_ID,
        sourceHash = "hash",
        sourceFile = "sources/hash.wav",
        displayName = "tone.wav",
        sourceDurationMs = 2_000L,
        sourceSampleRate = 44_100,
        sourceChannels = 2,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        speed = speed,
    )

    private companion object {
        const val CLIP_ID = "clip-1"
    }
}
