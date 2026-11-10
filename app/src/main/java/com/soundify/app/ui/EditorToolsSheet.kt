package com.soundify.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.soundify.app.editor.EditorClip
import com.soundify.app.editor.renderedDurationMs
import java.util.Locale
import kotlin.math.roundToLong

/** Tools that open the editor's bottom sheet; Split runs from the tool rail. */
enum class EditorTool(val label: String) {
    Trim("Trim"),
    Fade("Fade"),
    Volume("Volume"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorToolsSheet(
    tool: EditorTool,
    clip: EditorClip,
    enabled: Boolean,
    onToolChange: (EditorTool) -> Unit,
    onTrim: (startMs: Long, endMs: Long) -> Unit,
    onFadeIn: (Long) -> Unit,
    onFadeOut: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Clip tools", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorTool.entries.forEach { entry ->
                    FilterChip(
                        selected = entry == tool,
                        onClick = { onToolChange(entry) },
                        label = { Text(entry.label) },
                        enabled = enabled,
                    )
                }
            }
            when (tool) {
                EditorTool.Trim -> TrimTool(clip, enabled, onTrim)
                EditorTool.Fade -> FadeTool(clip, enabled, onFadeIn, onFadeOut)
                EditorTool.Volume -> VolumeTool(clip, enabled, onVolume)
            }
        }
    }
}

@Composable
private fun TrimTool(
    clip: EditorClip,
    enabled: Boolean,
    onTrim: (Long, Long) -> Unit,
) {
    var startText by remember(clip.id) { mutableStateOf(clip.trimStartMs.toString()) }
    var endText by remember(clip.id) { mutableStateOf(clip.trimEndMs.toString()) }
    LaunchedEffect(clip.trimStartMs) { startText = clip.trimStartMs.toString() }
    LaunchedEffect(clip.trimEndMs) { endText = clip.trimEndMs.toString() }

    val start = startText.toLongOrNull()
    val end = endText.toLongOrNull()
    val valid = start != null &&
        end != null &&
        start >= 0L &&
        end <= clip.sourceDurationMs &&
        end - start >= MIN_CLIP_MS
    val changed = start != clip.trimStartMs || end != clip.trimEndMs

    Text(
        "Drag the waveform handles, or type exact milliseconds.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = startText,
            onValueChange = { text -> startText = text.filter { it.isDigit() } },
            label = { Text("Start (ms)") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = endText,
            onValueChange = { text -> endText = text.filter { it.isDigit() } },
            label = { Text("End (ms)") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        "Source length ${clip.sourceDurationMs} ms.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!valid) {
        Text(
            "Enter a start and an end at least $MIN_CLIP_MS ms apart.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(
        onClick = {
            val applyStart = start
            val applyEnd = end
            if (valid && applyStart != null && applyEnd != null) {
                onTrim(applyStart, applyEnd)
            }
        },
        enabled = enabled && valid && changed,
    ) {
        Text("Apply trim")
    }
}

@Composable
private fun FadeTool(
    clip: EditorClip,
    enabled: Boolean,
    onFadeIn: (Long) -> Unit,
    onFadeOut: (Long) -> Unit,
) {
    val rendered = clip.renderedDurationMs.toFloat().coerceAtLeast(1f)
    var fadeIn by remember(clip.id) { mutableFloatStateOf(clip.fadeInMs.toFloat()) }
    var fadeOut by remember(clip.id) { mutableFloatStateOf(clip.fadeOutMs.toFloat()) }
    LaunchedEffect(clip.fadeInMs) { fadeIn = clip.fadeInMs.toFloat() }
    LaunchedEffect(clip.fadeOutMs) { fadeOut = clip.fadeOutMs.toFloat() }

    Text("Fade in ${seconds(fadeIn)}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = fadeIn.coerceIn(0f, rendered),
        onValueChange = { value ->
            val capped = value.coerceIn(0f, (rendered - fadeOut).coerceAtLeast(0f))
            fadeIn = capped
            onFadeIn(capped.roundToLong())
        },
        valueRange = 0f..rendered,
        enabled = enabled,
    )
    Text("Fade out ${seconds(fadeOut)}", style = MaterialTheme.typography.labelLarge)
    Slider(
        value = fadeOut.coerceIn(0f, rendered),
        onValueChange = { value ->
            val capped = value.coerceIn(0f, (rendered - fadeIn).coerceAtLeast(0f))
            fadeOut = capped
            onFadeOut(capped.roundToLong())
        },
        valueRange = 0f..rendered,
        enabled = enabled,
    )
    Text(
        "Fades are applied on export.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VolumeTool(
    clip: EditorClip,
    enabled: Boolean,
    onVolume: (Float) -> Unit,
) {
    var gain by remember(clip.id) { mutableFloatStateOf(clip.gainDb) }
    LaunchedEffect(clip.gainDb) { gain = clip.gainDb }

    Text(
        "Volume ${String.format(Locale.US, "%.1f", gain)} dB",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = gain.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB),
        onValueChange = { value ->
            gain = value
            onVolume(value)
        },
        valueRange = MIN_GAIN_DB..MAX_GAIN_DB,
        enabled = enabled,
    )
    Text(
        "Preview is capped at unity. Boosts above 0 dB are applied on export.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun seconds(milliseconds: Float): String =
    String.format(Locale.US, "%.2f s", milliseconds / 1_000f)

private const val MIN_CLIP_MS = 250L
private const val MIN_GAIN_DB = -24f
private const val MAX_GAIN_DB = 24f
