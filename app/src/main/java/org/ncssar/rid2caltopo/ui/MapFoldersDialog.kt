/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One folder fetched from the Caltopo map, with its contained items. */
data class MapFolderUiState(
    val folderId: String,
    val title: String,
    /** Reflects the Caltopo folder `visible` property at the time it was last received. */
    val initiallyVisible: Boolean,
    val items: List<MapItemUiState>
)

/** One non-folder feature (marker, shape, track, etc.) belonging to a map folder. */
data class MapItemUiState(
    val featureId: String,
    val title: String
)

/**
 * Dialog that lists all Caltopo map folders and their contained items, each with a
 * visibility checkbox.  Visibility state is maintained by the caller; this composable
 * is stateless.
 *
 * - Folder checkbox unchecked → all items in that folder are hidden on the map and the
 *   item list is collapsed.
 * - Folder checkbox checked → items are listed; each item has its own checkbox.
 * - Clicking the folder title (while expanded) → toggles all items: if all are visible,
 *   hides all; if any are hidden, shows all.  Folder stays expanded.
 */
@Composable
fun MapFoldersDialog(
    folders: List<MapFolderUiState>,
    hiddenFolderIds: Set<String>,
    hiddenItemIds: Set<String>,
    onFolderVisibilityChanged: (folderId: String, visible: Boolean) -> Unit,
    onItemVisibilityChanged: (itemId: String, visible: Boolean) -> Unit,
    onAllItemsToggled: (itemIds: List<String>, visible: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map Folders") },
        text = {
            if (folders.isEmpty()) {
                Text("No map folders found.")
            } else {
                LazyColumn {
                    items(folders) { folder ->
                        val folderVisible = folder.folderId !in hiddenFolderIds
                        val allItemsVisible = folder.items.none { it.featureId in hiddenItemIds }
                        // Folder row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = folderVisible,
                                onCheckedChange = { checked ->
                                    onFolderVisibilityChanged(folder.folderId, checked)
                                }
                            )
                            Text(
                                text = folder.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = if (folderVisible && folder.items.isNotEmpty()) {
                                    Modifier.clickable {
                                        // All visible → hide all; any hidden → show all
                                        onAllItemsToggled(
                                            folder.items.map { it.featureId },
                                            !allItemsVisible
                                        )
                                    }
                                } else Modifier
                            )
                        }
                        // Item rows — only shown when folder is visible
                        if (folderVisible) {
                            Column(modifier = Modifier.padding(start = 24.dp)) {
                                folder.items.forEach { item ->
                                    val itemVisible = item.featureId !in hiddenItemIds
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Checkbox(
                                            checked = itemVisible,
                                            onCheckedChange = { checked ->
                                                onItemVisibilityChanged(item.featureId, checked)
                                            }
                                        )
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                if (folder.items.isEmpty()) {
                                    Text(
                                        text = "(no items)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
