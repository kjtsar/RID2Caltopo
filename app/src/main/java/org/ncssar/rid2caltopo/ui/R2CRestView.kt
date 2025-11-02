package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2CRest
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme

@Composable
fun R2CRestView(
    peerName: String,
    appVersion: String,
    drones : List<CtDroneSpec>,
    appUptime : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    RestHeader(appUptime, appVersion, peerName)
    RestRidmapHeader()
    drones.forEach { drone ->
        DroneItem(drone = drone) { newMappedId ->
            onMappedIdChange(drone, newMappedId)
        }
    }
}

@Composable
fun RestHeader(appUptime: String, appVersion: String, peerName: String) {
    Row(
        modifier = Modifier
            .background(Color.Black)
            .padding(2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = peerName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
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
                    .height(30.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
            Text(
                text = appUptime,
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
                    .height(30.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
            Text(
                text = "0.000 sec",
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
            .background(Color.Black)
            .padding(2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
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
            modifier = Modifier.width(500.dp)
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "BT4:",
                    modifier = Modifier
                        .width(100.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "BT5:",
                    modifier = Modifier
                        .width(100.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "WiFi:",
                    modifier = Modifier
                        .width(100.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "NaN:",
                    modifier = Modifier
                        .width(100.dp)
                        .height(25.dp)
                        .background(Color.White),
                    textAlign = TextAlign.Center,
                    fontSize = 18.sp
                )
                Text(
                    text = "Total:",
                    modifier = Modifier
                        .width(100.dp)
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
        R2CRestView("", "", emptyList(), "", {} as (CtDroneSpec, String) -> Unit)
    }
}
