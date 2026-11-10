package com.soundify.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soundify.app.Screen
import com.soundify.app.SoundifyViewModel
import com.soundify.app.data.ThemePreferences
import com.soundify.app.editor.EditorViewModel
import java.io.File

@Composable
fun SoundifyApp(
    viewModel: SoundifyViewModel,
    themePreferences: ThemePreferences,
    editorViewModel: EditorViewModel = viewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val playing by viewModel.isPlaying.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val exported by viewModel.exportedFile.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val storage by viewModel.storageUsed.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storage.collectAsStateWithLifecycle()
    val storageMessage by viewModel.storageMessage.collectAsStateWithLifecycle()
    val editorProjects by editorViewModel.projects.collectAsStateWithLifecycle()
    val editorActive by editorViewModel.active.collectAsStateWithLifecycle()
    val editorBusy by editorViewModel.isBusy.collectAsStateWithLifecycle()
    val editorMessage by editorViewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(exported) {
        exported?.let {
            shareWav(context, it)
            viewModel.clearExportedFile()
        }
    }

    LaunchedEffect(editorMessage) {
        editorMessage?.let {
            snackbar.showSnackbar(it)
            editorViewModel.clearMessage()
        }
    }

    // A 500 ms debounce is invisible while editing, but backgrounding the app
    // inside that window must not lose the last change.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        editorViewModel.flush()
    }

    BackHandler(enabled = screen !is Screen.Library) {
        viewModel.back()
    }

    Box(Modifier.fillMaxSize()) {
        when (val destination = screen) {
            is Screen.Library -> LibraryScreen(
                projects = projects,
                onNew = viewModel::openImport,
                onOpen = viewModel::openProject,
                onRename = viewModel::renameProject,
                onDelete = viewModel::deleteProject,
                onSettings = viewModel::openSettings,
                onCleanVideo = viewModel::openCleanVideo,
                onAudioEditor = viewModel::openEditorHome
            )

            is Screen.Import -> ImportScreen(
                isImporting = importing,
                importStatus = importStatus,
                onBack = viewModel::back,
                onImport = viewModel::importStems
            )

            is Screen.Player -> {
                val project = current
                if (project != null) {
                    PlayerScreen(
                        project = project,
                        isPlaying = playing,
                        positionMs = position,
                        exportProgress = exportProgress,
                        onBack = viewModel::back,
                        onPlayPause = viewModel::togglePlay,
                        onSeek = viewModel::seekTo,
                        onGain = viewModel::setGain,
                        onPan = viewModel::setPan,
                        onMute = viewModel::toggleMute,
                        onSolo = viewModel::toggleSolo,
                        onOffset = viewModel::setOffset,
                        onMasterGain = viewModel::setMasterGain,
                        onLoop = viewModel::setLoop,
                        onLoopRange = viewModel::setLoopRange,
                        onExport = viewModel::exportWav
                    )
                } else {
                    LibraryScreen(
                        projects = projects,
                        onNew = viewModel::openImport,
                        onOpen = viewModel::openProject,
                        onRename = viewModel::renameProject,
                        onDelete = viewModel::deleteProject,
                        onSettings = viewModel::openSettings,
                        onCleanVideo = viewModel::openCleanVideo,
                        onAudioEditor = viewModel::openEditorHome
                    )
                }
            }

            is Screen.Settings -> SettingsScreen(
                storage = storageUsage,
                storageMessage = storageMessage,
                theme = themePreferences,
                onBack = viewModel::back,
                onFreeUpSpace = viewModel::freeUpSpace,
            )

            is Screen.CleanVideo -> CleanVideoScreen(
                onBack = viewModel::back
            )

            is Screen.EditorHome -> EditorHomeScreen(
                projects = editorProjects,
                isBusy = editorBusy,
                onOpen = { projectId ->
                    editorViewModel.open(projectId) { viewModel.openEditor(projectId) }
                },
                onCreate = { uri, displayName ->
                    editorViewModel.createFromAudio(uri, displayName) { projectId ->
                        if (viewModel.screen.value is Screen.EditorHome) {
                            viewModel.openEditor(projectId)
                        }
                    }
                },
                onDelete = editorViewModel::delete,
                onBack = viewModel::back,
            )

            is Screen.Editor -> {
                DisposableEffect(editorViewModel) {
                    onDispose { editorViewModel.flush() }
                }
                val project = editorActive
                if (project == null) {
                    EditorScreenShell(projectName = null, onBack = viewModel::back)
                } else {
                    EditorScreen(
                        project = project,
                        viewModel = editorViewModel,
                        onBack = viewModel::back,
                        onDeleted = {
                            editorViewModel.deleteActive(project.id)
                            viewModel.back()
                        },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreenShell(projectName: String?, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName ?: "Editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text("Opening edit…")
        }
    }
}

private fun shareWav(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share mix"))
    }
}
