package org.ncssar.rid2caltopo.ui
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.ncssar.rid2caltopo.app.R2CActivity

@Composable
fun ScannerScreen(
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Scanner Status", style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row (verticalAlignment = Alignment.CenterVertically) {
                    val bt4: Boolean = R2CActivity.legacyBluetoothSupported
                    Text("Bluetooth 4: ${bt4}")
                }
                Row (verticalAlignment = Alignment.CenterVertically) {
                    val bt5: Boolean =
                        R2CActivity.codedPhySupported || R2CActivity.extendedAdvertisingSupported
                    Text("Bluetooth 5: ${bt5}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("WiFi: ${R2CActivity.wifiSupported}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NaN: ${R2CActivity.nanSupported}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
