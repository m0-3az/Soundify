package com.soundify.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.StreamingCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CleanVideoViewModel(application: Application) : AndroidViewModel(application) {
    val selectedUri = MutableStateFlow<Uri?>(null)
    val selectedName = MutableStateFlow<String?>(null)
    val isProcessing = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val status = MutableStateFlow<String?>(null)
    val result = MutableStateFlow<CleanVideoResult?>(null)
    val message = MutableStateFlow<String?>(null)

    private var job: Job? = null

    fun select(uri: Uri, name: String?) {
        selectedUri.value = uri
        selectedName.value = name
        result.value = null
        progress.value = 0f
        status.value = null
    }

    fun clean() {
        val uri = selectedUri.value ?: return
        if (isProcessing.value) return
        isProcessing.value = true
        progress.value = 0f
        status.value = "Starting…"
        result.value = null
        job = viewModelScope.launch {
            try {
                val output = StreamingCleaner(getApplication()).clean(uri, selectedName.value) { p, s ->
                    progress.value = p
                    status.value = s
                }
                result.value = output
                status.value = "Done"
            } catch (c: CancellationException) {
                status.value = "Cancelled"
                throw c
            } catch (t: Throwable) {
                message.value = t.message ?: "Clean Video failed"
                status.value = "Failed"
            } finally {
                isProcessing.value = false
                job = null
            }
        }
    }

    /**
     * Keeps [isProcessing] true until the worker actually unwinds. This blocks a
     * second run from deleting the shared cache directory while the cancelled
     * run is still using it.
     */
    fun cancel() {
        val running = job ?: return
        if (!running.isActive) return
        status.value = "Cancelling…"
        running.cancel()
    }

    fun clearMessage() {
        message.value = null
    }
}
