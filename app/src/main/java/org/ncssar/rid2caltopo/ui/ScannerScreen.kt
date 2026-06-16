
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.data.CaltopoClient
import kotlin.math.roundToInt

@Composable
fun ScannerScreen(
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val persistedDroneSpecs = CaltopoClient.GetPersistedDroneSpecs()
    val verticalSliderMax = verticalScrollState.maxValue.toFloat()
    val horizontalSliderMax = horizontalScrollState.maxValue.toFloat()
    val statusText = buildStatusText(persistedDroneSpecs)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 960.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Status", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 560.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(verticalScrollState)
                                .horizontalScroll(horizontalScrollState)
                        ) {
                            Column(
                                modifier = Modifier.width(IntrinsicSize.Max)
                            ) {
                                Text("BUILD_VERSION: ${currentBuildVersion()}")
                                Text("BUILD_TIME: ${currentBuildTime()}")

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("Scanner Status")
                                Text("Bluetooth 4: ${R2CActivity.legacyBluetoothSupported}")
                                Text(
                                    "Bluetooth 5: ${
                                        R2CActivity.codedPhySupported ||
                                            R2CActivity.extendedAdvertisingSupported
                                    }"
                                )
                                Text("WiFi: ${R2CActivity.wifiSupported}")
                                Text("NaN: ${R2CActivity.nanSupported}")

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("Loaded Config Files")
                                Text(CaltopoClient.GetConfigFilesLoadedRecord())

                                Spacer(modifier = Modifier.height(24.dp))
                                StatusSectionHeader("Persisted CtDroneSpecs")
                                if (persistedDroneSpecs.isEmpty()) {
                                    Text("No persisted CtDroneSpecs.")
                                } else {
                                    persistedDroneSpecs.forEach { spec ->
                                        Text(
                                            "remoteId: ${spec.remoteId}    mappedId: ${spec.mappedId}    org: ${spec.org.orEmpty()}    owner: ${spec.owner.orEmpty()}    model: ${spec.model.orEmpty()}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(vertical = 6.dp)
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }

                    if (verticalSliderMax > 0f) {
                        Box(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .height(220.dp)
                                .width(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Slider(
                                value = verticalScrollState.value.toFloat(),
                                onValueChange = { newValue ->
                                    coroutineScope.launch {
                                        verticalScrollState.scrollTo(newValue.roundToInt())
                                    }
                                },
                                valueRange = 0f..verticalSliderMax,
                                modifier = Modifier
                                    .width(220.dp)
                                    .rotate(270f)
                            )
                        }
                    }
                }

                if (horizontalSliderMax > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = horizontalScrollState.value.toFloat(),
                        onValueChange = { newValue ->
                            coroutineScope.launch {
                                horizontalScrollState.scrollTo(newValue.roundToInt())
                            }
                        },
                        valueRange = 0f..horizontalSliderMax,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        modifier = Modifier.padding(end = 12.dp),
                        onClick = {
                            clipboard.setText(AnnotatedString(statusText))
                            CaltopoClient.ShowToast("Status copied to clipboard.")
                        }
                    ) {
                        Text("Copy")
                    }
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
}

internal fun buildStatusText(persistedDroneSpecs: List<org.ncssar.rid2caltopo.data.CtDroneSpec>): String {
    val bluetooth5 = R2CActivity.codedPhySupported || R2CActivity.extendedAdvertisingSupported
    val builder = StringBuilder()
    builder.appendLine("BUILD_VERSION: ${currentBuildVersion()}")
    builder.appendLine("BUILD_TIME: ${currentBuildTime()}")
    builder.appendLine()
    builder.appendLine("Scanner Status")
    builder.appendLine("Bluetooth 4: ${R2CActivity.legacyBluetoothSupported}")
    builder.appendLine("Bluetooth 5: $bluetooth5")
    builder.appendLine("WiFi: ${R2CActivity.wifiSupported}")
    builder.appendLine("NaN: ${R2CActivity.nanSupported}")
    builder.appendLine()
    builder.appendLine("Loaded Config Files")
    builder.appendLine(CaltopoClient.GetConfigFilesLoadedRecord())
    builder.appendLine()
    builder.appendLine("Persisted CtDroneSpecs")
    if (persistedDroneSpecs.isEmpty()) {
        builder.append("No persisted CtDroneSpecs.")
    } else {
        persistedDroneSpecs.forEach { spec ->
            builder.appendLine(
                "remoteId: ${spec.remoteId}    mappedId: ${spec.mappedId}    org: ${spec.org.orEmpty()}    owner: ${spec.owner.orEmpty()}    model: ${spec.model.orEmpty()}"
            )
        }
    }
    return builder.toString().trimEnd()
}

private fun currentBuildVersion(): String = buildConfigString("BUILD_VERSION")

private fun currentBuildTime(): String = buildConfigString("BUILD_TIME")

internal fun buildConfigString(
    fieldName: String,
    buildConfigClass: Class<*> = BuildConfig::class.java
): String =
    runCatching { buildConfigClass.getField(fieldName).get(null) as String }
        .getOrDefault("unknown")
