package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CaltopoSettingsScreen(
    onDismiss: () -> Unit,
    settingsViewModel: CaltopoSettingsViewModel = viewModel()
) {
    val groupId by settingsViewModel.groupId.collectAsState()
    val mapId by settingsViewModel.mapId.collectAsState()
    val minDistance by settingsViewModel.minDistance.collectAsState()
    val newTrackDelay by settingsViewModel.newTrackDelay.collectAsState()
    val useDirect by settingsViewModel.useDirect.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Caltopo Connection:")
                    Switch(
                        checked = useDirect,
                        onCheckedChange = { settingsViewModel.onUseDirectChanged(it) }
                    )
                    Text(if (useDirect) "Direct" else "Live")
                }

                OutlinedTextField(
                    value = groupId,
                    onValueChange = { settingsViewModel.onGroupIdChanged(it) },
                    label = { Text("Group ID") }
                )
                OutlinedTextField(
                    value = mapId,
                    onValueChange = { settingsViewModel.onMapIdChanged(it) },
                    label = { Text("Map ID") }
                )
                OutlinedTextField(
                    value = minDistance,
                    onValueChange = { settingsViewModel.onMinDistanceChanged(it) },
                    label = { Text("Min Dist (ft)") }
                )
                OutlinedTextField(
                    value = newTrackDelay,
                    onValueChange = { settingsViewModel.onNewTrackDelayChanged(it) },
                    label = { Text("New Track Delay (s)") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row {
                    Button(onClick = {
                        settingsViewModel.saveSettings()
                        onDismiss()
                    }) {
                        Text("Save")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
