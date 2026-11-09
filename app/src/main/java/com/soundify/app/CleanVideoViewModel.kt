package com.soundify.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.CleanStem
import com.soundify.app.cleanvideo.CleanVideoController
import com.soundify.app.cleanvideo.CleanVideoExporter
import com.soundify.app.cleanvideo.CleanVideoHistoryEntry
import com.soundify.app.cleanvideo.CleanVideoHistoryStore
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.CleanVideoStorage
import com.soundify.app.cleanvideo.StemResult
import com.soundify.app.cleanvideo.StorageManager
import com.soundify.app.cleanvideo.StorageUsage
import com.soundify.app.cleanvideo.VideoFingerprint
import com.soundify.app.cleanvideo.VideoFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

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
    val fingerprint = MutableStateFlow<String?>(null)
    val duplicate = MutableStateFlow<CleanVideoHistoryEntry?>(null)
    val history = MutableStateFlow<List<CleanVideoHistoryEntry>>(emptyList())
    val storage = MutableStateFlow<StorageUsage?>(null)
    val storageMessage = MutableStateFlow<String?>(null)

    private val historyStore: CleanVideoHistoryStore
        get() = CleanVideoHistoryStore(CleanVideoStorage.exportRoot(getApplication()))

    private val storageManager: StorageManager
        get() = StorageManager(getApplication())

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
                if (state.stage == com.soundify.app.cleanvideo.CleanStage.DONE) {
                    refreshHistory()
                    refreshStorage()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            refreshHistory()
            refreshStorage()
        }
    }

    fun select(uri: Uri, name: String?) {
        CleanVideoController.clearForNewSelection()
        selectedUri.value = uri
        selectedName.value = name
        result.value = null
        selectedStem.value = CleanStem.CLEAN
        progress.value = 0f
        status.value = null
        fingerprint.value = null
        duplicate.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val fp = VideoFingerprint.read(getApplication(), uri)
            if (selectedUri.value != uri) return@launch
            fingerprint.value = fp
            if (fp != null && !isProcessing.value) {
                val entry = historyStore.find(fp)
                if (entry != null && resultFor(entry) != null) {
                    duplicate.value = entry
                }
            }
        }
    }

    fun clean() {
        val uri = selectedUri.value ?: return
        if (duplicate.value != null) return
        startClean(uri)
    }

    fun cleanAgain() {
        val uri = selectedUri.value ?: return
        duplicate.value = null
        startClean(uri)
    }

    fun dismissDuplicate() {
        duplicate.value = null
    }

    private fun startClean(uri: Uri) {
        CleanVideoController.start(
            getApplication(),
            uri,
            selectedName.value,
            fingerprint.value,
        )
    }

    fun deleteStems() {
        if (isSaving.value) return
        val id = result.value?.clean?.file?.parentFile?.name
        CleanVideoController.deleteResult(getApplication())
        if (id != null) historyStore.delete(id)
        result.value = null
        selectedStem.value = CleanStem.CLEAN
        message.value = "The four stems were deleted"
        refreshHistory()
        refreshStorage()
    }

    fun openHistoryEntry(entry: CleanVideoHistoryEntry) {
        val loaded = resultFor(entry) ?: run {
            message.value = "These stems are no longer on the device."
            refreshHistory()
            return
        }
        selectedName.value = entry.displayName
        selectedStem.value = CleanStem.CLEAN
        fingerprint.value = entry.fingerprint
        duplicate.value = null
        CleanVideoController.open(loaded)
    }

    fun deleteHistoryEntry(entry: CleanVideoHistoryEntry) {
        val deleted = historyStore.delete(entry.id)
        if (result.value?.clean?.file?.parentFile?.name == entry.id) {
            CleanVideoController.clearResult()
        }
        message.value = if (deleted) "Clean Video result deleted" else "Could not delete this result"
        refreshHistory()
        refreshStorage()
    }

    fun clearHistory() {
        historyStore.deleteAllRuns()
        CleanVideoController.clearResult()
        message.value = "Clean Video history cleared"
        refreshHistory()
        refreshStorage()
    }

    fun cleanTemporaryFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = storageManager.cleanTemporary()
            refreshStorage()
            storageMessage.value = if (freed > 0) {
                "Freed ${com.soundify.app.ui.formatBytes(freed)} of temporary files"
            } else {
                "No temporary files to remove"
            }
        }
    }

    fun deleteAllCleanVideoResults() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = storageManager.deleteAllCleanVideo()
            CleanVideoController.clearResult()
            refreshHistory()
            refreshStorage()
            storageMessage.value = if (freed > 0) {
                "Freed ${com.soundify.app.ui.formatBytes(freed)} of Clean Video results"
            } else {
                "No Clean Video results to remove"
            }
        }
    }

    fun clearStorageMessage() {
        storageMessage.value = null
    }

    /** One-tap cleanup: temporary files plus every Clean Video result, projects kept. */
    fun freeUpSpace() {
        viewModelScope.launch(Dispatchers.IO) {
            val temporary = storageManager.cleanTemporary()
            val results = storageManager.deleteAllCleanVideo()
            CleanVideoController.clearResult()
            refreshHistory()
            refreshStorage()
            val freed = temporary + results
            storageMessage.value = if (freed > 0) {
                "Freed ${com.soundify.app.ui.formatBytes(freed)}"
            } else {
                "Nothing to remove"
            }
        }
    }

    fun refreshHistory() {
        history.value = historyStore.list()
    }

    fun refreshStorage() {
        storage.value = storageManager.usage()
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

    private fun resultFor(entry: CleanVideoHistoryEntry): CleanVideoResult? {
        val dir = File(CleanVideoStorage.exportRoot(getApplication()), entry.id)
        val stems = entry.stems.mapNotNull { record ->
            val kind = runCatching { CleanStem.valueOf(record.kind) }.getOrNull() ?: return@mapNotNull null
            val file = File(dir, record.fileName)
            if (!file.isFile) return@mapNotNull null
            StemResult(kind, file, record.durationMs, record.rmsDb, record.peakDb)
        }
        val byKind = stems.associateBy { it.kind }
        val voice = byKind[CleanStem.VOICE] ?: return null
        val sfx = byKind[CleanStem.SFX] ?: return null
        val music = byKind[CleanStem.MUSIC] ?: return null
        val clean = byKind[CleanStem.CLEAN] ?: return null
        return CleanVideoResult(
            voice = voice,
            sfx = sfx,
            music = music,
            clean = clean,
            sourceUri = Uri.parse(entry.sourceUri.orEmpty()),
            sourceName = entry.displayName,
            videoCodec = entry.videoCodec,
            videoDurationMs = entry.durationMs,
            cleanNote = entry.cleanNote,
        )
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
