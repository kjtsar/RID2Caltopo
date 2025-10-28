package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.data.CtDroneSpec
@Composable
fun AircraftItem(aircraft: CtDroneSpec, onMappedIdChange: (String) -> Unit) {
    var text by remember { mutableStateOf(aircraft.mappedId) }
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier.background(Color.Black).padding(1.dp).height(65.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.width(250.dp).background(Color.White).padding(1.dp)
                    .fillMaxWidth().fillMaxHeight()
            ) {
                Text(text = aircraft.remoteId, textAlign = TextAlign.End)
            }
            Column(
                modifier = Modifier.width(250.dp).background(Color.White).padding(1.dp)
                    .fillMaxWidth().fillMaxHeight()
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onMappedIdChange(text)
                            focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier.onFocusChanged {
                        if (!it.isFocused) {
                            onMappedIdChange(text)
                        }
                    }
                )
            }
            Column(
                modifier = Modifier
                    .width(100.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.BT4)}", textAlign = TextAlign.Center)
            }
            Column(
                modifier = Modifier
                    .width( 100.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.BT5)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 100.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.WIFI)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 100.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${aircraft.getTransportCount(CtDroneSpec.TransportTypeEnum.WNAN)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 100.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${aircraft.totalCount}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 150.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = aircraft.getDurationInSecAsString(), fontSize=14.sp, textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 150.dp)
                    .background(Color.White)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val r2cClient = aircraft.myR2cOwner
                val str : String
                if (null != r2cClient) {
                    str = aircraft.myR2cOwner!!.getRttString()
                } else {
                    str = "n/a"
                }
                Text(text = str, fontSize = 14.sp, textAlign = TextAlign.Right)
            }
        }
    }
}
