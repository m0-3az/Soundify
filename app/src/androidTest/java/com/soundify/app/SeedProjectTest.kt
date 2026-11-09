package com.soundify.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.data.Project
import com.soundify.app.data.ProjectStore
import com.soundify.app.data.RoleMapper
import com.soundify.app.data.Stem
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeedProjectTest {

    @Test
    fun seedDemoProject() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val store = ProjectStore(context)
        val projectId = "seed-demo"
        store.delete(projectId)
        val stemsDir = store.stemsDir(projectId)

        val names = listOf("vocals", "drums", "bass", "other")
        val stems = names.mapIndexed { index, name ->
            val destination = File(stemsDir, "$name.wav")
            instrumentation.context.assets.open("stems/$name.wav").use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            val role = RoleMapper.infer(name)
            Stem(
                role = role,
                displayName = RoleMapper.defaultDisplayName(role, index),
                filePath = destination.absolutePath,
                format = "audio/raw",
                sampleRate = 44100,
                channels = 2,
                durationMs = 1000,
                sortOrder = index
            )
        }

        store.save(
            Project(
                id = projectId,
                name = "Demo Mix",
                sampleRate = 44100,
                durationMs = 1000,
                loopEndMs = 1000,
                stems = stems
            )
        )
    }
}
