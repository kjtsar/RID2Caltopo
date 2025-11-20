package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import org.ncssar.rid2caltopo.data.CaltopoClientMap
import java.util.Locale

@Composable
fun R2CView(
    hostName: String,
    mapId: String,
    groupId: String,
    mapStatus: CaltopoClientMap.MapStatusListener.mapStatus,
    drones : List<CtDroneSpec>,
    appUptime : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    Column {
        AppHeader(appUptime, hostName, mapId, groupId, mapStatus)
        if (!drones.isEmpty()) {
            RidmapHeader()
            drones.forEach { drone ->
                key(drone.remoteId) {
                    DroneItem(drone = drone) { newMappedId ->
                        onMappedIdChange(drone, newMappedId)
                    }
                }
            }
        }
    }
}

@Composable
fun AppHeader(appUptime: String, hostName: String, mapId: String, groupId: String, mapStatus: CaltopoClientMap.MapStatusListener.mapStatus) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(3.dp)
            .height(IntrinsicSize.Min)
    ) {
        if (mapStatus != CaltopoClientMap.MapStatusListener.mapStatus.down) {
            Column(
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (mapStatus == CaltopoClientMap.MapStatusListener.mapStatus.up) {
                    Image(
                        painter = painterResource(id = R.drawable.earth),
                        contentDescription = "earth",
                        modifier = Modifier.size(30.dp)
                    )
                } else if (mapStatus == CaltopoClientMap.MapStatusListener.mapStatus.connecting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.secondary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        Column(
            modifier = Modifier.width(200.dp)
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
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "Map Id:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Text(
                text = mapId,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(100.dp)
        ) {
            Text(
                text = "Group Id:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Text(
                text = groupId,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
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
            modifier = Modifier.width(420.dp)
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
                        .width(70.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(70.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(70.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(70.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "R2C:",
                    modifier = Modifier
                        .width(70.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "Total:",
                    modifier = Modifier
                        .width(70.dp)
                        .height(25.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
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
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = "Duration:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(100.dp)
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
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun R2CViewPreview() {
    RID2CaltopoTheme {
        R2CView("", "", "", CaltopoClientMap.MapStatusListener.mapStatus.down, emptyList(), "", {} as (CtDroneSpec, String) -> Unit)
    }
}
