package com.soundify.app.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.soundify.app.CleanVideoViewModel
import com.soundify.app.cleanvideo.CleanVideoResult
import java.io.File

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

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
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

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clean Video") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                enabled = !processing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (uri == null) "Choose video" else "Choose another video")
            }

            name?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
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
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(status ?: "Working…", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = viewModel::cancel) { Text("Cancel") }
            }

            result?.let { ResultSection(it) }

            Spacer(Modifier.height(8.dp))
            Text(
                "A 20-minute video takes roughly 25–30 minutes on this phone. " +
                    "Keep Soundify open while it processes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultSection(result: CleanVideoResult) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cleaned audio is ready", style = MaterialTheme.typography.titleMedium)
            AudioPreview(result.cleanedWav)
            Button(
                onClick = { share(context, result.mp3, "audio/mpeg") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share MP3") }
            Button(
                onClick = { share(context, result.m4a, "audio/mp4") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share M4A") }
            result.video?.let { video ->
                Button(
                    onClick = { share(context, video, "video/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share cleaned video") }
            }
            Text(
                "Files are in Soundify's exports folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioPreview(file: File) {
    var playing by remember(file) { mutableStateOf(false) }
    val player = remember(file) { MediaPlayer() }
    DisposableEffect(file) {
        player.setOnCompletionListener { playing = false }
        onDispose { player.release() }
    }
    Button(
        onClick = {
            if (playing) {
                player.pause()
                playing = false
            } else {
                if (!player.isPlaying) {
                    player.reset()
                    player.setDataSource(file.absolutePath)
                    player.prepare()
                    player.seekTo(0)
                    player.start()
                }
                playing = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (playing) "Pause cleaned audio" else "Play cleaned audio")
    }
}

private fun share(context: Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }
}

private fun displayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()
}
