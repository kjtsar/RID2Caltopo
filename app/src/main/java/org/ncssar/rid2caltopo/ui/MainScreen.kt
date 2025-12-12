/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.ncssar.rid2caltopo.data.CaltopoClient
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 1. Define a sealed interface to represent the different types of items in our list.
sealed interface MainScreenItem {
    data class LocalView(val viewModel: R2CViewModel) : MainScreenItem
    data class RemoteView(val viewModel: R2CPeerViewModel) : MainScreenItem
    data class SpacerView(val height: Dp) : MainScreenItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    remoteViewModels: List<R2CPeerViewModel>,
    onShowHelp: () -> Unit,
    onShowScanners: () -> Unit,
    loadConfigFile: () -> Unit,
    onShowLog: () -> Unit,
    onShowSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // 2. Build the unified list of display items.
    val screenItems = buildList {
        add(MainScreenItem.LocalView(localViewModel))
        if (remoteViewModels.isNotEmpty()) add(MainScreenItem.SpacerView(52.dp))
        remoteViewModels.forEach {
            add(MainScreenItem.RemoteView(it))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RID-2-Caltopo") },
                actions = {
                    var level by remember {mutableStateOf(CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel))}
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            onShowSettings()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Load Config File") }, onClick = {
                            loadConfigFile()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Show Log") }, onClick = {
                            onShowLog()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = {
                            Text("LogLevel:${level}") }, onClick = {
                            CaltopoClient.BumpLoggingLevel()
                            level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                            menuExpanded = true
                        })
                        DropdownMenuItem(text = { Text("Scanners")}, onClick = {
                            onShowScanners()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Help") }, onClick = {
                            onShowHelp()
                            menuExpanded = false
                        })
                    }
                }
            )
        }
    ) { paddingValues ->
        // 3. Use a single LazyColumn with the robust `items` DSL and a stable key.
        Box(
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            LazyColumn(modifier = Modifier.padding(paddingValues)) {
                items(
                    items = screenItems,
                    key = { item ->
                        // This key is now guaranteed to be unique and stable
                        when (item) {
                            is MainScreenItem.LocalView -> "local_view" // A constant key for the single local view
                            is MainScreenItem.RemoteView -> item.viewModel.r2cPeer.peerName
                            is MainScreenItem.SpacerView -> "spacer_view"
                        }
                    }
                ) { item ->
                    // 4. Use a `when` statement to render the correct composable.
                    when (item) {
                        is MainScreenItem.LocalView -> {
                            val localDrones by item.viewModel.drones.collectAsState()
                            val appUptime by item.viewModel.appUpTime.collectAsState()
                            val mapStatus by item.viewModel.mapStatus.collectAsState()
                            val mapId by item.viewModel.mapId.collectAsState()
                            val groupId by item.viewModel.groupId.collectAsState()
                            val hostname by item.viewModel.hostname.collectAsState()

                            R2CView(
                                hostName = hostname,
                                drones = localDrones,
                                appUptime = appUptime,
                                mapStatus = mapStatus,
                                mapId = mapId,
                                groupId = groupId,
                                onMappedIdChange = { drone, newId ->
                                    item.viewModel.updateMappedId(drone, newId)
                                }
                            )
                        }
                        is MainScreenItem.SpacerView -> {
                            HorizontalDivider(thickness = item.height)
                        }
                        is MainScreenItem.RemoteView -> {
                            val remoteDrones by item.viewModel.drones.collectAsState()
                            val remoteUptime by item.viewModel.remoteUptime.collectAsState()
                            val remoteCtRttString by item.viewModel.remoteCtRtt.collectAsState()
                            val remoteAppVersion by item.viewModel.remoteAppVersion.collectAsState()
                            val remoteMapId by item.viewModel.remoteMapId.collectAsState()
                            val remoteGroupId by item.viewModel.remoteGroupId.collectAsState()

                            R2CPeerView(
                                peerName = item.viewModel.r2cPeer.peerName,
                                drones = remoteDrones,
                                remoteUptime = remoteUptime,
                                appVersion = remoteAppVersion,
                                mapId = remoteMapId,
                                groupId = remoteGroupId,
                                ctRttString = remoteCtRttString,
                                onMappedIdChange = { drone, newId ->
                                    item.viewModel.updateMappedId(drone, newId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
