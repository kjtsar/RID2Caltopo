package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun CaltopoActionInterface(
    state: CaltopoConnectionState,
    onActionClicked: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Button(
        onClick = onActionClicked,
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(6.dp),
        colors = when (state) {
            is CaltopoConnectionState.MapSelected -> ButtonDefaults.buttonColors(containerColor = colorScheme.primary)
            is CaltopoConnectionState.StandAlone -> ButtonDefaults.buttonColors(containerColor = Color.Gray)
            else -> ButtonDefaults.buttonColors(containerColor = colorScheme.surfaceVariant)
        }
    ) {
        when (state) {
            is CaltopoConnectionState.StandAlone -> Text("STANDALONE")
            is CaltopoConnectionState.CheckingCredentials -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            is CaltopoConnectionState.CredentialsVerified -> Text("SELECT MAP")
            is CaltopoConnectionState.CredentialsLoaded -> Text("SELECT MAP")
            is CaltopoConnectionState.Connecting -> Text("CONNECTING...")
            is CaltopoConnectionState.MapSelected -> {
                Text(
                    text = state.map.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
