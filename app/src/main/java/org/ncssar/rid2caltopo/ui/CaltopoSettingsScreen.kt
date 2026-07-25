/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.data.ExternalDisplayAlertRouting
import org.ncssar.rid2caltopo.data.ExternalDisplayMode

@Composable
fun CaltopoSettingsScreen(
    onDismiss: () -> Unit,
    settingsViewModel: CaltopoSettingsViewModel = viewModel()
) {
    val minDistance by settingsViewModel.minDistance.collectAsState()
    val newTrackDelay by settingsViewModel.newTrackDelay.collectAsState()
    val bridgeCheckDistanceFeet by settingsViewModel.bridgeCheckDistanceFeet.collectAsState()
    val alarmVolumePercent by settingsViewModel.alarmVolumePercent.collectAsState()
    val maxIdleTimeInMinutes by settingsViewModel.maxIdleTimeInMinutes.collectAsState()
    val captureIncomingVideo by settingsViewModel.captureIncomingVideo.collectAsState()
    val standaloneR2cCoordinationEnabled by settingsViewModel.standaloneR2cCoordinationEnabled.collectAsState()
    val predictiveHeadEnabled by settingsViewModel.predictiveHeadEnabled.collectAsState()
    val proximityAlertSpacingFeet by settingsViewModel.proximityAlertSpacingFeet.collectAsState()
    val caltopoUrl by settingsViewModel.caltopoUrl.collectAsState()
    val notamEnabled by settingsViewModel.notamEnabled.collectAsState()
    val notamRadiusNm by settingsViewModel.notamRadiusNm.collectAsState()
    val notamRefreshIntervalSeconds by settingsViewModel.notamRefreshIntervalSeconds.collectAsState()
    val notamAutoRefresh by settingsViewModel.notamAutoRefresh.collectAsState()
    val notamStatus by settingsViewModel.notamStatus.collectAsState()
    val landRestrictionsEnabled by settingsViewModel.landRestrictionsEnabled.collectAsState()
    val landRestrictionsShowOnMap by settingsViewModel.landRestrictionsShowOnMap.collectAsState()
    val landRestrictionsAutoRefresh by settingsViewModel.landRestrictionsAutoRefresh.collectAsState()
    val landRestrictionsRadiusNm by settingsViewModel.landRestrictionsRadiusNm.collectAsState()
    val externalDisplayMode by settingsViewModel.externalDisplayMode.collectAsState()
    val externalDisplayAutoOpen by settingsViewModel.externalDisplayAutoOpen.collectAsState()
    val externalDisplayReturnToPhoneOnly by settingsViewModel.externalDisplayReturnToPhoneOnly.collectAsState()
    val externalDisplayAllowInteraction by settingsViewModel.externalDisplayAllowInteraction.collectAsState()
    val externalDisplayAlertRouting by settingsViewModel.externalDisplayAlertRouting.collectAsState()
    val dismissAndSave = {
        settingsViewModel.saveSettings()
        onDismiss()
    }

    Dialog(onDismissRequest = dismissAndSave) {
        Card (modifier = Modifier.verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = minDistance,
                    onValueChange = { settingsViewModel.onMinDistanceChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Min Dist (ft)") }
                )
                OutlinedTextField(
                    value = newTrackDelay,
                    onValueChange = { settingsViewModel.onNewTrackDelayChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("New Track Delay (s)") }
                )
                OutlinedTextField(
                    value = bridgeCheckDistanceFeet,
                    onValueChange = {
                        settingsViewModel.onBridgeCheckDistanceFeetChanged(it.filter { ch -> ch.isDigit() })
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Bridge Check Distance (ft)") }
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Audio Alarm Volume: $alarmVolumePercent%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = alarmVolumePercent.toFloat(),
                        onValueChange = {
                            settingsViewModel.onAlarmVolumePercentChanged(it.toInt())
                        },
                        valueRange = 0f..100f,
                        steps = 19
                    )
                    Button(
                        onClick = { SpokenWarningCenter.requestAudioAlarmTest() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Audio Alarm Test")
                    }
                }
                OutlinedTextField(
                    value = maxIdleTimeInMinutes,
                    onValueChange = { settingsViewModel.onMaxIdleTimeInMinutesChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Max App Idle Time (minutes)") }
                )
                OutlinedTextField(
                    value = caltopoUrl,
                    onValueChange = { settingsViewModel.onCaltopoDomainAndPortChanged(it) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Caltopo Domain And Port (i.e. caltopo.com)") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Capture Streams:")
                    Switch(
                        checked = captureIncomingVideo,
                        onCheckedChange = { settingsViewModel.onCaptureIncomingVideoChanged(it) }
                    )
                    Text(if (captureIncomingVideo) "Yes" else "No")
                }

                LabeledSwitch(
                    label = "Standalone R2C coordination",
                    checked = standaloneR2cCoordinationEnabled,
                    onCheckedChange = settingsViewModel::onStandaloneR2cCoordinationEnabledChanged
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Predictive Head:")
                    Switch(
                        checked = predictiveHeadEnabled,
                        onCheckedChange = { settingsViewModel.onPredictiveHeadEnabledChanged(it) }
                    )
                    Text(if (predictiveHeadEnabled) "On" else "Off")
                }

                OutlinedTextField(
                    value = proximityAlertSpacingFeet,
                    onValueChange = {
                        settingsViewModel.onProximityAlertSpacingFeetChanged(it.filter { ch -> ch.isDigit() })
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Proximity Alert Spacing (ft)") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("NOTAM Admin", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = notamStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Nearby NOTAMs:")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = notamEnabled,
                        onCheckedChange = { settingsViewModel.onNotamEnabledChanged(it) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (notamEnabled) "Yes" else "No")
                }

                OutlinedTextField(
                    value = notamRadiusNm,
                    onValueChange = { settingsViewModel.onNotamRadiusNmChanged(it.filter { ch -> ch.isDigit() }) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("NOTAM Radius (2, 4, 8, 16 NM)") }
                )

                OutlinedTextField(
                    value = notamRefreshIntervalSeconds,
                    onValueChange = { settingsViewModel.onNotamRefreshIntervalSecondsChanged(it.filter { ch -> ch.isDigit() }) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("NOTAM Refresh Interval (s, min 1800)") }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto Refresh:")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = notamAutoRefresh,
                        onCheckedChange = { settingsViewModel.onNotamAutoRefreshChanged(it) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (notamAutoRefresh) "Yes" else "No")
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Land / Agency Restrictions", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Checks NPS units, National Wildlife Refuges, USFS wilderness, and Colorado parks and wildlife properties. Results distinguish land-use rules from FAA airspace restrictions and include agency follow-up links.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable protected-land checks:")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = landRestrictionsEnabled,
                        onCheckedChange = settingsViewModel::onLandRestrictionsEnabledChanged
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show protected lands on map:")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = landRestrictionsShowOnMap,
                        enabled = landRestrictionsEnabled,
                        onCheckedChange = settingsViewModel::onLandRestrictionsShowOnMapChanged
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Refresh automatically:")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = landRestrictionsAutoRefresh,
                        enabled = landRestrictionsEnabled,
                        onCheckedChange = settingsViewModel::onLandRestrictionsAutoRefreshChanged
                    )
                }
                OutlinedTextField(
                    value = landRestrictionsRadiusNm,
                    onValueChange = { settingsViewModel.onLandRestrictionsRadiusNmChanged(it.filter(Char::isDigit)) },
                    enabled = landRestrictionsEnabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    label = { Text("Boundary query radius (1–50 NM)") }
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("External Display", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Text("External display mode", style = MaterialTheme.typography.titleSmall)
                SingleChoiceGroup(
                    options = ExternalDisplayMode.entries,
                    selected = externalDisplayMode,
                    label = { it.displayLabel },
                    onSelected = settingsViewModel::onExternalDisplayModeChanged
                )
                when (externalDisplayMode) {
                    ExternalDisplayMode.Off -> {
                        Text(
                            "RID2Caltopo will not manage the external display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ExternalDisplayMode.OsMirroring -> {
                        Text(
                            "RID2Caltopo will not open its own external window. Enable mirroring from Samsung/Android display controls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ExternalDisplayMode.AppManaged -> {
                        LabeledSwitch(
                            label = "Auto-open on connect",
                            checked = externalDisplayAutoOpen,
                            onCheckedChange = settingsViewModel::onExternalDisplayAutoOpenChanged
                        )
                        LabeledSwitch(
                            label = "Return to phone-only layout on disconnect",
                            checked = externalDisplayReturnToPhoneOnly,
                            onCheckedChange = settingsViewModel::onExternalDisplayReturnToPhoneOnlyChanged
                        )
                        LabeledSwitch(
                            label = "Allow external display interaction",
                            checked = externalDisplayAllowInteraction,
                            onCheckedChange = settingsViewModel::onExternalDisplayAllowInteractionChanged
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "App-managed external display currently uses a single streams-focused layout.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Alert routing", style = MaterialTheme.typography.titleSmall)
                        SingleChoiceGroup(
                            options = ExternalDisplayAlertRouting.entries,
                            selected = externalDisplayAlertRouting,
                            label = { it.displayLabel },
                            onSelected = settingsViewModel::onExternalDisplayAlertRoutingChanged
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row {
                    Button(onClick = dismissAndSave) {
                        Text("Save")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = dismissAndSave) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (checked) "Yes" else "No")
    }
}

@Composable
private fun <T> SingleChoiceGroup(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Column {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = { onSelected(option) }
                )
                Text(label(option))
            }
        }
    }
}
