package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.app.R2CActivity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    localViewModel: R2CViewModel,
    remoteViewModels: List<R2CRestViewModel>,
    onShowHelp: () -> Unit,
    onShowLog: () -> Unit,
    onShowVersion: () -> Unit,
    onShowSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RID2Caltopo") },
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
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .horizontalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // The Local R2CView
                val localDrones by localViewModel.drones.collectAsState()
                val appUptime by localViewModel.appUpTime.collectAsState()

                R2CView(
                    hostName = R2CActivity.MyDeviceName,
                    drones = localDrones,
                    appUptime = appUptime,
                    onMappedIdChange = { drone, newId ->
                        localViewModel.updateMappedId(drone, newId)
                    }
                )
                // The Remote R2CViews
                remoteViewModels.forEach { remoteViewModel ->
                    val remoteDrones by remoteViewModel.drones.collectAsState()
                    val remoteUptime by remoteViewModel.remoteUptime.collectAsState()
                    val remoteCtRttString by remoteViewModel.remoteCtRtt.collectAsState()
                    Box(modifier = Modifier.fillMaxWidth().height(10.dp).background(Color.LightGray))
                    R2CRestView(
                        peerName = remoteViewModel.r2cClient.peerName,
                        drones = remoteDrones,
                        remoteUptime = remoteUptime,
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
}
