package com.soundify.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.CleanStem
import com.soundify.app.cleanvideo.CleanVideoController
import com.soundify.app.cleanvideo.CleanVideoExporter
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.VideoFormat
import kotlinx.coroutines.CancellationException
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
    val cleanState = CleanVideoController.state

    init {
        viewModelScope.launch {
            CleanVideoController.state.collect { state ->
                isProcessing.value = state.isProcessing
                progress.value = state.progress
                status.value = state.detail
                result.value = state.result
                state.error?.let {
                    message.value = it
                    CleanVideoController.clearError()
                }
            }
        }
    }

    fun select(uri: Uri, name: String?) {
        CleanVideoController.reset(getApplication())
        selectedUri.value = uri
        selectedName.value = name
        result.value = null
        selectedStem.value = CleanStem.CLEAN
        progress.value = 0f
        status.value = null
    }

    fun deleteStems() {
        if (isSaving.value) return
        CleanVideoController.deleteResult(getApplication())
        result.value = null
        selectedStem.value = CleanStem.CLEAN
        message.value = "The four stems were deleted"
    }

    fun clean() {
        val uri = selectedUri.value ?: return
        CleanVideoController.start(getApplication(), uri, selectedName.value)
    }

    fun cancel() {
        CleanVideoController.cancel(getApplication())
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
