/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError

// 1. Define a sealed interface to represent the different types of items in our list.
sealed interface MainScreenItem {
    object IncidentView : MainScreenItem
    data class LocalView(val viewModel: R2CViewModel) : MainScreenItem
    data class RemoteView(val viewModel: R2CPeerViewModel) : MainScreenItem
    data class SpacerView(val height: Dp) : MainScreenItem
}

// Try to bust thru Google Drive's cache to get the latest version of requested
// document.  N.B. If battery saver is on, Google Drive will always use the
// cached version regardless.
class FreshOpenDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return super.createIntent(context, input).apply {
            // Tells providers (like Drive) to check the server
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra("android.content.extra.NO_CACHE", true)
            // Ensures we aren't restricted to files already on the device
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
        }
    }
}

class OpenArchiveDir() : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input).apply {
            val downloadsUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Downloads"
            )
            putExtra(Intent.EXTRA_TITLE, "Select directory to archive drone tracks")
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
            putExtra("android.content.extra.NO_CACHE", true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    remoteViewModels: List<R2CPeerViewModel>,
    onShowLog: () -> Unit,
    onShowHelp: () -> Unit
) {
    val tag: String = "MainScreen"
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
    val context =  LocalContext.current
    var isPickerOpen by remember { mutableStateOf(false) }
    // Launcher for loading a config file
    val loadConfigFileLauncher = rememberLauncherForActivityResult(
        contract = FreshOpenDocument(),
        onResult = { uri ->
            isPickerOpen = false
            if (uri != null) {
                CTDebug(tag, "loadConfigFileLauncher() returned '${uri}'")
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(
                        uri, takeFlags
                    )
                    CTDebug(tag, "loadConfigFileLauncher() secured URI read permission.")
                    if (CaltopoClient.LoadConfigFile(uri)) {
                        localViewModel.onUIEvent((UIEvent.ConfigFileLoaded))
                    } else {
                        localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
                    }
                } catch (e: Exception) {
                    CTError(tag, "loadConfigFileLauncher(): read failed: ", e)
                }
            } else {
                CTDebug(tag, "loadConfigFileLauncher() picker closed w/o selection.")
                localViewModel.onUIEvent(UIEvent.NotAbleToReadConfigFile)
            }
        }
    )

    LaunchedEffect(localViewModel.overlay) {
        if (localViewModel.overlay == OverlayState.RequestConfigFile && !isPickerOpen) {
            CTDebug(tag, "LaunchedEffect(): requesting config file...")
            isPickerOpen = true
            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }


    // Launcher for selecting an archive directory
    val queryArchiveDirLauncher = rememberLauncherForActivityResult(
        contract = OpenArchiveDir(),
        onResult = { uri ->
            if (null != uri) {
                CTDebug(tag, "queryArchiveDirLauncher() returned: '${uri}'")
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                CaltopoClient.SetArchiveUri(uri)
            }
        }
    )
    LaunchedEffect(Unit) {
        if (null == CaltopoClient.GetArchiveUri()) {
            CTDebug(tag, "LaunchedEffect() requesting archiveDir")
            queryArchiveDirLauncher.launch(null)
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
                        DropdownMenuItem(text = { Text("Load config file") }, onClick = {
                            loadConfigFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Show Log") }, onClick = {
                            onShowLog()
                            CaltopoClient.CTEvent(tag,"LogDisplayed", null)
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
                            CaltopoClient.CTEvent(tag,"Stream Service Activated", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Status")}, onClick = {
                            localViewModel.showScanner()
                            CaltopoClient.CTEvent(tag,"ScannersDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Help") }, onClick = {
                            onShowHelp()
                            CaltopoClient.CTEvent(tag,"HelpDisplayed", null)
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Quit") }, onClick = {
                            showConfirmExitDialog = true
                            CaltopoClient.CTEvent(tag,"Quit", null)
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
