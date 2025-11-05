package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.ncssar.rid2caltopo.app.R2CActivity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    remoteViewModels: List<R2CRestViewModel>,
    onShowHelp: () -> Unit,
    onShowLog: () -> Unit,
    onLoadConfigFile: () -> Unit,
    onShowVersion: () -> Unit,
    onShowSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

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
                            onLoadConfigFile()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Show Log") }, onClick = {
                            onShowLog()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Help") }, onClick = {
                            onShowHelp()
                            menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text("Version") }, onClick = {
                            onShowVersion()
                            menuExpanded = false
                        })
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            // Item 1: The Local R2CView
            item {
                val localDrones by localViewModel.drones.collectAsState()
                val appUptime by localViewModel.appUpTime.collectAsState()

                R2CView(
                    hostName = R2CActivity.MyDeviceName ?: "<local>",
                    drones = localDrones,
                    appUptime = appUptime,
                    onMappedIdChange = { drone, newId ->
                        localViewModel.updateMappedId(drone, newId)
                    }
                )
            }

            // Items 2...N: The Remote R2CViews
            items(remoteViewModels) { remoteViewModel ->
                val remoteDrones by remoteViewModel.drones.collectAsState()
                val remoteUptime by remoteViewModel.remoteUptime.collectAsState()
                val remoteCtRttString by remoteViewModel.remoteCtRtt.collectAsState()
                R2CRestView(
                    peerName = remoteViewModel.r2cClient.peerName,
                    drones = remoteDrones,
                    appUptime = remoteUptime,
                    appVersion = remoteViewModel.r2cClient.remoteAppVersion,
                    ctRttString = remoteCtRttString,
                    onMappedIdChange = { drone, newId ->
                        remoteViewModel.updateMappedId(drone, newId)
                    }
                )
            }
        }
    }
}
