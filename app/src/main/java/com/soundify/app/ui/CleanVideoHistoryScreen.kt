package com.soundify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundify.app.cleanvideo.CleanVideoHistoryEntry
import com.soundify.app.cleanvideo.StorageUsage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanVideoHistoryScreen(
    entries: List<CleanVideoHistoryEntry>,
    storage: StorageUsage?,
    storageMessage: String?,
    onOpen: (CleanVideoHistoryEntry) -> Unit,
    onDelete: (CleanVideoHistoryEntry) -> Unit,
    onClearAll: () -> Unit,
    onFreeUpSpace: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmFree by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clean Video history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete all results")
                        }
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StorageCard(
                    storage = storage,
                    message = storageMessage,
                    onFreeUpSpace = { confirmFree = true },
                )
            }

            if (entries.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No Clean Video results yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Finished cleanups appear here so you can reopen or save them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(entries, key = { it.id }) { entry ->
                HistoryCard(
                    entry = entry,
                    onOpen = { onOpen(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all results?") },
            text = {
                Text(
                    "This removes all ${entries.size} Clean Video results from Soundify. " +
                        "Projects and files you already saved are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearAll()
                    }
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep") }
            },
        )
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
private fun StorageCard(
    storage: StorageUsage?,
    message: String?,
    onFreeUpSpace: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            if (storage == null) {
                Text("Measuring…", style = MaterialTheme.typography.bodySmall)
            } else {
                StorageRow("Projects", storage.projectsBytes)
                StorageRow("Clean Video results", storage.cleanVideoBytes)
                StorageRow("Temporary files", storage.cacheBytes)
                StorageRow("App and model", storage.appBytes + storage.modelBytes)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Used by Soundify", fontWeight = FontWeight.SemiBold)
                    Text(formatBytes(storage.totalBytes), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onFreeUpSpace,
                enabled = storage != null &&
                    (storage.cleanVideoBytes + storage.cacheBytes) > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Free up space")
            }
            Text(
                "Removes temporary files and Clean Video results. Projects and saved files are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HistoryCard(
    entry: CleanVideoHistoryEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    "${formatDate(entry.createdAt)} · ${formatTime(entry.durationMs)} · " +
                        formatBytes(entry.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete result")
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
