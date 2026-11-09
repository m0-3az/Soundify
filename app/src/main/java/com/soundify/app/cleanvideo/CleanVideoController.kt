package com.soundify.app.cleanvideo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide processing state. The foreground service owns the worker; the
 * screen can be recreated or backgrounded without losing progress or results.
 */
object CleanVideoController {

    data class State(
        val isProcessing: Boolean = false,
        val progress: Float = 0f,
        val stage: CleanStage? = null,
        val detail: String? = null,
        val chunkIndex: Int = 0,
        val chunkCount: Int = 0,
        val etaSeconds: Long? = null,
        val startedAtMs: Long = 0L,
        val result: CleanVideoResult? = null,
        val error: String? = null,
        val cancelled: Boolean = false,
        val cancelling: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(context: Context, uri: Uri, name: String?, fingerprint: String? = null) {
        if (_state.value.isProcessing) return
        _state.value = State(
            isProcessing = true,
            stage = CleanStage.PREPARING,
            detail = "Starting…",
            startedAtMs = System.currentTimeMillis(),
        )
        val intent = Intent(context, CleanVideoService::class.java).apply {
            action = CleanVideoService.ACTION_START
            putExtra(CleanVideoService.EXTRA_URI, uri)
            putExtra(CleanVideoService.EXTRA_NAME, name)
            putExtra(CleanVideoService.EXTRA_FINGERPRINT, fingerprint)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            // Android 12+ can refuse a foreground start (battery saver, restricted
            // settings). Do not leave the UI stuck in a processing state.
            fail(t.message ?: "Could not start background processing.")
        }
    }

    fun cancel(context: Context) {
        val current = _state.value
        if (!current.isProcessing || current.cancelling) return
        val delivered = runCatching {
            context.startService(
                Intent(context, CleanVideoService::class.java).apply {
                    action = CleanVideoService.ACTION_CANCEL
                }
            )
        }
        if (delivered.isSuccess) {
            markCancelling()
        } else {
            // The service is still running; keep processing true and Cancel
            // enabled, but tell the user why the request did not go through.
            _state.update {
                if (!it.isProcessing) {
                    it
                } else {
                    it.copy(error = "Could not stop the cleanup. Tap Cancel to try again.")
                }
            }
        }
    }

    fun update(progress: CleanProgress) {
        _state.update { current -> reduceProgress(current, progress) }
    }

    /** Keeps "Cancelling…" on screen even if a worker callback races the cancel. */
    fun markCancelling() {
        _state.update {
            if (!it.isProcessing) {
                it
            } else {
                it.copy(
                    cancelling = true,
                    stage = CleanStage.FINALIZING,
                    detail = "Cancelling…",
                )
            }
        }
    }

    fun finish(result: CleanVideoResult) {
        _state.update {
            it.copy(
                isProcessing = false,
                progress = 1f,
                stage = CleanStage.DONE,
                detail = "Done",
                result = result,
                etaSeconds = null,
                cancelling = false,
            )
        }
    }

    fun fail(message: String) {
        _state.update {
            it.copy(
                isProcessing = false,
                error = message,
                detail = "Failed",
                etaSeconds = null,
                cancelling = false,
            )
        }
    }

    fun cancelled() {
        _state.update {
            it.copy(
                isProcessing = false,
                cancelled = true,
                detail = "Cancelled",
                etaSeconds = null,
                cancelling = false,
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Drop the previous result and its stem directory when a new video is selected. */
    fun reset(context: Context) {
        if (_state.value.isProcessing) return
        CleanVideoStorage.deleteResult(context, _state.value.result)
        _state.value = State()
    }

    /**
     * Clear the screen for a new selection without deleting the finished stems.
     * Finished runs are owned by history and are removed from there instead.
     */
    fun clearForNewSelection() {
        if (_state.value.isProcessing) return
        _state.value = State()
    }

    /** Show a persisted history result without starting a new separation. */
    fun open(result: CleanVideoResult) {
        if (_state.value.isProcessing) return
        _state.value = State(
            isProcessing = false,
            progress = 1f,
            stage = CleanStage.DONE,
            detail = "Done",
            result = result,
        )
    }

    /** Drop the in-memory result after its stems were deleted elsewhere. */
    fun clearResult() {
        _state.update {
            it.copy(
                result = null,
                progress = 0f,
                stage = null,
                detail = null,
                etaSeconds = null,
            )
        }
    }

    /** Delete the current stems but keep the selected source video and saved files. */
    fun deleteResult(context: Context) {
        if (_state.value.isProcessing) return
        CleanVideoStorage.deleteResult(context, _state.value.result)
        _state.update {
            it.copy(
                result = null,
                progress = 0f,
                stage = null,
                detail = null,
                etaSeconds = null,
            )
        }
    }

    internal fun reduceProgress(current: State, progress: CleanProgress): State =
        if (!current.isProcessing || current.cancelling) {
            current
        } else {
            current.copy(
                progress = progress.fraction,
                stage = progress.stage,
                detail = progress.detail,
                chunkIndex = progress.chunkIndex,
                chunkCount = progress.chunkCount,
                etaSeconds = progress.etaSeconds,
            )
        }
}
