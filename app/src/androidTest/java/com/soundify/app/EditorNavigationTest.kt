package com.soundify.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.soundify.app.data.ThemePreferences
import com.soundify.app.editor.EditorViewModel
import com.soundify.app.ui.SoundifyApp
import com.soundify.app.ui.theme.SoundifyTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorNavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var soundify: SoundifyViewModel
    private lateinit var editor: EditorViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "editor").deleteRecursively()
        soundify = SoundifyViewModel(app)
        editor = EditorViewModel(app)
        compose.setContent {
            SoundifyTheme {
                SoundifyApp(soundify, ThemePreferences(app), editor)
            }
        }
    }

    @Test
    fun audioEditorOpensHomeAndBackReturnsToLibrary() {
        compose.onNodeWithText("Audio editor").assertIsDisplayed()
        compose.onNodeWithText("Audio editor").performClick()
        compose.onNodeWithText("Import an audio file to trim, split or fade it.")
            .assertIsDisplayed()
        compose.onNodeWithText("New edit", useUnmergedTree = true).assertExists()

        pressBack()

        compose.onNodeWithText("Soundify").assertIsDisplayed()
        compose.onNodeWithText("No edits yet").assertDoesNotExist()
    }

    @Test
    fun editorBackReturnsToEditorHomeAndThenLibrary() {
        compose.onNodeWithText("Audio editor").performClick()
        compose.onNodeWithText("No edits yet").assertIsDisplayed()

        compose.runOnUiThread { soundify.openEditor("test-project") }
        compose.onNodeWithText("Editor").assertIsDisplayed()

        pressBack()
        compose.onNodeWithText("No edits yet").assertIsDisplayed()

        pressBack()
        compose.onNodeWithText("Soundify").assertIsDisplayed()
    }

    private fun pressBack() {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }
}
