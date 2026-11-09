package com.soundify.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.soundify.app.cleanvideo.StemResult
import com.soundify.app.cleanvideo.StreamingCleaner
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Temporary evaluation harness: runs the real clean-video pipeline over videos
 * pushed to the app's external files dir and copies the four stems somewhere
 * adb can pull them from. Not part of the shipped app or the test suite.
 */
@RunWith(AndroidJUnit4::class)
class SpikeAudioEvalTest {
    @Test
    fun cleanSpikeClips() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputDir = context.getExternalFilesDir("spike-in")
            ?: error("no external files dir")
        val outRoot = context.getExternalFilesDir("spike-out")
            ?: error("no external files dir")
        val videos = inputDir.listFiles { file -> file.isFile && file.name.endsWith(".mp4", true) }
            .orEmpty()
            .sortedBy { it.name }
        check(videos.isNotEmpty()) { "no .mp4 files in ${inputDir.absolutePath}" }

        for (video in videos) {
            val name = video.nameWithoutExtension
            val target = File(outRoot, name).apply {
                deleteRecursively()
                mkdirs()
            }
            val started = System.currentTimeMillis()
            Log.i("SpikeEval", "start $name")
            val outcome = runCatching {
                StreamingCleaner(context).clean(Uri.fromFile(video), video.name) { progress ->
                    if (progress.stage.name != "SEPARATING" || progress.chunkIndex == 1 ||
                        progress.chunkIndex == progress.chunkCount
                    ) {
                        Log.i("SpikeEval", "$name ${progress.stage} ${progress.detail}")
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - started
            outcome.onSuccess { result ->
                for (stem in result.stems) {
                    stem.file.copyTo(File(target, stem.file.name), overwrite = true)
                }
                File(target, "stats.txt").writeText(
                    buildString {
                        appendLine("elapsedMs=$elapsed")
                        appendLine("cleanNote=${result.cleanNote}")
                        appendLine("videoCodec=${result.videoCodec}")
                        appendLine("videoDurationMs=${result.videoDurationMs}")
                        for (stem in result.stems) {
                            appendLine(line(stem))
                        }
                    },
                )
                Log.i(
                    "SpikeEval",
                    "done $name in ${elapsed}ms clean=${result.clean.rmsDb}dBFS/${result.clean.peakDb}dB",
                )
            }.onFailure { error ->
                File(target, "error.txt").writeText("$error")
                Log.e("SpikeEval", "failed $name in ${elapsed}ms", error)
            }
        }
    }

    private fun line(stem: StemResult): String =
        "${stem.kind.name} durationMs=${stem.durationMs} rmsDb=${stem.rmsDb} " +
            "peakDb=${stem.peakDb} exportGainDb=${stem.exportGainDb}"
}
