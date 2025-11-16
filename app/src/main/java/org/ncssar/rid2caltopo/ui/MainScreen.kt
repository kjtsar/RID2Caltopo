package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.ncssar.rid2caltopo.data.CaltopoClient
import androidx.compose.runtime.collectAsState

// 1. Define a sealed interface to represent the different types of items in our list.
sealed interface MainScreenItem {
    data class LocalView(val viewModel: R2CViewModel) : MainScreenItem
    data class RemoteView(val viewModel: R2CRestViewModel) : MainScreenItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    remoteViewModels: List<R2CRestViewModel>,
    onShowHelp: () -> Unit,
    loadConfigFile: () -> Unit,
    onShowLog: () -> Unit,
    onShowSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // 2. Build the unified list of display items.
    val screenItems = buildList {
        add(MainScreenItem.LocalView(localViewModel))
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
                        DropdownMenuItem(text = { Text("Logging Level: " +
                                CaltopoClient.LoggingLevelName(CaltopoClient.DebugLevel)) }, onClick = {
                            CaltopoClient.BumpLoggingLevel()
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
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            items(
                items = screenItems,
                key = { item ->
                    // This key is now guaranteed to be unique and stable
                    when (item) {
                        is MainScreenItem.LocalView -> "local_view" // A constant key for the single local view
                        is MainScreenItem.RemoteView -> item.viewModel.r2cClient.peerName
                    }
                }
            ) { item ->
                // 4. Use a `when` statement to render the correct composable.
                when (item) {
                    is MainScreenItem.LocalView -> {
                        val localDrones by item.viewModel.drones.collectAsState()
                        val appUptime by item.viewModel.appUpTime.collectAsState()
                        val mapIsUp by item.viewModel.mapIsUp.collectAsState()
                        val mapId by item.viewModel.mapId.collectAsState()
                        val groupId by item.viewModel.groupId.collectAsState()
                        val hostname by item.viewModel.hostname.collectAsState()

                        R2CView(
                            hostName = hostname,
                            drones = localDrones,
                            appUptime = appUptime,
                            mapIsUp = mapIsUp,
                            mapId = mapId,
                            groupId = groupId,
                            onMappedIdChange = { drone, newId ->
                                item.viewModel.updateMappedId(drone, newId)
                            }
                        )
                    }

                    is MainScreenItem.RemoteView -> {
                        val remoteDrones by item.viewModel.drones.collectAsState()
                        val remoteUptime by item.viewModel.remoteUptime.collectAsState()
                        val remoteCtRttString by item.viewModel.remoteCtRtt.collectAsState()
                        val remoteAppVersion by item.viewModel.remoteAppVersion.collectAsState()

                        R2CRestView(
                            peerName = item.viewModel.r2cClient.peerName,
                            drones = remoteDrones,
                            remoteUptime = remoteUptime,
                            appVersion = remoteAppVersion,
                            mapId = item.viewModel.r2cClient.mapId,
                            groupId = item.viewModel.r2cClient.groupId,
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
