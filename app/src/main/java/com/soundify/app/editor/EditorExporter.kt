package com.soundify.app.editor

import android.content.Context
import android.net.Uri
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.soundify.app.cleanvideo.Ffmpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Renders editor projects to audio files with FFmpeg.
 *
 * [exportToFile] is the whole pipeline without the storage-provider copy, so
 * tests can inspect the encoded file directly. Its inputs come from
 * [FilterPlan.inputClips], never from the raw selection: a clip dropped by
 * sanitization must not shift the `-i` ordering of the ones that remain.
 */
class EditorExporter(
    private val context: Context,
    private val store: EditorStore,
    private val freeSpace: () -> Long = { context.cacheDir.usableSpace },
) {

    /**
     * Exports [clips] in playback order to [target]. [onCancelReady] receives
     * the cancel action once FFmpeg is running. Failures are returned as
     * [Result.failure] with a message fit for the UI; coroutine cancellation
     * is rethrown so callers can cancel the export by cancelling their scope.
     */
    suspend fun export(
        project: EditorProject,
        clips: List<EditorClip>,
        format: EditorFormat,
        target: Uri,
        onProgress: (Int) -> Unit = {},
        onCancelReady: (() -> Unit) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val output = File(exportDir(), exportFileName(project, format))
        try {
            exportToFile(project, clips, format, output, onProgress, onCancelReady)
            copyToUri(output, target)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            output.delete()
        }
    }

    internal suspend fun exportToFile(
        project: EditorProject,
        clips: List<EditorClip>,
        format: EditorFormat,
        output: File,
        onProgress: (Int) -> Unit = {},
        onCancelReady: (() -> Unit) -> Unit = {},
    ) {
        val plan = FilterGraph.build(project, clips, format)
        requireFreeSpace(format, plan.expectedDurationMs)
        val inputs = exportInputs(store.projectDir(project.id), plan)
        output.parentFile?.mkdirs()
        runExport(
            command = exportCommand(plan, inputs, output),
            expectedDurationMs = plan.expectedDurationMs,
            onProgress = onProgress,
            onCancelReady = onCancelReady,
        )
        onProgress(100)
    }

    private fun requireFreeSpace(format: EditorFormat, durationMs: Long) {
        if (freeSpace() < exportRequiredBytes(format, durationMs)) {
            throw IllegalStateException(NOT_ENOUGH_STORAGE)
        }
    }

    private suspend fun runExport(
        command: String,
        expectedDurationMs: Long,
        onProgress: (Int) -> Unit,
        onCancelReady: (() -> Unit) -> Unit,
    ) = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        val session = Ffmpeg.runAsync(
            command,
            onStatistics = { statistics ->
                if (continuation.isActive) {
                    onProgress(exportProgressPercent(statistics.time, expectedDurationMs))
                }
            },
            onComplete = { success, log ->
                if (continuation.isActive) {
                    if (cancelled.get()) {
                        continuation.resumeWithException(IllegalStateException(CANCELLED))
                    } else if (success) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException(Ffmpeg.friendlyMessage("", log)),
                        )
                    }
                }
            },
        )
        onCancelReady {
            cancelled.set(true)
            FFmpegKit.cancel(session.sessionId)
        }
        continuation.invokeOnCancellation { session.cancel() }
    }

    private fun exportDir(): File =
        File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }

    private fun copyToUri(file: File, target: Uri) {
        // "wt" truncates an existing document on API 30+ so saving over a
        // larger previous file cannot leave stale bytes at the end.
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "wt" else "w"
        val output = runCatching {
            context.contentResolver.openOutputStream(target, mode)
        }.getOrNull() ?: context.contentResolver.openOutputStream(target)
        ?: error("Could not open the save location")
        output.use { sink ->
            file.inputStream().use { it.copyTo(sink) }
        }
    }

    private companion object {
        const val EXPORT_DIR = "editor/export"
        const val NOT_ENOUGH_STORAGE = "Not enough storage. Free some space and try again."
        const val CANCELLED = "Export was cancelled."
    }
}

private const val FREE_SPACE_HEADROOM_BYTES = 50L * 1024L * 1024L

/** Estimate doubled for the temporary file, plus working headroom. */
internal fun exportRequiredBytes(format: EditorFormat, durationMs: Long): Long =
    2L * format.estimatedBytes(durationMs) + FREE_SPACE_HEADROOM_BYTES

/** FFmpeg reports seconds; the plan reports milliseconds. */
internal fun exportProgressPercent(timeSeconds: Double, expectedDurationMs: Long): Int {
    if (expectedDurationMs <= 0L || !timeSeconds.isFinite() || timeSeconds <= 0.0) return 0
    val percent = timeSeconds * 1_000.0 / expectedDurationMs * 100.0
    return percent.roundToInt().coerceIn(0, 100)
}

/** One input file per rendered clip, in the graph's branch order. */
internal fun exportInputs(projectDir: File, plan: FilterPlan): List<File> =
    plan.inputClips.map { clip -> File(projectDir, clip.sourceFile) }

internal fun exportCommand(plan: FilterPlan, inputs: List<File>, output: File): String =
    buildString {
        append("-y")
        inputs.forEach { input -> append(" -i ").append(Ffmpeg.quote(input)) }
        append(" -filter_complex \"").append(plan.filterComplex).append('"')
        append(" -map ").append(plan.outputLabel)
        append(' ').append(plan.outputArgs)
        append(' ').append(Ffmpeg.quote(output))
    }

/** Cache file name, unique per export because of [token]. */
internal fun exportFileName(
    project: EditorProject,
    format: EditorFormat,
    token: String = UUID.randomUUID().toString(),
): String {
    val base = project.name
        .trim()
        .replace(UNSAFE_NAME_CHARS, "_")
        .take(MAX_NAME_LENGTH)
        .trim(' ', '.')
        .ifEmpty { "Soundify export" }
    return "$base-$token.${format.extension}"
}

private const val MAX_NAME_LENGTH = 60
private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9 ._-]")
