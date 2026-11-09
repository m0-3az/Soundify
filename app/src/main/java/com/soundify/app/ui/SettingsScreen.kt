package com.soundify.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soundify.app.BuildConfig
import com.soundify.app.cleanvideo.StorageUsage
import com.soundify.app.data.ThemePreferences
import com.soundify.app.ui.theme.ThemeAccent
import com.soundify.app.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    storage: StorageUsage?,
    storageMessage: String?,
    theme: ThemePreferences,
    onBack: () -> Unit,
    onFreeUpSpace: () -> Unit,
) {
    val mode by theme.mode.collectAsStateWithLifecycle()
    val accent by theme.accent.collectAsStateWithLifecycle()
    var confirmFree by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium
            )
            ThemeMode.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = mode == option,
                            role = Role.RadioButton,
                        ) { theme.setMode(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == option,
                        onClick = { theme.setMode(option) },
                    )
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Accent",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeAccent.entries.forEach { option ->
                    FilterChip(
                        selected = accent == option,
                        onClick = { theme.setAccent(option) },
                        label = { Text(option.label) },
                        leadingIcon = {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(option.swatch, CircleShape)
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text(
                text = "Storage",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            if (storage == null) {
                Text("Measuring…", style = MaterialTheme.typography.bodyMedium)
            } else {
                StorageRow("Projects", storage.projectsBytes)
                StorageRow("Clean Video results", storage.cleanVideoBytes)
                StorageRow("Temporary files", storage.cacheBytes)
                StorageRow("App and model", storage.appBytes + storage.modelBytes)
                Spacer(Modifier.height(4.dp))
                StorageRow("Used by Soundify", storage.totalBytes, bold = true)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmFree = true },
                enabled = storage != null &&
                    (storage.cleanVideoBytes + storage.cacheBytes) > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Free up space")
            }
            Text(
                text = "Removes temporary files and Clean Video results. " +
                    "Projects and files you saved are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            storageMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text(
                text = "About Soundify",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A free, offline stem mixer and Clean Video music remover. " +
                    "No accounts, no network, no tracking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Open-source licenses",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Kotlin and Kotlinx — Apache License 2.0\n" +
                    "Jetpack Compose, AndroidX and Material 3 — Apache License 2.0\n" +
                    "Soundify application code — MIT License",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Your media stays on this device. Soundify does not request internet access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmFree) {
        AlertDialog(
            onDismissRequest = { confirmFree = false },
            title = { Text("Free up space?") },
            text = {
                Text(
                    "Removes temporary files and every Clean Video result. " +
                        "Projects and files you saved stay on the device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmFree = false
                        onFreeUpSpace()
                    }
                ) { Text("Free up space") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFree = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else null,
        )
        Text(
            formatBytes(bytes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else null,
        )
    }
}
