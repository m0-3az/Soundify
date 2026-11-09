package com.soundify.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundify.app.Screen
import com.soundify.app.SoundifyViewModel
import java.io.File

@Composable
fun SoundifyApp(viewModel: SoundifyViewModel) {
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
                onCleanVideo = viewModel::openCleanVideo
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
                        onCleanVideo = viewModel::openCleanVideo
                    )
                }
            }

            is Screen.Settings -> SettingsScreen(
                storageUsed = storage,
                onBack = viewModel::back
            )

            is Screen.CleanVideo -> CleanVideoScreen(
                onBack = viewModel::back
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
