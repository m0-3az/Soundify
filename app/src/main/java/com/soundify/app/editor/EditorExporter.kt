package com.soundify.app.editor

import android.content.Context
import android.net.Uri
import android.os.Build
import com.arthenica.ffmpegkit.FFmpegKit
import com.soundify.app.cleanvideo.Ffmpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Cancellation is checked after encoding, before opening the target, and
 * between copy chunks. A scope cancellation waits (bounded) for the FFmpeg
 * session to finish before the temporary file is deleted, because a cancelled
 * session can otherwise recreate the file after cleanup.
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
        val cancel = ExportCancelToken()
        try {
            exportToFile(project, clips, format, output, onProgress, onCancelReady, cancel)
            cancel.check()
            // Encoding is done. The copy to the user's document is the commit
            // point: finish the token first so Cancel, rotation, or teardown
            // cannot truncate a target that has already been opened.
            cancel.finish()
            copyToUri(output, target)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            cancel.finish()
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
        cancel: ExportCancelToken = ExportCancelToken(),
    ) {
        val plan = FilterGraph.build(project, clips, format)
        requireFreeSpace(format, plan.expectedDurationMs)
        val inputs = exportInputs(store.projectDir(project.id), plan)
        requireSourceFiles(inputs)
        output.parentFile?.mkdirs()
        runExport(
            command = exportCommand(plan, inputs, output),
            expectedDurationMs = plan.expectedDurationMs,
            onProgress = onProgress,
            onCancelReady = onCancelReady,
            cancel = cancel,
        )
        cancel.check()
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
        cancel: ExportCancelToken,
    ) {
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val session = Ffmpeg.runAsync(
                    command,
                    onStatistics = { statistics ->
                        if (continuation.isActive && !cancel.isCancelled) {
                            onProgress(
                                exportProgressPercent(statistics.time, expectedDurationMs),
                            )
                        }
                    },
                    onComplete = { success, message ->
                        finished.complete(Unit)
                        cancel.unbindSession()
                        if (continuation.isActive) {
                            when {
                                cancel.isCancelled ->
                                    continuation.resumeWithException(
                                        IllegalStateException(EXPORT_CANCELLED),
                                    )

                                success -> continuation.resume(Unit)

                                else -> continuation.resumeWithException(
                                    IllegalStateException(message),
                                )
                            }
                        }
                    },
                )
                cancel.bindSession { FFmpegKit.cancel(session.sessionId) }
                onCancelReady { cancel.cancel() }
                continuation.invokeOnCancellation { session.cancel() }
            }
        } catch (cancelled: CancellationException) {
            // The FFmpeg session stops asynchronously. Wait for its completion
            // before the caller deletes the temp file, or FFmpeg can recreate it.
            withContext(NonCancellable) {
                withTimeoutOrNull(CANCEL_TIMEOUT_MS) { finished.await() }
            }
            throw cancelled
        } finally {
            cancel.unbindSession()
        }
    }

    private fun exportDir(): File =
        File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }

    private suspend fun copyToUri(file: File, target: Uri) {
        // Open the finished export first: a missing temp file must not truncate
        // the user's target document.
        val input = runCatching { file.inputStream() }.getOrNull()
            ?: error("The finished export could not be read.")
        input.use { source ->
            // "wt" truncates an existing document on API 30+ so saving over a
            // larger previous file cannot leave stale bytes at the end.
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "wt" else "w"
            val output = runCatching {
                context.contentResolver.openOutputStream(target, mode)
            }.getOrNull() ?: context.contentResolver.openOutputStream(target)
            ?: error("Could not open the save location")
            output.use { sink ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
                sink.flush()
            }
        }
    }

    private companion object {
        const val EXPORT_DIR = "editor/export"
        const val NOT_ENOUGH_STORAGE = "Not enough storage. Free some space and try again."
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CANCEL_TIMEOUT_MS = 5_000L
    }
}

internal const val EXPORT_CANCELLED = "Export was cancelled."
internal const val MISSING_SOURCE = "A source file is missing. Re-import this clip and try again."

/**
 * Cancel state shared by the encoder, the copy, and the caller's cancel action.
 * [finish] makes a late tap inert; [isCancelled] stays true only while the
 * export is still running.
 */
internal class ExportCancelToken {
    private val cancelled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    private var sessionCancel: (() -> Unit)? = null

    val isCancelled: Boolean
        get() = cancelled.get() && !finished.get()

    fun cancel() {
        if (finished.get()) return
        if (!cancelled.compareAndSet(false, true)) return
        sessionCancel?.invoke()
    }

    fun bindSession(action: () -> Unit) {
        if (finished.get()) return
        sessionCancel = action
        if (cancelled.get()) {
            sessionCancel = null
            action()
        }
    }

    fun unbindSession() {
        sessionCancel = null
    }

    fun finish() {
        finished.set(true)
        sessionCancel = null
    }

    fun check() {
        if (isCancelled) throw IllegalStateException(EXPORT_CANCELLED)
    }
}

private const val FREE_SPACE_HEADROOM_BYTES = 50L * 1024L * 1024L

/** Estimate doubled for the temporary file, plus working headroom. */
internal fun exportRequiredBytes(format: EditorFormat, durationMs: Long): Long =
    2L * format.estimatedBytes(durationMs) + FREE_SPACE_HEADROOM_BYTES

/** ffmpeg-kit reports [Statistics.time] in milliseconds. */
internal fun exportProgressPercent(timeMs: Double, expectedDurationMs: Long): Int {
    if (expectedDurationMs <= 0L || !timeMs.isFinite() || timeMs <= 0.0) return 0
    val percent = timeMs / expectedDurationMs * 100.0
    return percent.roundToInt().coerceIn(0, 100)
}

/** One input file per rendered clip, in the graph's branch order. */
internal fun exportInputs(projectDir: File, plan: FilterPlan): List<File> =
    plan.inputClips.map { clip -> File(projectDir, clip.sourceFile) }

internal fun requireSourceFiles(inputs: List<File>) {
    if (inputs.firstOrNull { !it.isFile } != null) {
        throw IllegalStateException(MISSING_SOURCE)
    }
}

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
