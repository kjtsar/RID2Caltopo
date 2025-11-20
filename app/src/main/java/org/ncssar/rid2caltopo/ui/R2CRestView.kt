package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme

@Composable
fun R2CRestView(
    peerName: String,
    appVersion: String,
    mapId: String,
    groupId: String,
    drones : List<CtDroneSpec>,
    remoteUptime : String,
    ctRttString : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    Column {
        RestHeader(remoteUptime, appVersion, peerName, mapId, groupId, ctRttString)
        if (!drones.isEmpty()) {
            RestRidmapHeader()
            drones.forEach { drone ->
                key(drone.remoteId, drone.mappedId) {
                    DroneItem(drone = drone) { newMappedId ->
                        onMappedIdChange(drone, newMappedId)
                    }
                }
            }
        }
    }
}

@Composable
fun RestHeader(appUptime: String, appVersion: String, peerName: String, mapId: String, groupId: String,ctRttString: String) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(3.dp),
    ) {
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = "Peer: "+ peerName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = appVersion,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(MaterialTheme.colorScheme.surface),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        Column(
            modifier = Modifier.width(200.dp)
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
            modifier = Modifier.width(200.dp)
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
            modifier = Modifier.width(200.dp)
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
                text = ctRttString,
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
fun RestRidmapHeader() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(2.dp),
    ) {
        Column(
            modifier = Modifier.width(250.dp)
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
fun R2CRestViewPreview() {
    RID2CaltopoTheme {
        R2CRestView("", "", "", "",emptyList(), "", "", {} as (CtDroneSpec, String) -> Unit)
    }
}
