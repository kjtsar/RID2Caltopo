package org.ncssar.rid2caltopo.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug

@Composable
fun DesignatorIndicator(
    streamDesignator: String,
    dsTimestamp : String?,
    designatorState: DesignatorState,
    streamState: StreamState,
    onLongPress: () -> Unit
) {
    val tag = "DesignatorIndicator"
    val streamStateText = when (streamState) {
        StreamState.CONNECTING -> "Connecting..."
        StreamState.LIVE -> "Live"
        StreamState.STOPPED -> "Stopped"
        StreamState.ERROR -> "Error"
    }
    CTDebug(tag, "Recomposing Designator")
    val (color, subtitle) = when (designatorState) {
        is DesignatorState.Green -> {
            val ds = designatorState.dronespec
            val feetPerMeter = 3.28084
            val lat = "%.5f".format(ds.lastLat)
            val lng = "%.5f".format(ds.lastLng)
            val feet = ds.lastAlt * feetPerMeter
            Color(0xFF2ECC71) to "loc:${lat},${lng}, alt:${feet}', duration:${dsTimestamp}"
        }
        is DesignatorState.Yellow ->
            Color(0xFFF1C40F) to "Long-press to match telemetry"

        DesignatorState.Red ->
            Color(0xFFE74C3C) to "No telemetry available"
    }
    Column(
        modifier = Modifier
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    CTDebug(tag, "onLongClick(): designatorState is '${designatorState}'")
                    if (designatorState is DesignatorState.Yellow) { onLongPress() }
                }
            ),


    ) {
        Text(
            text = "$streamDesignator - $streamStateText - $subtitle",
            color = color,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(Color.Transparent)
        )
    }
}

@Composable
fun DroneSpecPickerDialog(
    droneSpecs: List<CtDroneSpec>,
    onSelect: (CtDroneSpec) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Drone Telemetry") },
        text = {
            Column {
                droneSpecs.forEach { droneSpec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(droneSpec) }
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("Mapped ID: ${droneSpec.mappedId} Remote ID: ${droneSpec.remoteId}")
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
