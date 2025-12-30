/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
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
    val maxIdleTimeInMinutes by settingsViewModel.maxIdleTimeInMinutes.collectAsState()
    val useDirect by settingsViewModel.useDirect.collectAsState()
    val usePeers by settingsViewModel.usePeers.collectAsState()
    val incident by settingsViewModel.incident.collectAsState()
    val opPeriod by settingsViewModel.opPeriod.collectAsState()

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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use Peers:")
                    Switch(
                        checked = usePeers,
                        onCheckedChange = { settingsViewModel.onUsePeersChanged(it) }
                    )
                    Text(if (usePeers) "Yes" else "No")
                }

                OutlinedTextField(
                    value = incident,
                    onValueChange = { settingsViewModel.onIncidentChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Incident") }
                )
                OutlinedTextField(
                    value = opPeriod,
                    onValueChange = { settingsViewModel.onOpPeriodChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Op Period") }
                )
                OutlinedTextField(
                    value = mapId,
                    onValueChange = { settingsViewModel.onMapIdChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Map ID") }
                )
                OutlinedTextField(
                    value = groupId,
                    onValueChange = { settingsViewModel.onGroupIdChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Group ID") }
                )
                OutlinedTextField(
                    value = minDistance,
                    onValueChange = { settingsViewModel.onMinDistanceChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Min Dist (ft)") }
                )
                OutlinedTextField(
                    value = newTrackDelay,
                    onValueChange = { settingsViewModel.onNewTrackDelayChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("New Track Delay (s)") }
                )
                OutlinedTextField(
                    value = maxIdleTimeInMinutes,
                    onValueChange = { settingsViewModel.onMaxIdleTimeInMinutesChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Max App Idle Time (minutes)") }
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
