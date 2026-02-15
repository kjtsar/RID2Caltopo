/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    val minDistance by settingsViewModel.minDistance.collectAsState()
    val newTrackDelay by settingsViewModel.newTrackDelay.collectAsState()
    val maxIdleTimeInMinutes by settingsViewModel.maxIdleTimeInMinutes.collectAsState()
    val goLiveFlag by settingsViewModel.goLiveFlag.collectAsState()
    val usePeers by settingsViewModel.usePeers.collectAsState()
    val caltopoUrl by settingsViewModel.caltopoUrl.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Card (modifier = Modifier.verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
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
                OutlinedTextField(
                    value = caltopoUrl,
                    onValueChange = { settingsViewModel.onCaltopoDomainAndPortChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Caltopo Domain And Port (i.e. caltopo.com)") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Updates:")
                    Switch(
                        checked = goLiveFlag,
                        onCheckedChange = { settingsViewModel.onSendLiveChanged(it) }
                    )
                    Text(if (goLiveFlag) "Yes" else "No")
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use Peers:")
                    Switch(
                        checked = usePeers,
                        onCheckedChange = { settingsViewModel.onUsePeersChanged(it) }
                    )
                    Text(if (usePeers) "Yes" else "No")
                }


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
