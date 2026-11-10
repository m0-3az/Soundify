package com.soundify.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundify.app.audio.AudioInfo
import com.soundify.app.audio.AudioMetadata
import com.soundify.app.audio.StemMixer
import com.soundify.app.audio.WavExporter
import com.soundify.app.cleanvideo.CleanVideoController
import com.soundify.app.cleanvideo.StorageManager
import com.soundify.app.cleanvideo.StorageUsage
import com.soundify.app.data.Project
import com.soundify.app.data.ProjectStore
import com.soundify.app.data.RoleMapper
import com.soundify.app.data.Stem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface Screen {
    data object Library : Screen
    data object Import : Screen
    data class Player(val projectId: String) : Screen
    data object Settings : Screen
    data object CleanVideo : Screen
    data object EditorHome : Screen
    data class Editor(val projectId: String) : Screen
}

class SoundifyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ProjectStore(application)
    private val context: Context get() = getApplication()

    val screen = MutableStateFlow<Screen>(Screen.Library)
    val projects = MutableStateFlow<List<Project>>(emptyList())
    val current = MutableStateFlow<Project?>(null)
    val isPlaying = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val isImporting = MutableStateFlow(false)
    val importStatus = MutableStateFlow<String?>(null)
    val exportProgress = MutableStateFlow<Float?>(null)
    val exportedFile = MutableStateFlow<File?>(null)
    val message = MutableStateFlow<String?>(null)
    val storageUsed = MutableStateFlow(0L)
    val storage = MutableStateFlow<StorageUsage?>(null)
    val storageMessage = MutableStateFlow<String?>(null)

    private var mixer: StemMixer? = null
    private var exportJob: Job? = null
    private var saveJob: Job? = null

    init {
        refreshProjects()
    }

    fun refreshProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            projects.value = store.list()
        }
    }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            storageUsed.value = store.storageUsedBytes()
            storage.value = StorageManager(context).usage()
        }
    }

    fun freeUpSpace() {
        if (CleanVideoController.state.value.isProcessing) {
            storageMessage.value = "Wait for Clean Video to finish before cleaning storage."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val manager = StorageManager(context)
            val temporary = manager.cleanTemporary()
            val results = manager.deleteAllCleanVideo()
            CleanVideoController.clearResult()
            storageUsed.value = store.storageUsedBytes()
            storage.value = manager.usage()
            val freed = temporary + results
            storageMessage.value = if (freed > 0) {
                "Freed ${com.soundify.app.ui.formatBytes(freed)}"
            } else {
                "Nothing to remove"
            }
        }
    }

    fun clearStorageMessage() {
        storageMessage.value = null
    }

    fun openImport() {
        screen.value = Screen.Import
    }

    fun openSettings() {
        storageMessage.value = null
        refreshStorage()
        screen.value = Screen.Settings
    }

    fun openCleanVideo() {
        screen.value = Screen.CleanVideo
    }

    fun openEditorHome() {
        screen.value = Screen.EditorHome
    }

    fun openEditor(projectId: String) {
        screen.value = Screen.Editor(projectId)
    }

    fun renameProject(project: Project, name: String) {
        val trimmed = name.trim().ifBlank { "Untitled" }
        val updated = project.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        if (current.value?.id == project.id) {
            current.value = updated
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.save(updated)
            projects.value = store.list()
        }
    }

    fun deleteProject(project: Project) {
        if (current.value?.id == project.id) {
            val engine = mixer
            mixer = null
            isPlaying.value = false
            current.value = null
            screen.value = Screen.Library
            stopPlaybackService()
            viewModelScope.launch(Dispatchers.IO) { engine?.release() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(project.id)
            projects.value = store.list()
        }
    }

    fun openProject(project: Project) {
        saveJob?.cancel()
        mixer?.release()
        mixer = null

        current.value = project
        positionMs.value = project.lastPositionMs
        isPlaying.value = false
        screen.value = Screen.Player(project.id)

        val engine = StemMixer(
            project = project,
            onPosition = { positionMs.value = it },
            onFinished = {
                isPlaying.value = false
                positionMs.value = project.durationMs
                stopPlaybackService()
            },
            onError = { message.value = it }
        )
        mixer = engine
        engine.start(project.lastPositionMs)
    }

    fun closePlayer() {
        val project = current.value?.copy(lastPositionMs = positionMs.value)
        val engine = mixer
        mixer = null
        isPlaying.value = false
        current.value = null
        screen.value = Screen.Library
        stopPlaybackService()

        viewModelScope.launch(Dispatchers.IO) {
            project?.let { store.save(it) }
            engine?.release()
            projects.value = store.list()
        }
    }

    fun importStems(uris: List<Uri>, name: String) {
        if (isImporting.value) return
        if (uris.size !in 2..8) {
            message.value = "Choose 2 to 8 stems"
            return
        }

        isImporting.value = true
        importStatus.value = "Reading files…"
        viewModelScope.launch(Dispatchers.IO) {
            var createdProjectId: String? = null
            try {
                val infos = uris.map { AudioMetadata.read(context, it) }
                validate(infos)

                val sampleRate = infos.first().sampleRate
                val durationMs = infos.maxOf { it.durationMs }
                val projectId = UUID.randomUUID().toString()
                createdProjectId = projectId
                val stemsDir = store.stemsDir(projectId)
                val stems = mutableListOf<Stem>()

                infos.forEachIndexed { index, info ->
                    importStatus.value = "Copying ${index + 1} of ${infos.size}…"
                    val destination = File(stemsDir, "${UUID.randomUUID()}.${extensionFor(info)}")
                    context.contentResolver.openInputStream(uris[index])?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Could not open ${info.displayName}")

                    val role = RoleMapper.infer(info.displayName)
                    stems += Stem(
                        role = role,
                        displayName = RoleMapper.defaultDisplayName(role, index),
                        filePath = destination.absolutePath,
                        format = info.mime,
                        sampleRate = info.sampleRate,
                        channels = info.channels,
                        durationMs = info.durationMs,
                        sortOrder = index
                    )
                }

                val project = Project(
                    id = projectId,
                    name = name.trim().ifBlank { "Untitled" },
                    sampleRate = sampleRate,
                    durationMs = durationMs,
                    loopEndMs = durationMs,
                    stems = stems
                )
                store.save(project)

                withContext(Dispatchers.Main) {
                    isImporting.value = false
                    importStatus.value = null
                    refreshProjects()
                    openProject(project)
                }
            } catch (t: Throwable) {
                createdProjectId?.let { store.delete(it) }
                withContext(Dispatchers.Main) {
                    isImporting.value = false
                    importStatus.value = null
                    message.value = t.message ?: "Import failed"
                }
            }
        }
    }

    fun togglePlay() {
        val engine = mixer ?: return
        if (isPlaying.value) {
            engine.pause()
            isPlaying.value = false
            stopPlaybackService()
        } else {
            if (positionMs.value >= (current.value?.durationMs ?: 0L)) {
                engine.seekTo(0L)
                positionMs.value = 0L
            }
            engine.play()
            isPlaying.value = true
            startPlaybackService()
        }
    }

    fun seekTo(positionMs: Long) {
        val duration = current.value?.durationMs ?: 0L
        val clamped = positionMs.coerceIn(0L, duration)
        this.positionMs.value = clamped
        mixer?.seekTo(clamped)
    }

    fun setGain(stemId: String, gain: Float) {
        val project = current.value ?: return
        val updated = project.updated(stemId) { it.copy(gain = gain.coerceIn(0f, 2f)) }
        current.value = updated
        mixer?.setGain(stemId, gain)
        saveSoon(updated)
    }

    fun setPan(stemId: String, pan: Float) {
        val project = current.value ?: return
        val updated = project.updated(stemId) { it.copy(pan = pan.coerceIn(-1f, 1f)) }
        current.value = updated
        mixer?.setPan(stemId, pan)
        saveSoon(updated)
    }

    fun toggleMute(stemId: String) {
        val project = current.value ?: return
        val stem = project.stems.firstOrNull { it.id == stemId } ?: return
        val muted = !stem.muted
        val updated = project.updated(stemId) { it.copy(muted = muted) }
        current.value = updated
        mixer?.setMuted(stemId, muted)
        saveSoon(updated)
    }

    fun toggleSolo(stemId: String) {
        val project = current.value ?: return
        val stem = project.stems.firstOrNull { it.id == stemId } ?: return
        val soloed = !stem.soloed
        val updated = project.updated(stemId) { it.copy(soloed = soloed) }
        current.value = updated
        mixer?.setSoloed(stemId, soloed)
        saveSoon(updated)
    }

    fun setOffset(stemId: String, offsetMs: Long) {
        val project = current.value ?: return
        val clamped = offsetMs.coerceIn(-500L, 500L)
        val updated = project.updated(stemId) { it.copy(offsetMs = clamped) }
        current.value = updated
        mixer?.setOffset(stemId, clamped)
        saveSoon(updated)
    }

    fun setMasterGain(gain: Float) {
        val project = current.value ?: return
        val clamped = gain.coerceIn(0f, 2f)
        val updated = project.copy(masterGain = clamped)
        current.value = updated
        mixer?.setMasterGain(clamped)
        saveSoon(updated)
    }

    fun setLoop(enabled: Boolean) {
        val project = current.value ?: return
        val updated = project.copy(loopEnabled = enabled)
        current.value = updated
        mixer?.setLoop(enabled, updated.loopStartMs, updated.loopEndMs)
        saveSoon(updated)
    }

    fun setLoopRange(startMs: Long, endMs: Long) {
        val project = current.value ?: return
        val start = startMs.coerceIn(0L, (project.durationMs - 1000L).coerceAtLeast(0L))
        val minEnd = (start + 1000L).coerceAtMost(project.durationMs)
        val end = endMs.coerceIn(minEnd, project.durationMs)
        val updated = project.copy(loopStartMs = start, loopEndMs = end)
        current.value = updated
        mixer?.setLoop(updated.loopEnabled, start, end)
        saveSoon(updated)
    }

    fun exportWav() {
        val project = current.value ?: return
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            exportProgress.value = 0f
            try {
                val file = File(
                    store.exportsDir(),
                    "${safeFileName(project.name)}-${System.currentTimeMillis()}.wav"
                )
                WavExporter(project).export(file) { exportProgress.value = it }
                withContext(Dispatchers.Main) { exportedFile.value = file }
            } catch (t: Throwable) {
                message.value = "Export failed: ${t.message}"
            } finally {
                exportProgress.value = null
            }
        }
    }

    fun clearExportedFile() {
        exportedFile.value = null
    }

    fun clearMessage() {
        message.value = null
    }

    fun back() {
        when (screen.value) {
            is Screen.Library -> Unit
            is Screen.Import -> screen.value = Screen.Library
            is Screen.Settings -> screen.value = Screen.Library
            is Screen.Player -> closePlayer()
            is Screen.CleanVideo -> screen.value = Screen.Library
            is Screen.Editor -> screen.value = Screen.EditorHome
            is Screen.EditorHome -> screen.value = Screen.Library
        }
    }

    override fun onCleared() {
        mixer?.release()
        mixer = null
        stopPlaybackService()
        super.onCleared()
    }

    private fun startPlaybackService() {
        runCatching {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }

    private fun stopPlaybackService() {
        runCatching {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }

    private fun validate(infos: List<AudioInfo>) {
        infos.forEach { info ->
            if (!AudioMetadata.isSupported(info.mime)) {
                error("Unsupported format: ${info.displayName}")
            }
            if (info.channels !in 1..2) {
                error("Only mono and stereo stems are supported")
            }
            if (info.sampleRate !in listOf(44100, 48000)) {
                error("Use 44.1 kHz or 48 kHz stems")
            }
            if (info.durationMs <= 0L) {
                error("Could not read the duration of ${info.displayName}")
            }
        }
        val sampleRate = infos.first().sampleRate
        if (infos.any { it.sampleRate != sampleRate }) {
            error("All stems must share the same sample rate")
        }
        if (infos.maxOf { it.durationMs } > 30 * 60 * 1000L) {
            error("Projects are limited to 30 minutes")
        }
    }

    private fun extensionFor(info: AudioInfo): String {
        val fromName = info.displayName.substringAfterLast('.', "").lowercase()
        if (fromName in setOf("wav", "flac", "mp3", "m4a", "aac", "mp4")) {
            return if (fromName == "mp4") "m4a" else fromName
        }
        return when {
            info.mime.contains("mpeg") -> "mp3"
            info.mime.contains("flac") -> "flac"
            info.mime.contains("mp4") || info.mime.contains("m4a") -> "m4a"
            info.mime.contains("aac") -> "aac"
            else -> "wav"
        }
    }

    private fun saveSoon(project: Project) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(350)
            store.save(project)
            projects.value = store.list()
        }
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "mix" }
}
