package com.soundify.app.editor

import com.soundify.app.cleanvideo.Ffmpeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class EditorExporterTest {

    private fun clip(
        id: String = "clip",
        sourceHash: String = "hash",
        sourceDurationMs: Long = 2_000L,
        trimStartMs: Long = 0L,
        trimEndMs: Long = sourceDurationMs,
    ) = EditorClip(
        id = id,
        sourceHash = sourceHash,
        sourceFile = "sources/$sourceHash.wav",
        displayName = "$id.wav",
        sourceDurationMs = sourceDurationMs,
        sourceSampleRate = 44_100,
        sourceChannels = 2,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
    )

    private fun project(vararg clips: EditorClip) =
        EditorProject(name = "My Song: 2026/07", clips = clips.toList())

    @Test
    fun freeSpaceDoublesTheEstimateAndAddsFiftyMegabytes() {
        val headroom = 50L * 1024L * 1024L

        assertEquals(388_080L * 2L + headroom, exportRequiredBytes(EditorFormat.WAV, 2_000L))
        assertEquals(232_848L * 2L + headroom, exportRequiredBytes(EditorFormat.FLAC, 2_000L))
        assertEquals(52_800L * 2L + headroom, exportRequiredBytes(EditorFormat.MP3, 2_000L))
        assertEquals(52_800L * 2L + headroom, exportRequiredBytes(EditorFormat.M4A, 2_000L))
        assertEquals(headroom, exportRequiredBytes(EditorFormat.WAV, 0L))
    }

    @Test
    fun commandFeedsEachInputThenThePlanGraphMapAndOutputArgs() {
        val input = File("/tmp/editor-input-a.wav")
        val output = File("/tmp/editor-output.mp3")
        val clip = clip(id = "a", sourceHash = "hash-a")
        val plan = FilterGraph.build(project(clip), listOf(clip), EditorFormat.MP3)

        val command = exportCommand(plan, listOf(input), output)

        assertEquals(
            "-y -i ${Ffmpeg.quote(input)} -filter_complex \"${plan.filterComplex}\" " +
                "-map [out] ${plan.outputArgs} ${Ffmpeg.quote(output)}",
            command,
        )
        assertTrue(
            "the format codec and bitrate must reach FFmpeg",
            command.contains("-c:a libmp3lame -b:a 192k"),
        )
    }

    @Test
    fun inputsFollowThePlanOrderAndDropUnrenderableClips() {
        val first = clip(id = "a", sourceHash = "hash-a")
        val dropped = clip(id = "b", sourceHash = "")
        val last = clip(id = "c", sourceHash = "hash-c")
        val project = project(first, dropped, last)
        val plan = FilterGraph.build(project, listOf(last, dropped, first), EditorFormat.WAV)
        val projectDir = createTempDirectory("editor-export").toFile()

        try {
            assertEquals(listOf(last, first), plan.inputClips)

            val inputs = exportInputs(projectDir, plan)

            assertEquals(
                listOf(
                    File(projectDir, "sources/hash-c.wav"),
                    File(projectDir, "sources/hash-a.wav"),
                ),
                inputs,
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun progressIsPercentOfTheExpectedDurationAndClamped() {
        assertEquals(0, exportProgressPercent(0.0, 2_000L))
        assertEquals(50, exportProgressPercent(1.0, 2_000L))
        assertEquals(25, exportProgressPercent(0.5, 2_000L))
        assertEquals(100, exportProgressPercent(2.0, 2_000L))
        assertEquals(100, exportProgressPercent(3.5, 2_000L))
        assertEquals(0, exportProgressPercent(-1.0, 2_000L))
        assertEquals(0, exportProgressPercent(Double.NaN, 2_000L))
        assertEquals(0, exportProgressPercent(1.0, 0L))
    }

    @Test
    fun editorFailuresUseActionableMessages() {
        assertTrue(
            Ffmpeg.friendlyMessage("", "Exiting normally, received signal 2.")
                .startsWith("Export was cancelled."),
        )
        assertTrue(
            Ffmpeg.friendlyMessage("", "Output file #0 does not contain any stream")
                .startsWith("This file has no readable audio track."),
        )
        assertTrue(
            Ffmpeg.friendlyMessage("", "No space left on device")
                .startsWith("Not enough storage"),
        )
        assertTrue(
            Ffmpeg.friendlyMessage("", "Unknown encoder 'libmp3lame'")
                .startsWith("This device does not have the requested encoder."),
        )
        assertTrue(
            Ffmpeg.friendlyMessage("", "Something unexpected")
                .startsWith("FFmpeg failed."),
        )
    }

    @Test
    fun targetNameSanitizesTheProjectNameAndUsesTheFormatExtension() {
        assertEquals(
            "My Song_ 2026_07-abc.wav",
            exportFileName(EditorProject(name = "  My Song: 2026/07  "), EditorFormat.WAV, "abc"),
        )
        assertEquals(
            "Soundify export-abc.flac",
            exportFileName(EditorProject(name = "  "), EditorFormat.FLAC, "abc"),
        )
        assertEquals(
            "Soundify export-abc.m4a",
            exportFileName(EditorProject(name = "..."), EditorFormat.M4A, "abc"),
        )
        assertEquals(
            "${"A".repeat(60)}-abc.wav",
            exportFileName(EditorProject(name = "A".repeat(100)), EditorFormat.WAV, "abc"),
        )
    }
}
