package com.soundify.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundify.app.editor.EXPORT_CANCELLED
import com.soundify.app.editor.EditorClip
import com.soundify.app.editor.EditorFormat
import com.soundify.app.editor.EditorPlayer
import com.soundify.app.editor.EditorProject
import com.soundify.app.editor.EditorViewModel
import com.soundify.app.editor.PlaybackItem
import com.soundify.app.editor.WaveformView
import com.soundify.app.editor.durationMs
import com.soundify.app.editor.locate
import com.soundify.app.editor.positionAt
import com.soundify.app.editor.renderedDurationMs
import com.soundify.app.editor.splitClip
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    project: EditorProject,
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val player = remember { EditorPlayer() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val playing by player.isPlaying.collectAsStateWithLifecycle()
    val playerPosition by player.positionMs.collectAsStateWithLifecycle()
    val playerIndex by player.currentIndex.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val importing by viewModel.isBusy.collectAsStateWithLifecycle()

    var selectedClipId by rememberSaveable(project.id) {
        mutableStateOf(project.clips.firstOrNull()?.id)
    }
    LaunchedEffect(project.clips) {
        if (project.clips.none { it.id == selectedClipId }) {
            selectedClipId = project.clips.firstOrNull()?.id
        }
    }
    val selectedClip = project.clips.firstOrNull { it.id == selectedClipId }
        ?: project.clips.firstOrNull()
    val selectedIndex = selectedClip
        ?.let { clip -> project.clips.indexOfFirst { it.id == clip.id } }
        ?.coerceAtLeast(0)
        ?: 0

    var peaks by remember(project.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(project.id, selectedClip?.id, selectedClip?.sourceHash) {
        val clip = selectedClip
        peaks = if (clip == null) {
            FloatArray(0)
        } else {
            viewModel.peaksFor(project.id, clip)?.peaks ?: FloatArray(0)
        }
    }

    var scrubMs by remember(project.id, selectedClip?.id) {
        mutableLongStateOf(selectedClip?.trimStartMs ?: 0L)
    }
    LaunchedEffect(selectedClip?.trimStartMs, selectedClip?.trimEndMs) {
        val clip = selectedClip ?: return@LaunchedEffect
        scrubMs = scrubMs.coerceIn(clip.trimStartMs, clip.trimEndMs)
    }

    var toolSheet by remember { mutableStateOf<EditorTool?>(null) }
    var saveSheetOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var exportProgress by remember { mutableIntStateOf(-1) }
    var cancelAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingFormat by rememberSaveable { mutableStateOf<String?>(null) }

    val exporting = exportProgress >= 0
    val editingEnabled = !exporting && !importing

    fun pauseIfPlaying() {
        if (playing) {
            scrubMs = playerPosition
            player.pause()
        }
    }

    fun applyEdit(key: String?, transform: (EditorProject) -> EditorProject) {
        if (!editingEnabled) return
        pauseIfPlaying()
        viewModel.edit(key, transform)
    }

    fun startExport(format: EditorFormat, target: Uri) {
        if (exporting) return
        exportProgress = 0
        cancelAction = null
        scope.launch {
            val result = viewModel.export(
                format = format,
                target = target,
                onProgress = { exportProgress = it },
                onCancelReady = { action -> cancelAction = action },
            )
            exportProgress = -1
            cancelAction = null
            statusMessage = result.fold(
                onSuccess = { "Exported ${format.label}" },
                onFailure = { error ->
                    if (error.message == EXPORT_CANCELLED) null
                    else error.message ?: "Export failed"
                },
            )
        }
    }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = result.data?.data
        val formatName = pendingFormat
        pendingFormat = null
        if (result.resultCode == Activity.RESULT_OK && target != null && formatName != null) {
            runCatching { EditorFormat.valueOf(formatName) }.getOrNull()?.let { format ->
                startExport(format, target)
            }
        }
    }

    val addPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { selected ->
        if (selected != null && editingEnabled) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addAudio(selected, displayName(context, selected)) { clipId ->
                selectedClipId = clipId
            }
        }
    }

    fun playPause() {
        val clip = selectedClip ?: return
        if (playing) {
            pauseIfPlaying()
            return
        }
        val index = project.clips.indexOfFirst { it.id == clip.id }.coerceAtLeast(0)
        val source = scrubMs.coerceIn(
            clip.trimStartMs,
            (clip.trimEndMs - 1L).coerceAtLeast(clip.trimStartMs),
        )
        val items = project.clips.map { item ->
            PlaybackItem(item, viewModel.sourceFile(project.id, item))
        }
        player.load(items, index)
        player.seekToSourceMs(source)
    }

    fun seekProject(positionMs: Long) {
        val (index, source) = project.locate(positionMs)
        val clip = project.clips.getOrNull(index) ?: return
        if (playing && index != playerIndex) player.pause()
        selectedClipId = clip.id
        scrubMs = source
        if (playerIndex == index) player.seekToSourceMs(source)
    }

    fun splitAtPlayhead() {
        val clip = selectedClip ?: return
        if (clip.trimEndMs - clip.trimStartMs < MIN_SPLIT_SOURCE_MS) return
        val index = project.clips.indexOfFirst { it.id == clip.id }
        val at = if (playing && playerIndex == selectedIndex) playerPosition else scrubMs
        pauseIfPlaying()
        viewModel.edit(null) { it.splitClip(clip.id, at) }
        viewModel.active.value?.clips?.getOrNull(index + 1)?.let { selectedClipId = it.id }
    }

    fun selectClip(clip: EditorClip) {
        pauseIfPlaying()
        selectedClipId = clip.id
        scrubMs = clip.trimStartMs
    }

    fun moveClip(from: Int, to: Int) {
        if (to !in project.clips.indices) return
        applyEdit(null) { current ->
            val clips = current.clips.toMutableList()
            val moved = clips.removeAt(from)
            clips.add(to, moved)
            current.copy(clips = clips)
        }
    }

    fun deleteClip(clip: EditorClip) {
        if (!editingEnabled) return
        val index = project.clips.indexOfFirst { it.id == clip.id }
        applyEdit(null) { current ->
            current.copy(clips = current.clips.filterNot { it.id == clip.id })
        }
        if (selectedClipId == clip.id) {
            val remaining = viewModel.active.value?.clips.orEmpty()
            selectedClipId = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id
        }
    }

    val playheadSourceMs = if (playing && playerIndex == selectedIndex) playerPosition else scrubMs
    val currentProjectMs = if (playing) {
        project.positionAt(playerIndex, playerPosition)
    } else {
        project.positionAt(selectedIndex, scrubMs)
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbar.showSnackbar(it)
            statusMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            pauseIfPlaying()
                            viewModel.undo()
                        },
                        enabled = canUndo && editingEnabled,
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    IconButton(
                        onClick = {
                            pauseIfPlaying()
                            viewModel.redo()
                        },
                        enabled = canRedo && editingEnabled,
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        onClick = { saveSheetOpen = true },
                        enabled = !exporting,
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save audio")
                    }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            enabled = !exporting && !importing,
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete project") },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (importing) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Importing audio…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (selectedClip == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Add an audio file to start.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            val duration = selectedClip.sourceDurationMs.coerceAtLeast(1L)
                            val playheadFraction =
                                ((playheadSourceMs - selectedClip.trimStartMs).toFloat() /
                                    (selectedClip.trimEndMs - selectedClip.trimStartMs)
                                        .coerceAtLeast(1L))
                                    .coerceIn(0f, 1f)
                            val loadedPeaks = peaks
                            if (loadedPeaks == null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            } else {
                                WaveformView(
                                    peaks = loadedPeaks,
                                    playheadFraction = playheadFraction,
                                    selection = (selectedClip.trimStartMs.toFloat() / duration)..
                                        (selectedClip.trimEndMs.toFloat() / duration),
                                    onSeek = { fraction ->
                                        val source = (
                                            selectedClip.trimStartMs +
                                                fraction * (selectedClip.trimEndMs -
                                                    selectedClip.trimStartMs)
                                            ).roundToLong()
                                        scrubMs = source
                                        if (playing && playerIndex == selectedIndex) {
                                            player.seekToSourceMs(source)
                                        }
                                    },
                                    onTrimChange = { range ->
                                        val start = (range.start * duration).roundToLong()
                                            .coerceIn(0L, duration - MIN_TRIM_MS)
                                        val end = (range.endInclusive * duration).roundToLong()
                                            .coerceIn(start + MIN_TRIM_MS, duration)
                                        applyEdit("trim:${selectedClip.id}") { current ->
                                            current.copy(
                                                clips = current.clips.map { item ->
                                                    if (item.id == selectedClip.id) {
                                                        item.copy(
                                                            trimStartMs = start,
                                                            trimEndMs = end,
                                                        )
                                                    } else {
                                                        item
                                                    }
                                                },
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                selectedClip.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Drag the handles to trim.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = ::playPause,
                            enabled = editingEnabled && selectedClip != null,
                        ) {
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Slider(
                                value = currentProjectMs.toFloat(),
                                onValueChange = { seekProject(it.roundToLong()) },
                                valueRange = 0f..project.durationMs.coerceAtLeast(1L).toFloat(),
                                enabled = editingEnabled && project.clips.isNotEmpty(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(formatTime(currentProjectMs), style = MaterialTheme.typography.labelSmall)
                                Text(formatTime(project.durationMs), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolButton("Trim", editingEnabled && selectedClip != null) {
                        toolSheet = EditorTool.Trim
                    }
                    ToolButton(
                        label = "Split",
                        enabled = editingEnabled && selectedClip != null &&
                            selectedClip.trimEndMs - selectedClip.trimStartMs >= MIN_SPLIT_SOURCE_MS,
                        icon = Icons.Default.ContentCut,
                    ) {
                        splitAtPlayhead()
                    }
                    ToolButton("Fade", editingEnabled && selectedClip != null) {
                        toolSheet = EditorTool.Fade
                    }
                    ToolButton("Volume", editingEnabled && selectedClip != null) {
                        toolSheet = EditorTool.Volume
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Clips (${project.clips.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { addPicker.launch(arrayOf("audio/*")) },
                        enabled = editingEnabled,
                    ) {
                        Text("Add clip")
                    }
                }
            }

            itemsIndexed(project.clips, key = { _, clip -> clip.id }) { index, clip ->
                ClipCard(
                    clip = clip,
                    selected = clip.id == selectedClip?.id,
                    enabled = editingEnabled,
                    canMoveUp = index > 0,
                    canMoveDown = index < project.clips.lastIndex,
                    onSelect = { selectClip(clip) },
                    onMoveUp = { moveClip(index, index - 1) },
                    onMoveDown = { moveClip(index, index + 1) },
                    onDelete = { deleteClip(clip) },
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Original is never modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Crossfade and effects are applied on export.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    toolSheet?.let { tool ->
        val clip = selectedClip
        if (clip != null) {
            EditorToolsSheet(
                tool = tool,
                clip = clip,
                enabled = editingEnabled,
                onToolChange = { toolSheet = it },
                onTrim = { start, end ->
                    applyEdit("trim:${clip.id}") { current ->
                        current.copy(
                            clips = current.clips.map { item ->
                                if (item.id == clip.id) {
                                    item.copy(trimStartMs = start, trimEndMs = end)
                                } else {
                                    item
                                }
                            },
                        )
                    }
                },
                onFadeIn = { value ->
                    applyEdit("fadeIn:${clip.id}") { current ->
                        current.copy(
                            clips = current.clips.map { item ->
                                if (item.id == clip.id) item.copy(fadeInMs = value) else item
                            },
                        )
                    }
                },
                onFadeOut = { value ->
                    applyEdit("fadeOut:${clip.id}") { current ->
                        current.copy(
                            clips = current.clips.map { item ->
                                if (item.id == clip.id) item.copy(fadeOutMs = value) else item
                            },
                        )
                    }
                },
                onVolume = { value ->
                    applyEdit("volume:${clip.id}") { current ->
                        current.copy(
                            clips = current.clips.map { item ->
                                if (item.id == clip.id) item.copy(gainDb = value) else item
                            },
                        )
                    }
                },
                onDismiss = { toolSheet = null },
            )
        }
    }

    if (saveSheetOpen) {
        SaveSheet(
            initialName = project.name,
            onDismiss = { saveSheetOpen = false },
            onExport = { format, name ->
                saveSheetOpen = false
                scope.launch {
                    viewModel.edit("name") { current ->
                        current.copy(name = name.trim().ifBlank { "Untitled edit" })
                    }
                    viewModel.saveNow()
                    pendingFormat = format.name
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = format.mime
                        putExtra(
                            Intent.EXTRA_TITLE,
                            "${safeFileName(name)}.${format.extension}",
                        )
                    }
                    saver.launch(intent)
                }
            },
        )
    }

    if (exporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { exportProgress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${exportProgress.coerceIn(0, 100)}%")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { cancelAction?.invoke() },
                    enabled = cancelAction != null,
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "This removes the edit and its audio copies from this device. " +
                        "The original file is not touched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleted()
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ToolButton(
    label: String,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label)
    }
}

@Composable
private fun ClipCard(
    clip: EditorClip,
    selected: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(
                    clip.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${seconds(clip.trimStartMs)} – ${seconds(clip.trimEndMs)}" +
                        " · ${seconds(clip.renderedDurationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMoveUp, enabled = enabled && canMoveUp) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move clip up")
            }
            IconButton(onClick = onMoveDown, enabled = enabled && canMoveDown) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move clip down")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, contentDescription = "Delete clip")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(
    initialName: String,
    onDismiss: () -> Unit,
    onExport: (EditorFormat, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var format by remember { mutableStateOf(EditorFormat.WAV) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Save audio", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            EditorFormat.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == format,
                            role = Role.RadioButton,
                            onClick = { format = option },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option == format,
                        onClick = { format = option },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(option.label)
                }
            }
            Text(
                "Every format is 44.1 kHz stereo. The original file is never modified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onExport(format, name) }) {
                    Text("Export")
                }
            }
        }
    }
}

private fun safeFileName(name: String): String =
    name.trim()
        .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        .trim(' ', '.')
        .ifBlank { "Soundify export" }

private fun seconds(milliseconds: Long): String =
    String.format(Locale.US, "%.2f s", milliseconds / 1_000f)

private fun displayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}

private const val MIN_TRIM_MS = 250L
private const val MIN_SPLIT_SOURCE_MS = 500L
