package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CtDroneSpec


@Composable
fun DroneItem(drone: CtDroneSpec, onMappedIdChange: (String) -> Unit) {
    var text by remember { mutableStateOf(drone.mappedId) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(drone.mappedId) {
        if (text != drone.mappedId) text = drone.mappedId
    }
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondary)
                .padding(1.dp)
                .height(32.dp)
                .height(IntrinsicSize.Min)
        ) {
            Column(
                modifier = Modifier
                    .width(28.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (drone.droneIsLocallyOwned()) {
                    Image(
                        painter = painterResource(id = R.drawable.earth),
                        contentDescription = "earth",
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                }
            }
            Column(
                modifier = Modifier.width(250.dp).background(MaterialTheme.colorScheme.surface).padding(1.dp)
                    .fillMaxWidth().fillMaxHeight()
            ) {
                var fontWeight = FontWeight.Normal
                var fontStyle = FontStyle.Normal
                if (drone.droneIsLocallyOwned()) {
                    fontStyle = FontStyle.Italic
                    fontWeight = FontWeight.Bold
                }

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = fontWeight,
                        fontStyle = fontStyle,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onMappedIdChange(text)
                            focusManager.clearFocus()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .border(width = 1.dp, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp))
                                .padding(2.dp)
                        ) {
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp)
                        .height(26.dp)
                        .onFocusChanged {
                            if (!it.isFocused) {
                                onMappedIdChange(text)
                            }
                        },
                )
            }

            Column(
                modifier = Modifier.width(250.dp).background(MaterialTheme.colorScheme.surface).padding(1.dp)
                    .fillMaxWidth().fillMaxHeight()
            ) {
                Text(text = drone.remoteId, textAlign = TextAlign.End)
            }
            Column(
                modifier = Modifier
                    .width(80.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.BT4)}", textAlign = TextAlign.Center)
            }
            Column(
                modifier = Modifier
                    .width( 80.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.BT5)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 80.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.WIFI)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 80.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.WNAN)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 80.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${drone.getTransportCount(CtDroneSpec.TransportTypeEnum.R2C)}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 80.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "${drone.totalCount}", textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 150.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = drone.getDurationInSecAsString(), fontSize=14.sp, textAlign = TextAlign.Right)
            }
            Column(
                modifier = Modifier
                    .width( 150.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val r2cClient = drone.myR2cOwner
                val str : String
                if (null != r2cClient) {
                    str = drone.myR2cOwner!!.getRttString()
                } else {
                    str = "n/a"
                }
                Text(text = str, fontSize = 14.sp, textAlign = TextAlign.Right)
            }
        }
    }
}
