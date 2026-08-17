package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

internal fun incidentMapDisplayValue(state: CaltopoConnectionState): String {
    if (state !is CaltopoConnectionState.MapSelected) return "Standalone"
    return state.map.title.trim().ifEmpty {
        state.map.id.trim().ifEmpty { "Standalone" }
    }
}

@Composable
fun CaltopoActionInterface(
    state: CaltopoConnectionState,
    onActionClicked: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val colorScheme = MaterialTheme.colorScheme
    Button(
        onClick = onActionClicked,
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
    ) {
        Text(
            text = incidentMapDisplayValue(state),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
fun ConnectedOptionsDialog(
    mapName: String,
    onDismiss: () -> Unit,
    onSwitchMap: () -> Unit,      // Generic callback
    onDisconnect: () -> Unit      // Generic callback
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map Options") },
        text = { Text("You are currently synced with: $mapName") },
        confirmButton = {
            Button(onClick = onSwitchMap) { // Just call the lambda
                Text("Switch Map")
            }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) { // Just call the lambda
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
