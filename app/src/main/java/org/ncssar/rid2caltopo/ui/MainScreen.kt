/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.video.StreamsScreen

// 1. Define a sealed interface to represent the different types of items in our list.
sealed interface MainScreenItem {
    object IncidentView : MainScreenItem
    data class LocalView(val viewModel: R2CViewModel) : MainScreenItem
    data class RemoteView(val viewModel: R2CPeerViewModel) : MainScreenItem
    data class SpacerView(val height: Dp) : MainScreenItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    remoteViewModels: List<R2CPeerViewModel>,
    onShowLog: () -> Unit,
    onShowHelp: () -> Unit
) {
    val TAG: String = "MainScreen"
    var menuExpanded by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showConfirmExitDialog by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)) }

    if (showConfirmExitDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmExitDialog = false },
            title = { Text("Confirm Exit") },
            text = { Text("Do you really want to close this application?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmExitDialog = false
                        R2CActivity.getR2CActivity()?.finish()
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmExitDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Ludicrous Debugging") },
            text = { Text("This will generate a large amount of debugging information. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        // Reset to base level
                        CaltopoClient.SetLoggingLevel(CaltopoClient.DebugLevelError)
                        level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    val context = LocalContext.current

    // Launcher for loading a config file
    val loadConfigFileLauncher = rememberLauncherForActivityResult(
        contract = CaltopoClient.OpenOpenableDocument(),
        onResult = { uri ->
            uri?.let { CaltopoClient.LoadConfigFile(it) }
        }
    )

    // Launcher for selecting an archive directory
    val queryArchiveDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    CaltopoClient.SetArchivePath(uri.toString())
                }
            }
        }
    )
    LaunchedEffect(Unit) {
        if (CaltopoClient.GetArchivePath().isEmpty()) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(Intent.EXTRA_TITLE, "Select directory to archive drone tracks")
            }
            queryArchiveDirLauncher.launch(intent)
        }
    }
    
    // 2. Build the unified list of display items.
    val screenItems = buildList {
        add(MainScreenItem.IncidentView)
        add(MainScreenItem.SpacerView(12.dp))
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
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            localViewModel.showSettings()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Load Config File") }, onClick = {
                            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Show Log") }, onClick = {
                            onShowLog()
                            CaltopoClient.CTEvent(TAG,"LogDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = {
                            Text("LogLevel:${level}") }, onClick = {
                            CaltopoClient.BumpLoggingLevel()
                            level = CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)
                            if (CaltopoClient.DebugLevel == CaltopoClient.DebugLevelInfo) {
                                showConfirmDialog = true;
                            }
                            menuExpanded = true
                        })
                        DropdownMenuItem(text = { Text("Stream Service")}, onClick = {
                            localViewModel.showStreams()
                            CaltopoClient.CTEvent(TAG,"Stream Service Activated", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Status")}, onClick = {
                            localViewModel.showScanner()
                            CaltopoClient.CTEvent(TAG,"ScannersDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Help") }, onClick = {
                            onShowHelp()
                            CaltopoClient.CTEvent(TAG,"HelpDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Quit") }, onClick = {
                            showConfirmExitDialog = true
                            CaltopoClient.CTEvent(TAG,"Quit", null)
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
                            is MainScreenItem.IncidentView -> "incident_view"
                        }
                    }
                ) { item ->
                    // 4. Use a `when` statement to render the correct composable.
                    when (item) {
                        is MainScreenItem.IncidentView -> {
                             IncidentView()
                        }
                        is MainScreenItem.LocalView -> {
                            val localDrones by item.viewModel.drones.collectAsState()
                            val appUptime by item.viewModel.appUpTime.collectAsState()
                            val mapStatus by item.viewModel.mapStatus.collectAsState()
                            val hostname by item.viewModel.hostname.collectAsState()

                            R2CView(
                                hostName = hostname,
                                drones = localDrones,
                                appUptime = appUptime,
                                viewModel = item.viewModel,
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

                            R2CPeerView(
                                peerName = item.viewModel.r2cPeer.peerName,
                                drones = remoteDrones,
                                remoteUptime = remoteUptime,
                                appVersion = remoteAppVersion,
                                viewModel = item.viewModel,
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
