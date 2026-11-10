package com.soundify.app

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.soundify.app.audio.WavWriter
import com.soundify.app.data.ThemePreferences
import com.soundify.app.editor.EditorClip
import com.soundify.app.editor.EditorProject
import com.soundify.app.editor.EditorStore
import com.soundify.app.editor.EditorViewModel
import com.soundify.app.editor.renderedDurationMs
import com.soundify.app.ui.SoundifyApp
import com.soundify.app.ui.theme.SoundifyTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class EditorScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var soundify: SoundifyViewModel
    private lateinit var editor: EditorViewModel
    private lateinit var source: File
    private var projectId: String? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "editor").deleteRecursively()
        source = File(app.cacheDir, "editor-screen-${UUID.randomUUID()}.wav")
        writeTone(source)
        soundify = SoundifyViewModel(app)
        editor = EditorViewModel(app)
        compose.setContent {
            SoundifyTheme {
                SoundifyApp(soundify, ThemePreferences(app), editor)
            }
        }
    }

    @After
    fun tearDown() {
        projectId?.let { id -> editor.delete(id) }
        source.delete()
    }

    @Test
    fun editorShowsTheToolsAndSplitsAtThePlayhead() {
        compose.onNodeWithText("Audio editor").performClick()
        compose.runOnUiThread {
            editor.createFromAudio(Uri.fromFile(source), "tone.wav") { id ->
                projectId = id
                soundify.openEditor(id)
            }
        }

        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Trim").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Clips (1)").assertExists()
        compose.onNodeWithText("Original is never modified.").assertExists()
        compose.onNodeWithText("Crossfade and effects are applied on export.").assertExists()
        compose.onNodeWithContentDescription("Save audio").assertExists()

        compose.onNodeWithText("Split").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Clips (2)").fetchSemanticsNodes().isNotEmpty()
        }

        val clips = editor.active.value!!.clips
        assertEquals("splitting must produce two clips", 2, clips.size)
        assertEquals(
            "a split must keep the source trim length",
            2_000L,
            clips.sumOf { it.renderedDurationMs },
        )

        compose.onNodeWithText("Trim").performClick()
        compose.onNodeWithText("Start (ms)").assertExists()
        compose.onNodeWithText("End (ms)").assertExists()
        compose.onNodeWithText("Apply trim").assertExists()
    }

    @Test
    fun futureSchemaProjectOpensReadOnly() {
        val store = EditorStore(File(app.filesDir, "editor"))
        val project = EditorProject(
            schemaVersion = 99,
            name = "Future edit",
            clips = listOf(
                EditorClip(
                    sourceHash = "future-hash",
                    sourceFile = "sources/future.wav",
                    displayName = "future.wav",
                    sourceDurationMs = 2_000L,
                    sourceSampleRate = 44_100,
                    sourceChannels = 2,
                ),
            ),
        )
        store.save(project)
        projectId = project.id

        compose.onNodeWithText("Audio editor").performClick()
        compose.runOnUiThread {
            editor.open(project.id) { soundify.openEditor(project.id) }
        }

        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(
                "This edit was created by a newer version of Soundify. " +
                    "It opens read-only.",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Trim").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Save audio").assertIsNotEnabled()
    }

    private fun writeTone(file: File) {
        val sampleRate = 44_100
        val frames = sampleRate * 2
        WavWriter(file, sampleRate = sampleRate, channels = 2).use { writer ->
            val samples = FloatArray(frames * 2) { index ->
                val frame = index / 2
                (sin(2.0 * PI * 440.0 * frame / sampleRate) * 0.5).toFloat()
            }
            writer.writeInterleaved(samples, frames)
        }
    }
}
