package org.ncssar.rid2caltopo.video

import DroneSpecState
import StreamsViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.ncssar.rid2caltopo.data.CtDroneSpec

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignatorIndicator(
    streamDesignator: String,
    viewModel: StreamsViewModel,
    streamState: StreamState,
    streamErrorDetail: String?,
    onLongPress: () -> Unit
) {
    val errorSummary = formatStreamErrorDetail(streamErrorDetail)
    val renderDelayMs = viewModel.renderDelayMsFor(streamDesignator)
    val coordinateDisplayFormat = viewModel.coordinateDisplayFormat
    var coordinateMenuExpanded by remember(streamDesignator) { mutableStateOf(false) }
    val streamStateText = when (streamState) {
        StreamState.CONNECTING -> "Connecting..."
        StreamState.LIVE -> formatLiveState(renderDelayMs)
        StreamState.STOPPED -> "Stopped"
        StreamState.ERROR -> errorSummary?.let { "Error: $it" } ?: "Error"
    }
    val mapName = viewModel.mapName
    var mapStatus = "Standalone"
    if (null != mapName) {
        mapStatus = "Connected to $mapName"
    }
    val designatorState = viewModel.designatorStateFor(streamDesignator)

    val (color, locationText, detailText) = when (designatorState) {
        is DesignatorState.Green -> {
            val ds = viewModel.droneStates[streamDesignator]
            val feet: String
            val dur: String
            val location: String
            if (ds != null) {
                val feetPerMeter: Double = (ds.lastAlt * 3.28084F)
                location = CoordinateFormatter.format(ds.lastLat, ds.lastLng, coordinateDisplayFormat)
                feet = "%.0f".format(feetPerMeter)
                dur = ds.lastTimestamp
            } else {
                location = "loc:unknown"
                feet = "0.0"
                dur = "unknown"
            }
            Triple(
                Color(0xFF00FF00),
                "$location (${coordinateDisplayFormat.label})",
                "alt:${feet}', duration:${dur}, (mapStatus:${mapStatus})"
            )
        }
        is DesignatorState.Yellow -> Triple(
            Color(0xFFFFFF00),
            null,
            "Long-press to match telemetry (mapStatus:${mapStatus})"
        )

        DesignatorState.Red -> Triple(
            Color(0xFFFF0000),
            null,
            "No telemetry available (mapStatus:${mapStatus})"
        )
    }
    Column {
        Text(
            text = "$streamDesignator - $streamStateText",
            color = color,
            style = MaterialTheme.typography.titleLarge,
            maxLines = if (streamState == StreamState.ERROR) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(10.dp)
                .background(Color.Transparent)
        )
        if (locationText != null) {
            Text(
                text = locationText,
                color = color,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .background(Color.Transparent)
                    .clickable { coordinateMenuExpanded = true }
            )
            DropdownMenu(
                expanded = coordinateMenuExpanded,
                onDismissRequest = { coordinateMenuExpanded = false }
            ) {
                CoordinateDisplayFormat.values().forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.label) },
                        onClick = {
                            coordinateMenuExpanded = false
                            viewModel.setCoordinateDisplayFormat(format)
                        }
                    )
                }
            }
        }
        Text(
            text = detailText,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (streamState == StreamState.ERROR) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .background(Color.Transparent)
        )
        if (streamState == StreamState.ERROR && streamErrorDetail != null) {
            Text(
                text = streamErrorDetail,
                color = color,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .background(Color.Transparent)
            )
        }
    }
}

internal fun formatLiveState(renderDelayMs: Long?): String {
    val delayMs = renderDelayMs ?: return "Live"
    if (delayMs < 500L) return "Live"
    if (delayMs < 1_000L) return "lag:${delayMs}ms"
    return String.format(Locale.US, "lag:%.1fs", delayMs / 1000.0)
}

private fun formatStreamErrorDetail(streamErrorDetail: String?): String? {
    if (streamErrorDetail.isNullOrBlank()) return null
    return when {
        streamErrorDetail.contains("extended chunk stream IDs", ignoreCase = true) ->
            "RTMP publisher uses unsupported extended chunk IDs"
        streamErrorDetail.contains("connection reset by peer", ignoreCase = true) ->
            "RTMP publisher reset connection"
        streamErrorDetail.contains("i/o timeout", ignoreCase = true) ->
            "RTMP publisher timed out"
        streamErrorDetail.contains("unexpected EOF", ignoreCase = true) ->
            "RTMP publisher disconnected"
        streamErrorDetail.contains("received type 1 chunk without previous chunk", ignoreCase = true) ->
            "RTMP chunk stream lost sync"
        else -> streamErrorDetail
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
