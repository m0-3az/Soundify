package com.soundify.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soundify.app.data.Project
import com.soundify.app.data.Stem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    project: Project,
    isPlaying: Boolean,
    positionMs: Long,
    exportProgress: Float?,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onGain: (String, Float) -> Unit,
    onPan: (String, Float) -> Unit,
    onMute: (String) -> Unit,
    onSolo: (String) -> Unit,
    onOffset: (String, Long) -> Unit,
    onMasterGain: (Float) -> Unit,
    onLoop: (Boolean) -> Unit,
    onLoopRange: (Long, Long) -> Unit,
    onExport: () -> Unit
) {
    var sliderPosition by remember(project.id) { mutableStateOf(positionMs.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(positionMs, dragging) {
        if (!dragging) sliderPosition = positionMs.toFloat()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = project.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onExport, enabled = exportProgress == null) {
                        Icon(Icons.Default.Share, contentDescription = "Export mix")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Slider(
                    value = sliderPosition,
                    onValueChange = {
                        dragging = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeek(sliderPosition.toLong())
                    },
                    valueRange = 0f..project.durationMs.toFloat().coerceAtLeast(1f)
                )

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatTime(sliderPosition.toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatTime(project.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    FilterChip(
                        selected = project.loopEnabled,
                        onClick = { onLoop(!project.loopEnabled) },
                        label = { Text("Loop") },
                        leadingIcon = {
                            Icon(Icons.Default.Loop, contentDescription = null)
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${project.stemCount} stems",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (project.loopEnabled) {
                LoopRangeCard(
                    project = project,
                    onLoopRange = onLoopRange
                )
            }

            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = project.stems.sortedBy { it.sortOrder },
                    key = { it.id }
                ) { stem ->
                    StemStrip(
                        stem = stem,
                        onGain = { onGain(stem.id, it) },
                        onPan = { onPan(stem.id, it) },
                        onMute = { onMute(stem.id) },
                        onSolo = { onSolo(stem.id) },
                        onOffset = { onOffset(stem.id, it) }
                    )
                }
            }

            MasterSection(
                gain = project.masterGain,
                onGain = onMasterGain
            )

            if (exportProgress != null) {
                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )
                Text(
                    text = "Exporting ${(exportProgress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            } else {
                Button(
                    onClick = onExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text("Export WAV")
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StemStrip(
    stem: Stem,
    onGain: (Float) -> Unit,
    onPan: (Float) -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onOffset: (Long) -> Unit
) {
    var offset by remember(stem.id) { mutableStateOf(stem.offsetMs) }
    var expanded by remember(stem.id) { mutableStateOf(false) }
    LaunchedEffect(stem.offsetMs) { offset = stem.offsetMs }

    Card {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stem.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stem.role.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onMute) {
                    Text(
                        text = "M",
                        fontWeight = FontWeight.Bold,
                        color = if (stem.muted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(onClick = onSolo) {
                    Text(
                        text = "S",
                        fontWeight = FontWeight.Bold,
                        color = if (stem.soloed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = if (expanded) "Hide details" else "More controls",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expanded) {
                Text(
                    text = "Gain ${(stem.gain * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = stem.gain,
                    onValueChange = onGain,
                    valueRange = 0f..2f
                )

                Text(
                    text = "Pan ${panLabel(stem.pan)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = stem.pan,
                    onValueChange = onPan,
                    valueRange = -1f..1f
                )

                Text(
                    text = "Offset $offset ms",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = offset.toFloat(),
                    onValueChange = { offset = it.roundToInt().toLong() },
                    onValueChangeFinished = { onOffset(offset) },
                    valueRange = -500f..500f
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gain ${(stem.gain * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = stem.gain,
                            onValueChange = onGain,
                            valueRange = 0f..2f
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pan ${panLabel(stem.pan)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = stem.pan,
                            onValueChange = onPan,
                            valueRange = -1f..1f
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoopRangeCard(
    project: Project,
    onLoopRange: (Long, Long) -> Unit
) {
    var start by remember(project.id) { mutableStateOf(project.loopStartMs) }
    var end by remember(project.id) { mutableStateOf(project.loopEndMs) }
    LaunchedEffect(project.loopStartMs) { start = project.loopStartMs }
    LaunchedEffect(project.loopEndMs) { end = project.loopEndMs }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Loop ${formatTime(start)} – ${formatTime(end)}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "Start",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = start.toFloat(),
                onValueChange = {
                    start = it.toLong().coerceIn(0L, (end - 1000L).coerceAtLeast(0L))
                },
                onValueChangeFinished = { onLoopRange(start, end) },
                valueRange = 0f..project.durationMs.toFloat().coerceAtLeast(1f)
            )
            Text(
                text = "End",
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = end.toFloat(),
                onValueChange = {
                    end = it.toLong().coerceIn(
                        (start + 1000L).coerceAtMost(project.durationMs),
                        project.durationMs
                    )
                },
                onValueChangeFinished = { onLoopRange(start, end) },
                valueRange = 0f..project.durationMs.toFloat().coerceAtLeast(1f)
            )
        }
    }
}

@Composable
private fun MasterSection(gain: Float, onGain: (Float) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Master ${(gain * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall
            )
            Slider(
                value = gain,
                onValueChange = onGain,
                valueRange = 0f..2f
            )
        }
    }
}

private fun panLabel(pan: Float): String = when {
    pan < -0.05f -> "${(-pan * 100).roundToInt()}% left"
    pan > 0.05f -> "${(pan * 100).roundToInt()}% right"
    else -> "center"
}
