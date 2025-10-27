package org.ncssar.rid2caltopo.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme

private var rootView: View? = null
fun getRootView(): View? {
    return rootView
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun R2CView(
    onShowHelp: () -> Unit,
    onShowLog: () -> Unit,
    onLoadConfigFile: () -> Unit,
    onShowVersion: () -> Unit,
    onShowSettings: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    rootView = LocalView.current

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
        Column(modifier = Modifier.padding(paddingValues)) {
            AppHeader()
            RidmapHeader()
            LazyColumn {
                // TODO: Add your list items here
            }
        }
    }
}

@Composable
fun AppHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = "MyR2CDeviceName",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = "app v1.0.0-24Oct2025(HHMMSS)",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Color.White)
            )
            Text(
                text = "Up Time:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(29.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Text(
                text = "HH:MM:SS",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
        }
        Column(
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Color.White)
            )
            Text(
                text = "ct rtt",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(29.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Text(
                text = "0.000 sec",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White),
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
            .fillMaxWidth()
            .background(Color.Black)
            .padding(2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.width(200.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White)
            )
            Text(
                text = "Remote ID:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
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
                    .background(Color.White)
            )
            Text(
                text = "Track Label:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = "Waypoints Received",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Color.White)
                    .padding(bottom = 1.dp),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "BT4:",
                    modifier = Modifier
                        .width(60.dp)
                        .height(23.dp)
                        .background(Color.White)
                        .padding(end = 1.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(60.dp)
                        .height(23.dp)
                        .background(Color.White)
                        .padding(end = 1.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(60.dp)
                        .height(23.dp)
                        .background(Color.White)
                        .padding(end = 1.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(60.dp)
                        .height(23.dp)
                        .background(Color.White)
                        .padding(end = 1.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
                Text(
                    text = "Total:",
                    modifier = Modifier
                        .width(60.dp)
                        .height(23.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            }
        }
        Column(
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "Flight",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Text(
                text = "Duration:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White)
                    .padding(top = 1.dp),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White)
            )
            Text(
                text = "r2c rtt:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(23.dp)
                    .background(Color.White)
                    .padding(top = 1.dp),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
    }

}

@Preview(showBackground = true)
@Composable
fun R2CViewPreview() {
    RID2CaltopoTheme {
        R2CView({}, {}, {}, {}, {})
    }
}


