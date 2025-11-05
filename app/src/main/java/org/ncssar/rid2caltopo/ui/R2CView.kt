package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.ui.theme.RID2CaltopoTheme
import java.util.Locale

@Composable
fun R2CView(
    hostName: String,
    drones : List<CtDroneSpec>,
    appUptime : String,
    onMappedIdChange: (CtDroneSpec, String) -> Unit
) {
    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            AppHeader(appUptime, hostName)
            RidmapHeader()
            drones.forEach { drone ->
                DroneItem(drone = drone) { newMappedId ->
                    onMappedIdChange(drone, newMappedId)
                }
            }
        }
    }
}

@Composable
fun AppHeader(appUptime: String, hostName: String) {
    Row(
        modifier = Modifier
            .background(Color.Blue)
            .padding(3.dp),
//        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = hostName,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
                textAlign = TextAlign.Center,
                fontSize = 18.sp
            )
            Text(
                text = R2CActivity.GetMyAppVersion(),
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
                text = "ct rtt",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(25.dp)
                    .background(Color.White),
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
            .background(Color.Blue)
            .padding(2.dp),
//        horizontalArrangement = Arrangement.SpaceBetween
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
                modifier = Modifier.fillMaxWidth()
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
fun R2CViewPreview() {
    RID2CaltopoTheme {
        R2CView("", emptyList(), "", {} as (CtDroneSpec, String) -> Unit)
    }
}
