package com.soundify.app.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the editor project list, the open project, its undo history and
 * debounced persistence. All state updates happen on the main dispatcher;
 * file IO runs on [Dispatchers.IO].
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EditorStore(File(application.filesDir, STORE_DIR))
    private val importer = EditorImporter(application, store)
    private val exporter by lazy { EditorExporter(application, store) }
    private val history = EditorHistory<EditorProject>()

    private val _projects = MutableStateFlow<List<EditorSummary>>(emptyList())
    val projects: StateFlow<List<EditorSummary>> = _projects.asStateFlow()

    private val _active = MutableStateFlow<EditorProject?>(null)
    val active: StateFlow<EditorProject?> = _active.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()

    private val deletingIds = ConcurrentHashMap.newKeySet<String>()
    private var saveJob: Job? = null
    private var saveJobProjectId: String? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value = runCatching { store.list() }.getOrDefault(_projects.value)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Creates an empty project and makes it active. [onCreated] runs on the
     * main dispatcher with the new project id.
     */
    fun create(name: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            savePending()
            val project = runCatching { store.create(name.trim().ifBlank { UNTITLED }) }
                .getOrElse {
                    withContext(Dispatchers.Main) {
                        _message.value = "Could not create this edit. Free some space and try again."
                    }
                    return@launch
                }
            _projects.value = runCatching { store.list() }.getOrDefault(_projects.value)
            withContext(Dispatchers.Main) {
                activate(project)
                onCreated(project.id)
            }
        }
    }

    /**
     * Creates a project from a picked audio file: the file is copied into the
     * project's app-private storage and becomes its first clip. [onCreated]
     * runs on the main dispatcher once the project is ready to open.
     */
    fun createFromAudio(
        uri: Uri,
        displayName: String?,
        onCreated: (String) -> Unit = {},
    ) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var createdId: String? = null
            try {
                savePending()
                val project = store.create(projectNameFor(displayName))
                createdId = project.id
                val imported = importer.import(project.id, uri, displayName)
                val clip = clipFrom(imported)
                val saved = project.copy(clips = listOf(clip))
                store.save(saved)
                val reloaded = store.load(saved.id) ?: saved
                _projects.value = store.list()
                withContext(Dispatchers.Main) {
                    // If the user opened another project while this import ran,
                    // leave it alone; the new project is in the home list.
                    if (_active.value == null || _active.value?.id == reloaded.id) {
                        activate(reloaded)
                        onCreated(reloaded.id)
                    }
                }
            } catch (error: Throwable) {
                createdId?.let { id ->
                    deletingIds.add(id)
                    store.delete(id)
                }
                _projects.value = runCatching { store.list() }.getOrDefault(_projects.value)
                withContext(Dispatchers.Main) {
                    _message.value = error.message ?: "Could not import this audio file"
                }
            } finally {
                withContext(Dispatchers.Main) { _isBusy.value = false }
            }
        }
    }

    fun open(projectId: String, onOpened: () -> Unit = {}) {
        if (projectId in deletingIds) {
            _message.value = "This edit could not be opened"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            savePending()
            val project = store.load(projectId)
            withContext(Dispatchers.Main) {
                if (project == null) {
                    _message.value = "This edit could not be opened"
                } else {
                    activate(project)
                    onOpened()
                }
            }
        }
    }

    /** App-private copy of [clip]'s source inside [projectId]'s directory. */
    fun sourceFile(projectId: String, clip: EditorClip): File =
        File(store.projectDir(projectId), clip.sourceFile)

    /** Builds or reads the cached waveform envelope for [clip]. */
    suspend fun peaksFor(projectId: String, clip: EditorClip): PeakData? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = sourceFile(projectId, clip)
                if (!source.isFile) return@runCatching null
                Waveform.decode(
                    getApplication(),
                    source,
                    store.peaksFile(projectId, clip.sourceHash),
                    clip.sourceDurationMs,
                )
            }.getOrNull()
        }

    /**
     * Copies a picked audio file into the active project and appends it as a
     * new clip. [onAdded] runs on the main dispatcher with the new clip id.
     */
    fun addAudio(uri: Uri, displayName: String?, onAdded: (String) -> Unit = {}) {
        val project = _active.value ?: return
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            var imported: ImportedSource? = null
            try {
                val source = importer.import(project.id, uri, displayName)
                imported = source
                val clip = clipFrom(source)
                withContext(Dispatchers.Main) {
                    val current = _active.value
                    val abandoned = current?.id != project.id ||
                        project.id in deletingIds ||
                        _readOnly.value
                    if (abandoned) {
                        if (source.created) source.file.delete()
                        _message.value = if (_readOnly.value) {
                            "This edit is read-only"
                        } else {
                            "This edit is no longer open"
                        }
                    } else {
                        edit(coalesceKey = null) { project ->
                            project.copy(clips = project.clips + clip)
                        }
                        if (_active.value?.clips?.any { it.id == clip.id } == true) {
                            onAdded(clip.id)
                        } else if (source.created) {
                            source.file.delete()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (imported?.created == true) imported.file.delete()
                _projects.value = runCatching { store.list() }.getOrDefault(_projects.value)
                withContext(Dispatchers.Main) {
                    _message.value = error.message ?: "Could not import this audio file"
                }
            } finally {
                withContext(Dispatchers.Main) { _isBusy.value = false }
            }
        }
    }

    /** Saves the active project, then renders it to [target] with FFmpeg. */
    suspend fun export(
        format: EditorFormat,
        target: Uri,
        onProgress: (Int) -> Unit = {},
        onCancelReady: (() -> Unit) -> Unit = {},
    ): Result<Unit> {
        if (_readOnly.value) {
            return Result.failure(
                IllegalStateException("This edit was created by a newer version and is read-only"),
            )
        }
        saveNow()
        val project = _active.value
            ?: return Result.failure(IllegalStateException("No edit is open"))
        return exporter.export(
            project = project,
            clips = project.clips,
            format = format,
            target = target,
            onProgress = onProgress,
            onCancelReady = onCancelReady,
        )
    }

    fun delete(projectId: String) {
        // Mark first: a pending or in-flight save must never recreate the dir.
        deletingIds.add(projectId)
        if (_active.value?.id == projectId) {
            _active.value = null
            _readOnly.value = false
            history.reset()
            refreshHistoryFlags()
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (saveJobProjectId == projectId) {
                saveJob?.cancelAndJoin()
                saveJob = null
                saveJobProjectId = null
            }
            val deleted = runCatching { store.delete(projectId) }.getOrDefault(false)
            _projects.value = runCatching { store.list() }.getOrDefault(_projects.value)
            withContext(Dispatchers.Main) {
                if (!deleted) _message.value = "Could not delete this edit"
            }
        }
    }

    /**
     * Clears the active project synchronously, then deletes it. The clear must
     * happen before navigation so the screen's disposal flush cannot save the
     * project back to disk after the delete.
     */
    fun deleteActive(projectId: String) {
        delete(projectId)
    }

    /**
     * Applies [transform] to the active project, records an undo step and
     * schedules a save. Consecutive edits sharing [coalesceKey] within 500 ms
     * collapse into one undo step; pass null for a discrete edit.
     */
    fun edit(coalesceKey: String?, transform: (EditorProject) -> EditorProject) {
        val project = _active.value ?: return
        if (_readOnly.value || project.id in deletingIds) return
        val updated = transform(project).sanitized()
        if (updated == project) return
        history.record(project, coalesceKey)
        _active.value = updated
        refreshHistoryFlags()
        scheduleSave(updated.id)
    }

    fun undo() {
        val project = _active.value ?: return
        if (_readOnly.value || project.id in deletingIds) return
        val previous = history.undo(project) ?: return
        _active.value = previous
        refreshHistoryFlags()
        scheduleSave(previous.id)
    }

    fun redo() {
        val project = _active.value ?: return
        if (_readOnly.value || project.id in deletingIds) return
        val next = history.redo(project) ?: return
        _active.value = next
        refreshHistoryFlags()
        scheduleSave(next.id)
    }

    /** Schedules a save 500 ms after the last change. */
    fun save() {
        _active.value?.let { project -> scheduleSave(project.id) }
    }

    /** Saves the active project now; used before export and when leaving. */
    suspend fun saveNow() {
        val project = _active.value ?: return
        if (project.isFutureSchema || project.id in deletingIds) return
        if (saveJobProjectId == project.id) {
            saveJob?.cancelAndJoin()
            saveJob = null
            saveJobProjectId = null
        }
        withContext(Dispatchers.IO) {
            if (project.id in deletingIds) return@withContext
            runCatching {
                store.save(project)
                _projects.value = store.list()
            }.onFailure {
                withContext(Dispatchers.Main) {
                    _message.value = "Could not save this edit. Free some space and try again."
                }
            }
        }
    }

    /** Fire-and-forget save for composable disposal. */
    fun flush() {
        val project = _active.value ?: return
        if (project.isFutureSchema || project.id in deletingIds) return
        val previous = if (saveJobProjectId == project.id) saveJob else null
        saveJobProjectId = project.id
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            if (project.id in deletingIds) return@launch
            runCatching {
                store.save(project)
                _projects.value = store.list()
            }.onFailure {
                withContext(Dispatchers.Main) {
                    _message.value = "Could not save this edit. Free some space and try again."
                }
            }
        }
    }

    /** Saves then detaches the active project when leaving the editor. */
    fun closeActive() {
        val project = _active.value ?: return
        if (!project.isFutureSchema) flush()
        _active.value = null
        _readOnly.value = false
        history.reset()
        refreshHistoryFlags()
    }

    override fun onCleared() {
        saveJob?.cancel()
        // Best effort: a small JSON write, so a process teardown does not lose
        // the last edit that had not reached its 500 ms debounce.
        _active.value
            ?.takeIf { !it.isFutureSchema && it.id !in deletingIds }
            ?.let { project -> runCatching { store.save(project) } }
        super.onCleared()
    }

    private fun activate(project: EditorProject) {
        _active.value = project
        _readOnly.value = project.isFutureSchema
        history.reset()
        refreshHistoryFlags()
    }

    private fun scheduleSave(projectId: String) {
        if (saveJobProjectId == projectId) saveJob?.cancel()
        saveJobProjectId = projectId
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SAVE_DEBOUNCE_MS)
            if (projectId in deletingIds) return@launch
            val latest = _active.value
                ?.takeIf { it.id == projectId && !it.isFutureSchema }
                ?: return@launch
            runCatching {
                store.save(latest)
                _projects.value = store.list()
            }.onFailure {
                withContext(Dispatchers.Main) {
                    _message.value = "Could not save this edit. Free some space and try again."
                }
            }
        }
    }

    /** Saves a snapshot that was waiting for its debounce, before switching projects. */
    private suspend fun savePending() {
        val project = _active.value
        saveJob?.cancelAndJoin()
        saveJob = null
        saveJobProjectId = null
        if (project == null || project.isFutureSchema || project.id in deletingIds) return
        runCatching {
            store.save(project)
            _projects.value = store.list()
        }
    }

    private fun refreshHistoryFlags() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    private fun projectNameFor(displayName: String?): String =
        displayName
            ?.substringBeforeLast('.', displayName)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: UNTITLED

    private fun clipFrom(imported: ImportedSource): EditorClip = EditorClip(
        sourceHash = imported.hash,
        sourceFile = "sources/${imported.file.name}",
        displayName = imported.displayName,
        sourceDurationMs = imported.durationMs,
        sourceSampleRate = imported.sampleRate,
        sourceChannels = imported.channels,
    )

    private companion object {
        const val STORE_DIR = "editor"
        const val SAVE_DEBOUNCE_MS = 500L
        const val UNTITLED = "Untitled edit"
    }
}

/**
 * Bounded undo/redo history for immutable project snapshots.
 *
 * [record] is called with the project state *before* an edit. Consecutive
 * records with the same [key] inside [coalesceWindowMs] collapse into one undo
 * step, so a slider drag is undone in a single tap rather than per frame.
 * Passing no key never coalesces.
 */
internal class EditorHistory<T>(
    private val limit: Int = 50,
    private val coalesceWindowMs: Long = 500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()
    private var lastKey: Any? = null
    private var lastRecordedAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoSize: Int get() = undoStack.size
    val redoSize: Int get() = redoStack.size

    fun record(current: T, key: Any? = null) {
        val now = clock()
        val coalesces = key != null &&
            key == lastKey &&
            now - lastRecordedAt <= coalesceWindowMs &&
            undoStack.isNotEmpty()
        if (!coalesces) {
            undoStack.addLast(current)
            while (undoStack.size > limit) undoStack.removeFirst()
        }
        redoStack.clear()
        lastKey = key
        lastRecordedAt = now
    }

    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        while (redoStack.size > limit) redoStack.removeFirst()
        lastKey = null
        return previous
    }

    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        while (undoStack.size > limit) undoStack.removeFirst()
        lastKey = null
        return next
    }

    fun reset() {
        undoStack.clear()
        redoStack.clear()
        lastKey = null
        lastRecordedAt = 0L
    }
}
