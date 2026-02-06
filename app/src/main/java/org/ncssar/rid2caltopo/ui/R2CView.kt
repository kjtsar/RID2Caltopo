/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

import java.util.Locale

@Composable
fun R2CView(
    hostName: String,
    viewModel: R2CViewModel?,
    mapStatus: CaltopoMap.MapStatusListener.mapStatus,
    drones : List<CtDroneSpec>,
    appUptime : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    Column {
        AppHeader(appUptime, hostName, viewModel, mapStatus)
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
fun AppHeader(appUptime: String, hostName: String, viewModel: R2CViewModel?, mapStatus: CaltopoMap.MapStatusListener.mapStatus) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(3.dp)
    ) {
        if (mapStatus != CaltopoMap.MapStatusListener.mapStatus.down) {
            Column(
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (mapStatus == CaltopoMap.MapStatusListener.mapStatus.up) {
                    Image(
                        painter = painterResource(id = R.drawable.earth),
                        contentDescription = "earth",
                        modifier = Modifier.size(30.dp)
                    )
                } else if (mapStatus == CaltopoMap.MapStatusListener.mapStatus.connecting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.secondary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = hostName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = R2CActivity.getMyAppVersion(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(120.dp)
        ) {
            Text(
                text = "Up Time:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Text(
                text = appUptime,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            if (null != viewModel) MapStateView(viewModel)
        }
        Column(
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "ct rtt",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Text(
                text = String.format(
                    Locale.US, "%.3f sec",
                    CaltopoLiveTrack.GetCaltopoRttInMsec().toDouble() / 1000.0
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(150.dp)
        ) {
            Text(
                text = "bad RID msgs",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Text(
                text = String.format(
                    Locale.US, "%d", CtDroneSpec.GetInvalidWaypointCount()
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
    }
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
            modifier = Modifier.width(560.dp)
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
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Right,
                    fontSize = 18.sp
                )
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
                    text = "Good:",
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
        R2CView("",  null, CaltopoMap.MapStatusListener.mapStatus.down, emptyList(), "", {} as (CtDroneSpec, String) -> Unit)
    }
}

@Composable
fun MapStateView(viewModel: R2CViewModel) {
    // 1. Observe the two sources of truth
    val connection = viewModel.connectionState
    val overlay = viewModel.overlay

    Box (modifier = Modifier.fillMaxSize()) {
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
                    onDismiss = { viewModel.onUIEvent(UIEvent.DismissRequested) },
                    loading = false,
                    onAction = { viewModel.onUIEvent(UIEvent.ConnectionRequested) }
                )
            }
            is OverlayState.Connecting -> {
                StandAloneOptionsDialog(
                    hasCreds = viewModel.hasCredentials,
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

@Composable
fun StandAloneOptionsDialog(
    onDismiss: () -> Unit,
    loading: Boolean,
    hasCreds: Boolean,
    onAction: () -> Unit
) {
    AlertDialog(
        onDismissRequest = if (loading) ({}) else onDismiss, // disable dismiss while loading
        title = { Text(if (hasCreds) "Connect to Map" else "Credentials Required") },
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
                Text(
                    if (hasCreds)
                        "Existing credentials found. Would you like to sync with CalTopo?"
                    else "No CalTopo credentials found. You need to load a credential file to sync maps."
                )
            }
        },
        confirmButton = {
            Button(onClick = onAction) {
                Text(if (hasCreds) "Connect" else "Load Credential File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay Offline") }
        }
    )
}
