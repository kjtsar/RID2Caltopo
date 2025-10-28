package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CtDroneSpec
import java.util.Locale

@Composable
fun AircraftItem(aircraft: CtDroneSpec) {
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.background(Color.Black).padding(2.dp).height(25.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.width(200.dp).background(Color.White).padding(1.dp)) {
                Text(text = aircraft.remoteId, textAlign = TextAlign.End)
            }
            Column(modifier = Modifier.width( 200.dp).background(Color.White).padding(1.dp)) {
                Text(text = aircraft.mappedId, textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.width( 60.dp).background(Color.White).padding(1.dp)) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.BT4)}", textAlign = TextAlign.End)
            }
            Column(modifier = Modifier.width( 60.dp).background(Color.White).padding(1.dp)) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.BT5)}", textAlign = TextAlign.End)
            }
            Column(modifier = Modifier.width( 60.dp).background(Color.White).padding(1.dp)) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.WIFI)}", textAlign = TextAlign.End)
            }
            Column(modifier = Modifier.width( 60.dp).background(Color.White).padding(1.dp)) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.WNAN)}", textAlign = TextAlign.End)
            }
            Column(modifier = Modifier.width( 60.dp).background(Color.White).padding(1.dp)) {
                Text(text = "${aircraft.totalCount}", textAlign = TextAlign.End)
            }
            Column(modifier = Modifier.width( 150.dp).background(Color.White).padding(1.dp)) {
                CTDebug("AircraftItem.kt", "Updating duration for "+ aircraft.mappedId)

                Text(text = "${aircraft.getDurationInSecAsString()}s", fontSize=14.sp, textAlign = TextAlign.End)
            }
        }
    }
}
