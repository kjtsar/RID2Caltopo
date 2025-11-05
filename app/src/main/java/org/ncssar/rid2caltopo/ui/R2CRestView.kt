package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    drones : List<CtDroneSpec>,
    remoteUptime : String,
    ctRttString : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    Column {
        RestHeader(remoteUptime, appVersion, peerName, ctRttString)
        RestRidmapHeader()
        drones.forEach { drone ->
            DroneItem(drone = drone) { newMappedId ->
                onMappedIdChange(drone, newMappedId)
            }
        }
    }
}

@Composable
fun RestHeader(remoteUptime: String, appVersion: String, peerName: String, ctRttString: String) {
    Row(
        modifier = Modifier
            .background(Color.Red)
            .padding(2.dp),
    ) {
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = "Peer: " + peerName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = appVersion,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
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
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Text(
                text = remoteUptime,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
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
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Text(
                text = ctRttString,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
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
            .background(Color.Red)
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
                    .background(Color.White)
            )
            Text(
                text = "Track Label:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(250.dp)
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
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(480.dp)
        ) {
            Text(
                text = "Waypoints Received",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "BT4:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "R2C:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "Total:",
                    modifier = Modifier
                        .width(80.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
            }
        }
        Column(
            modifier = Modifier.width(150.dp)
        ) {
            Text(
                text = "Flight",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = "Duration:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.width(150.dp)
        ) {
            Text(
                text = "",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White)
            )
            Text(
                text = "R2C RTT:",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
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
        R2CRestView("", "", emptyList(), "", "0.000", {} as (CtDroneSpec, String) -> Unit)
    }
}
