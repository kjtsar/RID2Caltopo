/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CaltopoHybridBrowser
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

import java.util.Locale

@Composable
fun R2CView(
    hostName: String,
    viewModel: R2CViewModel?,
    drones : List<CtDroneSpec>,
    appUptime : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    val tag = "R2CView"
    Column {
        AppHeader(appUptime, hostName, viewModel)
        if (!drones.isEmpty()) {
            RidmapHeader()
            drones.forEach { drone ->
                key(drone.remoteId) {
                    val triggerCount = drone.totalCount
                    DroneItem(
                        drone = drone,
                        totalCount = drone.totalCount
                    ) { newMappedId ->
                        onMappedIdChange(drone, newMappedId)
                    }
                }
            }
        }
    }
}

@Composable
fun AppHeader(appUptime: String, hostName: String, viewModel: R2CViewModel?) {
    val textMod = Modifier.fillMaxWidth().padding(6.dp)
    val colModifier = Modifier
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.surface)
        .height(IntrinsicSize.Min)
    var showRidmapEntries by remember { mutableStateOf(false) }
    if (showRidmapEntries) {
        RidmapEntriesDialog(
            entries = CaltopoClient.GetRidmapEntriesSnapshot(),
            onDismiss = { showRidmapEntries = false }
        )
    }
    Row(
        modifier = Modifier
            .height(70.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(2.dp)
    ) {
        Column(modifier = colModifier) {
            if (null != viewModel) MapStateView(viewModel)
        }
        Column (modifier = colModifier) {
            Text(
                text = "rid_map entries:\n${CaltopoClient.GetRidmapCount()}",
                modifier = textMod.clickable { showRidmapEntries = true },
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            Text(
                text = "$hostName\n${R2CActivity.getMyAppVersion()}",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            Text(
                text = "Up Time:\n$appUptime",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            val rtt = String.format(
                Locale.US, "%.3f sec",
                CaltopoLiveTrack.GetCaltopoRttInMsec().toDouble() / 1000.0
            )
            Text(
                text = "Caltopo msg rtt:\n$rtt",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
        Column(modifier = colModifier) {
            val msgs = String.format(
                Locale.US, "%d", CtDroneSpec.GetInvalidWaypointCount()
            )
            Text(
                text = "Invalid RID msgs:\n$msgs",
                modifier = textMod,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RidmapEntriesDialog(
    entries: List<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Text("rid_map entries (${entries.size})")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (entries.isEmpty()) {
                    Text("No cached rid_map entries.")
                } else {
                    Text(
                        text = entries.joinToString("\n\n"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    )
}

@Composable
fun RidmapHeader() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(2.dp),
    ) {
        Column(
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }
        Column(
            modifier = Modifier.width(200.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
            Text(
                text = "Track Label:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(200.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
            Text(
                text = "Remote ID:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(640.dp)
        ) {
            Text(
                text = "Waypoints Received",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "BT4:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(40.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(40.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(40.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Box(modifier = Modifier.width(40.dp).height(25.dp).background(MaterialTheme.colorScheme.surface))
                Text(
                    text = "R2C:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Text(
                    text = "Total:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
            }
        }
        Column(
            modifier = Modifier.width(125.dp)
        ) {
            Text(
                text = "Flight",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Right,
                fontSize = 18.sp
            )
            Text(
                text = "Duration:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Right,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(125.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
            Text(
                text = "R2C RTT:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Right,
                fontSize = 18.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun R2CViewPreview() {
    RID2CaltopoTheme {
        R2CView(
            "",
            null,
            emptyList(),
            "",
            { _, _ -> }
        )
    }
}

@Composable
fun DroneSpecConfirmationDialog(
    state: DroneSpecConfirmationUiState,
    onFieldChange: (organization: String?, pilotCallsign: String?, droneDescription: String?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val organization = state.organization.trim()
    val pilotCallsign = state.pilotCallsign.trim()
    val droneDescription = state.droneDescription.trim()
    val saveEnabled = organization.isNotEmpty() && pilotCallsign.isNotEmpty() && droneDescription.isNotEmpty()

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Confirm Drone") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!state.warning.isNullOrBlank()) {
                    Text(
                        text = state.warning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedTextField(
                    value = state.remoteId,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Remote ID") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.organization,
                    onValueChange = { onFieldChange(it, null, null) },
                    label = { Text("Organization") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.pilotCallsign,
                    onValueChange = { onFieldChange(null, it, null) },
                    label = { Text("Pilot Callsign") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.droneDescription,
                    onValueChange = { onFieldChange(null, null, it) },
                    label = { Text("Drone Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = saveEnabled
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not recognized")
            }
        },
    )
}

@Composable
fun MapStateView(viewModel: R2CViewModel) {
    // 1. Observe the two sources of truth
    val connection = viewModel.connectionState
    val overlay = viewModel.overlay
    val pendingProfileSwitch = viewModel.pendingProfileSwitch

    Box (
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(6.dp)
    ) {
        // The Header only needs to know the Connection state to draw its icon/label
        CaltopoActionInterface(
            state = connection,
            onActionClicked = { viewModel.onUIEvent(UIEvent.HeaderClicked) }
        )

        // 2. The "Marching Orders": Render EXACTLY one overlay based on state
        when (val currentOverlay = overlay) {
            is OverlayState.ConnectionSetup -> {
                StandAloneOptionsDialog(
                    hasCreds = viewModel.hasCredentials,
                    hasNetwork = viewModel.hasNetwork,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = false,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }
            is OverlayState.RequestConfigFile -> {
                StandAloneOptionsDialog(
                    hasCreds = viewModel.hasCredentials,
                    hasNetwork = viewModel.hasNetwork,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = true,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }
            is OverlayState.Connecting -> {
                StandAloneOptionsDialog(
                    hasCreds = viewModel.hasCredentials,
                    hasNetwork = viewModel.hasNetwork,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = true,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }

            is OverlayState.MapBrowser -> {
                val nodes = viewModel.mapHierarchy?: emptyList()
                // The browser receives the data it needs and bubbles events back up
                Dialog(
                    onDismissRequest = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    // Now the browser has its own window and won't be "squished" by the header
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        key(nodes) {
                            CaltopoHybridBrowser(
                                rootNodes = nodes,
                                profileOptions = viewModel.mapBrowserProfiles,
                                selectedProfileId = viewModel.selectedMapBrowserProfileId,
                                onUIEvent = { viewModel.onUIEvent(it) }
                            )
                        }
                    }
                }
            }

            is OverlayState.Management -> {
                // Type safety: Management only makes sense if a map is selected
                (connection as? CaltopoConnectionState.MapSelected)?.let { state ->
                    ConnectedOptionsDialog(
                        mapName = state.map.title,
                        onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                        onSwitchMap = { viewModel.onUIEvent(UIEvent.SwitchMapRequested) },
                        onDisconnect = { viewModel.onUIEvent(UIEvent.DisconnectRequested) }
                    )
                }
            }

            is OverlayState.Error -> {
                ErrorDialog(
                    message = overlay.message,
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) }
                )
            }

            OverlayState.None -> { /* Render nothing over the map */ }
        }
    }

    pendingProfileSwitch?.let { pending ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissPendingProfileSwitch() },
            title = { Text("Switch Browse Profile?") },
            text = {
                Text(
                    "Disconnect from the current map and stop arbitration for " +
                            "${pending.activeFlightCount} active flight(s) before browsing as " +
                            "${pending.label}?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingProfileSwitch() }) {
                    Text("Disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPendingProfileSwitch() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(text = "Connection Error")
        },
        text = {
            Text(text = message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandAloneOptionsDialog(
    onDismiss: () -> Unit,
    loading: Boolean,
    hasNetwork: Boolean,
    hasCreds: Boolean,
    onAction: () -> Unit
) {
    val titleText = if (!hasNetwork) "No Network Connection" else if (loading) "Connect to Map" else "Credentials Required"
    val msgText = if (!hasNetwork) {
        "Turn on your device's WiFi and connect to hotspot before continuing"
    } else if (hasCreds) {
        "Existing credentials found. Would you like to select a map?"
    } else {
        "No CalTopo credentials found. You need to load Team acct credentials first."
    }
    AlertDialog(
        onDismissRequest = if (loading) ({}) else onDismiss, // disable dismiss while loading
        title = { Text(titleText) },
        text = {
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Fetching Map Hierarchy...")
                }
            } else {
                Text(msgText)
            }
        },
        confirmButton = {
            Button(
                onClick = onAction,
                enabled = !loading && hasNetwork
            ) {
                Text(if (hasCreds) "Connect" else "Load Credential File")

            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay Offline") }
        }
    )
}
