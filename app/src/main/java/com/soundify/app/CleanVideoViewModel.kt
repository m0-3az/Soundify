package com.soundify.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.CleanStem
import com.soundify.app.cleanvideo.CleanVideoExporter
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.StreamingCleaner
import com.soundify.app.cleanvideo.VideoFormat
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
    val selectedStem = MutableStateFlow(CleanStem.CLEAN)
    val isSaving = MutableStateFlow(false)
    val saveStatus = MutableStateFlow<String?>(null)
    val saveMessage = MutableStateFlow<String?>(null)

    private var job: Job? = null

    fun select(uri: Uri, name: String?) {
        selectedUri.value = uri
        selectedName.value = name
        result.value = null
        selectedStem.value = CleanStem.CLEAN
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

    fun selectStem(kind: CleanStem) {
        if (!isSaving.value) selectedStem.value = kind
    }

    fun saveAudio(format: AudioFormat, target: Uri) {
        val output = result.value ?: return
        startSave("Saving ${format.label}…") {
            CleanVideoExporter(getApplication())
                .exportAudio(output.stem(selectedStem.value), format, target)
            saveMessage.value = "Saved ${selectedStem.value.label} as ${format.label}"
        }
    }

    fun saveVideo(format: VideoFormat, target: Uri) {
        val output = result.value ?: return
        startSave("Saving ${format.label} video…") {
            CleanVideoExporter(getApplication())
                .exportVideo(output, output.stem(selectedStem.value), format, target)
            saveMessage.value = "Saved ${selectedStem.value.label} video as ${format.label}"
        }
    }

    fun clearSaveMessage() {
        saveMessage.value = null
    }

    private fun startSave(status: String, block: suspend () -> Unit) {
        if (isSaving.value) return
        isSaving.value = true
        saveStatus.value = status
        viewModelScope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                saveMessage.value = t.message ?: "Save failed"
            } finally {
                isSaving.value = false
                saveStatus.value = null
            }
        }
    }
}
