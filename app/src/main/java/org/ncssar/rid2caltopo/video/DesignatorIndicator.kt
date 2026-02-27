package org.ncssar.rid2caltopo.video

import DroneSpecState
import StreamsViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignatorIndicator(
    streamDesignator: String,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    streamErrorDetail: String?,
    onLongPress: () -> Unit
) {
    val tag = "DesignatorIndicator"
    val streamStateText = when (streamState) {
        StreamState.CONNECTING -> "Connecting..."
        StreamState.LIVE -> "Live"
        StreamState.STOPPED -> "Stopped"
        StreamState.ERROR -> streamErrorDetail?.let { "Error ($it)" } ?: "Error"
    }
    val mapName = viewModel.mapName
    var mapStatus = "Standalone"
    if (null != mapName) {
        mapStatus = "Connected to $mapName"
    }
    val designatorState = viewModel.designatorStateFor(streamDesignator)

    CTDebug(tag, "Recomposing Designator: {$streamDesignator}")

    val (color, subtitle) = when (designatorState) {
        is DesignatorState.Green -> {
            val ds = viewModel.droneStates[streamDesignator]
            val lat: String
            val lng: String
            val feet: String
            val dur: String
            if (ds != null) {
                val feetPerMeter: Double = (ds.lastAlt * 3.28084F)
                lat = "%.5f".format(ds.lastLat)
                lng = "%.5f".format(ds.lastLng)
                feet = "%.0f".format(feetPerMeter)
                dur = ds.lastTimestamp
            } else {
                lat = "0.0"; lng = "0.0"; feet = "0.0"; dur = "unknown"
            }
            Color(0xFF00FF00) to "loc:${lat},${lng}, alt:${feet}', duration:${dur}, mapStatus:${mapStatus}"
        }
        is DesignatorState.Yellow ->
            Color(0xFFFFFF00) to "Long-press to match telemetry (mapStatus:${mapStatus}"

        DesignatorState.Red ->
            Color(0xFFFF0000) to "No telemetry available (mapStatus:${mapStatus}"
    }
    Column(
        modifier = Modifier
            .pointerInput(designatorState) { // Passing state as a key ensures the lambda stays updated
            detectTapGestures(
                onTap = {
                    // Equivalent to onClick = {}
                },
                onLongPress = {
                    CTDebug(tag, "onLongClick(): designatorState is '${designatorState}'")
                    if (designatorState is DesignatorState.Yellow) {
                        onLongPress()
                    }
                }
            )
        }


    ) {
        Text(
            text = "$streamDesignator - $streamStateText - $subtitle",
            color = color,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(10.dp)
                .background(Color.Transparent)
        )
    }
}

@Composable
fun DroneSpecPickerDialog(
    droneSpecStates: Map<String, DroneSpecState>,
    onSelect: (Map.Entry<String, DroneSpecState>) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Drone Telemetry") },
        text = {
            Column {
                droneSpecStates.forEach { droneSpecState ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(droneSpecState) }
                            .padding(8.dp)
                    ) {
                        Column {
                            val (designator, droneSpecState) = droneSpecState
                            Text("Designator: $designator")
                            Text("Mapped ID: ${droneSpecState.mappedId} Remote ID: ${droneSpecState.remoteId}")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
