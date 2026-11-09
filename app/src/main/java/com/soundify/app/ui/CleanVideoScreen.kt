package com.soundify.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soundify.app.CleanVideoViewModel
import com.soundify.app.cleanvideo.AudioFormat
import com.soundify.app.cleanvideo.CleanStage
import com.soundify.app.cleanvideo.CleanStem
import com.soundify.app.cleanvideo.CleanVideoController
import com.soundify.app.cleanvideo.CleanVideoResult
import com.soundify.app.cleanvideo.StemResult
import com.soundify.app.cleanvideo.VideoCompatibility
import com.soundify.app.cleanvideo.VideoFormat
import kotlinx.coroutines.delay
import java.util.Locale

private enum class SaveKind { AUDIO, VIDEO }

internal sealed interface PendingSave {
    data class Audio(val format: AudioFormat) : PendingSave
    data class Video(val format: VideoFormat) : PendingSave
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanVideoScreen(onBack: () -> Unit) {
    val viewModel: CleanVideoViewModel = viewModel()
    val uri by viewModel.selectedUri.collectAsStateWithLifecycle()
    val name by viewModel.selectedName.collectAsStateWithLifecycle()
    val processing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val selectedStem by viewModel.selectedStem.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val saveMessage by viewModel.saveMessage.collectAsStateWithLifecycle()
    val cleanState by viewModel.cleanState.collectAsStateWithLifecycle()
    val historyEntries by viewModel.history.collectAsStateWithLifecycle()
    val duplicate by viewModel.duplicate.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val storageMessage by viewModel.storageMessage.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }

    if (showHistory) {
        CleanVideoHistoryScreen(
            entries = historyEntries,
            storage = storage,
            storageMessage = storageMessage,
            onOpen = { entry ->
                viewModel.openHistoryEntry(entry)
                showHistory = false
            },
            onDelete = viewModel::deleteHistoryEntry,
            onClearAll = viewModel::clearHistory,
            onFreeUpSpace = viewModel::freeUpSpace,
            onBack = {
                showHistory = false
                viewModel.clearStorageMessage()
            },
        )
        return
    }

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Keep the pending format in saved state: the SAF picker can recreate the
    // activity, and the result callback must still know what to encode.
    var pendingSaveKey by rememberSaveable { mutableStateOf<String?>(null) }
    var saveSheetOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
        val target = activityResult.data?.data
        val pending = pendingSave(pendingSaveKey)
        pendingSaveKey = null
        if (activityResult.resultCode == Activity.RESULT_OK && target != null && pending != null) {
            when (pending) {
                is PendingSave.Audio -> viewModel.saveAudio(pending.format, target)
                is PendingSave.Video -> viewModel.saveVideo(pending.format, target)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.select(selected, displayName(context, selected))
        }
    }

    LaunchedEffect(message, saveMessage) {
        (message ?: saveMessage)?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
            viewModel.clearSaveMessage()
        }
    }

    val launchSave = { pending: PendingSave, mime: String, fileName: String ->
        pendingSaveKey = pending.key()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        saver.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clean Video") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = "Clean Video history")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Remove music from a video while keeping voice and sound effects. " +
                    "Everything runs on this device; no internet is used.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !processing && !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uri == null) "Choose video" else "Choose another video")
            }

            name?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }

            if (historyEntries.isNotEmpty()) {
                TextButton(onClick = { showHistory = true }) {
                    Text("History (${historyEntries.size})")
                }
            }

            if (uri != null && !processing && result == null) {
                Button(
                    onClick = viewModel::clean,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clean video")
                }
            }

            if (processing) {
                ProcessingCard(state = cleanState, onCancel = viewModel::cancel)
            }

            result?.let { output ->
                ResultSection(
                    result = output,
                    selected = selectedStem,
                    isSaving = isSaving,
                    saveStatus = saveStatus,
                    onSelect = viewModel::selectStem,
                    onSave = { saveSheetOpen = true },
                    onDelete = { confirmDelete = true },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "A 20-minute video takes roughly 25–30 minutes on this phone. " +
                    "The stems take about 850 MB until you delete them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete && result != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete stems?") },
            text = {
                Text(
                    "This removes the four Clean Video stems from Soundify's exports " +
                        "folder. Files you already saved are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteStems()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }

    val output = result
    if (saveSheetOpen && output != null) {
        val base = baseName(output.sourceName)
        val slug = stemSlug(selectedStem)
        SaveSheet(
            result = output,
            selected = selectedStem,
            onDismiss = { saveSheetOpen = false },
            onAudio = { format ->
                saveSheetOpen = false
                launchSave(
                    PendingSave.Audio(format),
                    format.mime,
                    "${base}_$slug.${format.extension}",
                )
            },
            onVideo = { format ->
                saveSheetOpen = false
                launchSave(
                    PendingSave.Video(format),
                    format.mime,
                    "${base}_$slug.${format.extension}",
                )
            },
        )
    }

    duplicate?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDuplicate,
            title = { Text("Already cleaned") },
            text = {
                Text(
                    "You already cleaned ${entry.displayName}. Open that result instead " +
                        "of processing the video again?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.openHistoryEntry(entry) }) {
                    Text("Open result")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cleanAgain) {
                    Text("Clean again")
                }
            },
        )
    }
}

@Composable
private fun ProcessingCard(state: CleanVideoController.State, onCancel: () -> Unit) {
    val target = state.progress.coerceIn(0f, 1f)
    val animationMillis = progressAnimationMillis(
        etaSeconds = state.etaSeconds,
        chunkIndex = state.chunkIndex,
        chunkCount = state.chunkCount,
    )
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = animationMillis, easing = LinearEasing),
        label = "clean-progress",
    )
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isProcessing, state.startedAtMs) {
        while (state.isProcessing) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = if (state.startedAtMs > 0L) {
        ((now - state.startedAtMs) / 1000).coerceAtLeast(0)
    } else {
        0
    }
    val cancelling = state.cancelling

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cleaning on device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Elapsed ${formatClock(elapsed)} · ${(target * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
            StageRow(CleanStage.PREPARING, "Prepare audio", state)
            StageRow(CleanStage.SEPARATING, "Separate stems", state)
            StageRow(CleanStage.FINALIZING, "Finish", state)
            if (state.stage == CleanStage.SEPARATING && state.chunkCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip("Chunk ${state.chunkIndex} / ${state.chunkCount}")
                    state.etaSeconds?.let { InfoChip("~${formatClock(it)} left") }
                }
            }
            state.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel, enabled = !cancelling) {
                Text(if (cancelling) "Cancelling…" else "Cancel")
            }
        }
    }
}

@Composable
private fun StageRow(
    stage: CleanStage,
    label: String,
    state: CleanVideoController.State,
) {
    val active = state.stage == stage && state.isProcessing
    val done = state.stage == CleanStage.DONE || ((state.stage?.ordinal ?: -1) > stage.ordinal)
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            active -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )

            else -> Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (active || done) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ResultSection(
    result: CleanVideoResult,
    selected: CleanStem,
    isSaving: Boolean,
    saveStatus: String?,
    onSelect: (CleanStem) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf<CleanStem?>(null) }
    var loaded by remember { mutableStateOf<CleanStem?>(null) }
    val volumes = remember { mutableStateMapOf<CleanStem, Float>() }

    DisposableEffect(player) {
        player.setOnCompletionListener { playing = null }
        player.setOnErrorListener { _, _, _ ->
            playing = null
            true
        }
        player.setOnPreparedListener { media ->
            val kind = loaded ?: return@setOnPreparedListener
            val volume = volumes[kind] ?: 1f
            media.setVolume(volume, volume)
            media.start()
            playing = kind
        }
        onDispose { player.release() }
    }
    LaunchedEffect(result) {
        runCatching { player.reset() }
        loaded = null
        playing = null
    }

    fun toggle(stem: StemResult) {
        if (playing == stem.kind && player.isPlaying) {
            player.pause()
            playing = null
            return
        }
        runCatching {
            player.reset()
            player.setDataSource(stem.file.absolutePath)
            loaded = stem.kind
            player.prepareAsync()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Four on-device outputs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Select a track, then save it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete, enabled = !isSaving) {
                    Text("Delete stems")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = !isSaving) {
                    Text(if (isSaving) "Saving…" else "Save")
                }
            }

            result.cleanNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            saveStatus?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            result.stems.forEach { stem ->
                StemRow(
                    stem = stem,
                    selected = selected == stem.kind,
                    playing = playing == stem.kind,
                    volume = volumes[stem.kind] ?: 1f,
                    onSelect = { onSelect(stem.kind) },
                    onToggle = { toggle(stem) },
                    onVolume = { value ->
                        volumes[stem.kind] = value
                        if (playing == stem.kind) player.setVolume(value, value)
                    },
                )
            }

            Text(
                "Stems use ${megabytes(result.stems.sumOf { it.file.length() })} in Soundify's exports folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StemRow(
    stem: StemResult,
    selected: Boolean,
    playing: Boolean,
    volume: Float,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Icon(stemIcon(stem.kind), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stem.kind.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatDuration(stem.durationMs)} · RMS ${
                            "%.1f".format(Locale.US, stem.displayRmsDb)
                        } dBFS · peak ${"%.1f".format(Locale.US, stem.displayPeakDb)} dBFS" +
                            if (stem.kind == CleanStem.CLEAN) "" else " · +6 dB on save",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
            }
            LinearProgressIndicator(
                progress = { levelFraction(stem.displayRmsDb) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Level", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSheet(
    result: CleanVideoResult,
    selected: CleanStem,
    onDismiss: () -> Unit,
    onAudio: (AudioFormat) -> Unit,
    onVideo: (VideoFormat) -> Unit,
) {
    var kind by remember { mutableStateOf<SaveKind?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Save ${selected.label}", style = MaterialTheme.typography.titleLarge)
            Text(
                "Only the chosen format is encoded when you save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (kind) {
                null -> {
                    ChoiceRow("Audio", "WAV, FLAC, MP3 or M4A") { kind = SaveKind.AUDIO }
                    ChoiceRow("Video", "Put this audio back into the video") { kind = SaveKind.VIDEO }
                }

                SaveKind.AUDIO -> {
                    TextButton(onClick = { kind = null }) { Text("Back") }
                    AudioFormat.entries.forEach { format ->
                        ChoiceRow(format.label, ".${format.extension}") { onAudio(format) }
                    }
                }

                SaveKind.VIDEO -> {
                    TextButton(onClick = { kind = null }) { Text("Back") }
                    val recommended = VideoCompatibility.default(result.videoCodec)
                    VideoCompatibility.ordered(result.videoCodec).forEach { format ->
                        ChoiceRow(
                            format.label,
                            if (format == recommended) {
                                ".${format.extension} · recommended for ${result.videoCodec ?: "this video"}"
                            } else {
                                ".${format.extension}"
                            },
                        ) { onVideo(format) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun stemIcon(kind: CleanStem) = when (kind) {
    CleanStem.VOICE -> Icons.Default.Mic
    CleanStem.SFX -> Icons.Default.GraphicEq
    CleanStem.MUSIC -> Icons.Default.MusicNote
    CleanStem.CLEAN -> Icons.Default.CheckCircle
}

private fun levelFraction(rmsDb: Float): Float =
    ((rmsDb + 60f) / 60f).coerceIn(0f, 1f)

/**
 * Progress arrives once per 6 s chunk, so tween across roughly the time the
 * next chunk is expected to take instead of stalling for 7–10 s.
 */
internal fun progressAnimationMillis(
    etaSeconds: Long?,
    chunkIndex: Int,
    chunkCount: Int,
): Int {
    val remaining = (chunkCount - chunkIndex).coerceAtLeast(1)
    val perChunk = etaSeconds?.let { it * 1000L / remaining } ?: 700L
    return perChunk.coerceIn(500L, 10_000L).toInt()
}

internal fun PendingSave.key(): String = when (this) {
    is PendingSave.Audio -> "audio:${format.name}"
    is PendingSave.Video -> "video:${format.name}"
}

internal fun pendingSave(key: String?): PendingSave? {
    if (key == null) return null
    val name = key.substringAfter(':', "")
    return when {
        key.startsWith("audio:") -> AudioFormat.entries
            .firstOrNull { it.name == name }
            ?.let { PendingSave.Audio(it) }

        key.startsWith("video:") -> VideoFormat.entries
            .firstOrNull { it.name == name }
            ?.let { PendingSave.Video(it) }

        else -> null
    }
}

private fun formatDuration(ms: Long): String = formatClock(ms / 1000)

private fun formatClock(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}

private fun megabytes(bytes: Long): String =
    "%.1f MB".format(Locale.US, bytes / 1_000_000.0)

private fun baseName(name: String?): String =
    name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Soundify"

private fun stemSlug(kind: CleanStem): String = when (kind) {
    CleanStem.VOICE -> "voice"
    CleanStem.SFX -> "sfx"
    CleanStem.MUSIC -> "music"
    CleanStem.CLEAN -> "clean"
}

private fun displayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
