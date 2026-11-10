package com.soundify.app.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the editor project list, the open project, its undo history and
 * debounced persistence. All state updates happen on the main dispatcher;
 * file IO runs on [Dispatchers.IO].
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val store = EditorStore(File(application.filesDir, STORE_DIR))
    private val importer = EditorImporter(application, store)
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

    private var saveJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _projects.value = store.list()
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
            val project = store.create(name.trim().ifBlank { UNTITLED })
            _projects.value = store.list()
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
                val project = store.create(projectNameFor(displayName))
                createdId = project.id
                val imported = importer.import(project.id, uri, displayName)
                val clip = EditorClip(
                    sourceHash = imported.hash,
                    sourceFile = "sources/${imported.file.name}",
                    displayName = imported.displayName,
                    sourceDurationMs = imported.durationMs,
                    sourceSampleRate = imported.sampleRate,
                    sourceChannels = imported.channels,
                )
                val saved = project.copy(clips = listOf(clip))
                store.save(saved)
                val reloaded = store.load(saved.id) ?: saved
                _projects.value = store.list()
                withContext(Dispatchers.Main) {
                    activate(reloaded)
                    onCreated(reloaded.id)
                }
            } catch (error: Throwable) {
                createdId?.let { store.delete(it) }
                _projects.value = store.list()
                withContext(Dispatchers.Main) {
                    _message.value = error.message ?: "Could not import this audio file"
                }
            } finally {
                withContext(Dispatchers.Main) { _isBusy.value = false }
            }
        }
    }

    fun open(projectId: String, onOpened: () -> Unit = {}) {
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

    fun delete(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            saveJob?.cancel()
            val deleted = store.delete(projectId)
            _projects.value = store.list()
            withContext(Dispatchers.Main) {
                if (!deleted) {
                    _message.value = "Could not delete this edit"
                } else if (_active.value?.id == projectId) {
                    _active.value = null
                    history.reset()
                    refreshHistoryFlags()
                }
            }
        }
    }

    /**
     * Applies [transform] to the active project, records an undo step and
     * schedules a save. Consecutive edits sharing [coalesceKey] within 500 ms
     * collapse into one undo step; pass null for a discrete edit.
     */
    fun edit(coalesceKey: String?, transform: (EditorProject) -> EditorProject) {
        val project = _active.value ?: return
        val updated = transform(project).sanitized()
        if (updated == project) return
        history.record(project, coalesceKey)
        _active.value = updated
        refreshHistoryFlags()
        scheduleSave(updated)
    }

    fun undo() {
        val project = _active.value ?: return
        val previous = history.undo(project) ?: return
        _active.value = previous
        refreshHistoryFlags()
        scheduleSave(previous)
    }

    fun redo() {
        val project = _active.value ?: return
        val next = history.redo(project) ?: return
        _active.value = next
        refreshHistoryFlags()
        scheduleSave(next)
    }

    /** Schedules a save 500 ms after the last change. */
    fun save() {
        _active.value?.let(::scheduleSave)
    }

    /** Saves the active project now; used before export and when leaving. */
    suspend fun saveNow() {
        saveJob?.cancel()
        val project = _active.value ?: return
        withContext(Dispatchers.IO) {
            store.save(project)
            _projects.value = store.list()
        }
    }

    /** Fire-and-forget save for composable disposal. */
    fun flush() {
        saveJob?.cancel()
        val project = _active.value ?: return
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            store.save(project)
            _projects.value = store.list()
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        // Best effort: a small JSON write, so a process teardown does not lose
        // the last edit that had not reached its 500 ms debounce.
        _active.value?.let { project -> runCatching { store.save(project) } }
        super.onCleared()
    }

    private fun activate(project: EditorProject) {
        saveJob?.cancel()
        _active.value = project
        history.reset()
        refreshHistoryFlags()
    }

    private fun scheduleSave(project: EditorProject) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SAVE_DEBOUNCE_MS)
            store.save(project)
            _projects.value = store.list()
        }
    }

    /** Saves a snapshot that was waiting for its debounce, before switching projects. */
    private suspend fun savePending() {
        saveJob?.cancel()
        val project = _active.value ?: return
        store.save(project)
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
