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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(context: Context, uri: Uri, name: String?) {
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
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel(context: Context) {
        val current = _state.value
        if (!current.isProcessing) return
        _state.value = current.copy(stage = CleanStage.FINALIZING, detail = "Cancelling…")
        context.startService(
            Intent(context, CleanVideoService::class.java).apply {
                action = CleanVideoService.ACTION_CANCEL
            }
        )
    }

    fun update(progress: CleanProgress) {
        _state.update { current ->
            if (!current.isProcessing) {
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
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** Drop the previous result when a new video is selected. */
    fun reset() {
        if (_state.value.isProcessing) return
        _state.value = State()
    }
}
