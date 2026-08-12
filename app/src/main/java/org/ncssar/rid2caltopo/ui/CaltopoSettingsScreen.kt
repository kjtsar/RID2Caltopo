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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.ExternalDisplayAlertRouting
import org.ncssar.rid2caltopo.data.ExternalDisplayMode

@Composable
fun CaltopoSettingsScreen(
    onDismiss: () -> Unit,
    settingsViewModel: CaltopoSettingsViewModel = viewModel()
) {
    var showRidMappingAdmin by remember { mutableStateOf(false) }
    val ridMappingCount = CaltopoClient.GetPersistedDroneSpecs().size
    val organizationName by settingsViewModel.organizationName.collectAsState()
    val trackFolder by settingsViewModel.trackFolder.collectAsState()
    val incident by settingsViewModel.incident.collectAsState()
    val opPeriod by settingsViewModel.opPeriod.collectAsState()
    val caltopoTeamId by settingsViewModel.caltopoTeamId.collectAsState()
    val caltopoCredentialId by settingsViewModel.caltopoCredentialId.collectAsState()
    val caltopoCredentialSecret by settingsViewModel.caltopoCredentialSecret.collectAsState()
    val trackerUrl by settingsViewModel.trackerUrl.collectAsState()
    val trackerApiKey by settingsViewModel.trackerApiKey.collectAsState()
    val mutualAidTeamId by settingsViewModel.mutualAidTeamId.collectAsState()
    val mutualAidCredentialId by settingsViewModel.mutualAidCredentialId.collectAsState()
    val mutualAidCredentialSecret by settingsViewModel.mutualAidCredentialSecret.collectAsState()
    val mutualAidDomain by settingsViewModel.mutualAidDomain.collectAsState()
    val mutualAidSourceLabel by settingsViewModel.mutualAidSourceLabel.collectAsState()
    val mutualAidTargetFolder by settingsViewModel.mutualAidTargetFolder.collectAsState()
    val usePeers by settingsViewModel.usePeers.collectAsState()
    val minDistance by settingsViewModel.minDistance.collectAsState()
    val newTrackDelay by settingsViewModel.newTrackDelay.collectAsState()
    val bridgeCheckDistanceFeet by settingsViewModel.bridgeCheckDistanceFeet.collectAsState()
    val alarmVolumePercent by settingsViewModel.alarmVolumePercent.collectAsState()
    val maxIdleTimeInMinutes by settingsViewModel.maxIdleTimeInMinutes.collectAsState()
    val captureIncomingVideo by settingsViewModel.captureIncomingVideo.collectAsState()
    val remoteVideoControlEnabled by settingsViewModel.remoteVideoControlEnabled.collectAsState()
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
                Text(
                    "Organization / RID Map",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "RID map entries are normally loaded from the organization QR code. " +
                        "Use this editor to review, add, or correct Remote ID mappings stored on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showRidMappingAdmin = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View or Edit RID Map Entries ($ridMappingCount)")
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = organizationName,
                    onValueChange = settingsViewModel::onOrganizationNameChanged,
                    label = { Text("Organization designator") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = trackFolder,
                    onValueChange = settingsViewModel::onTrackFolderChanged,
                    label = { Text("CalTopo track folder") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = incident,
                    onValueChange = settingsViewModel::onIncidentChanged,
                    label = { Text("Incident") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = opPeriod,
                    onValueChange = settingsViewModel::onOpPeriodChanged,
                    label = { Text("Operational period") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "CalTopo Teams Account",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caltopoTeamId,
                    onValueChange = settingsViewModel::onCaltopoTeamIdChanged,
                    label = { Text("Team ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caltopoCredentialId,
                    onValueChange = settingsViewModel::onCaltopoCredentialIdChanged,
                    label = { Text("Credential ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caltopoCredentialSecret,
                    onValueChange = settingsViewModel::onCaltopoCredentialSecretChanged,
                    label = { Text("Credential secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = caltopoUrl,
                    onValueChange = settingsViewModel::onCaltopoDomainAndPortChanged,
                    label = { Text("Domain and port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Tracker Coordination",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = trackerUrl,
                    onValueChange = settingsViewModel::onTrackerUrlChanged,
                    label = { Text("Tracker URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = trackerApiKey,
                    onValueChange = settingsViewModel::onTrackerApiKeyChanged,
                    label = { Text("Tracker API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledSwitch(
                    label = "Use tracker peers",
                    checked = usePeers,
                    onCheckedChange = settingsViewModel::onUsePeersChanged
                )
                Text(
                    "Manual tracker changes configure coordination only. FAA proxy access remains organization-QR-only and is cleared when these fields are manually changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Mutual Aid Account",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mutualAidTeamId,
                    onValueChange = settingsViewModel::onMutualAidTeamIdChanged,
                    label = { Text("Team ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mutualAidCredentialId,
                    onValueChange = settingsViewModel::onMutualAidCredentialIdChanged,
                    label = { Text("Credential ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mutualAidCredentialSecret,
                    onValueChange = settingsViewModel::onMutualAidCredentialSecretChanged,
                    label = { Text("Credential secret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mutualAidDomain,
                    onValueChange = settingsViewModel::onMutualAidDomainChanged,
                    label = { Text("Domain and port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mutualAidSourceLabel,
                    onValueChange = settingsViewModel::onMutualAidSourceLabelChanged,
                    label = { Text("Source organization label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mutualAidTargetFolder,
                    onValueChange = settingsViewModel::onMutualAidTargetFolderChanged,
                    label = { Text("Target folder hint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "These are the same six values accepted by ct_mutual_aid_credentials JSON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Capture Streams:")
                    Switch(
                        checked = captureIncomingVideo,
                        onCheckedChange = { settingsViewModel.onCaptureIncomingVideoChanged(it) }
                    )
                    Text(if (captureIncomingVideo) "Yes" else "No")
                }
                LabeledSwitch(
                    label = "Remote Video Control",
                    checked = remoteVideoControlEnabled,
                    onCheckedChange = settingsViewModel::onRemoteVideoControlEnabledChanged,
                )
                Text(
                    "When enabled, an authenticated requester chooses video quality after the link test without a per-request approval prompt. Only one viewer can use this tablet at a time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )

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
                    Text("Enable Nearby FAA Monitoring (NOTAM/TFR + Airport/LAANC):")
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
                    label = { Text("NOTAM Radius (1, 2, 4, 8, 16 statute miles)") }
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
                    label = { Text("Boundary query radius (1–50 statute miles)") }
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
    if (showRidMappingAdmin) {
        RidMappingAdminDialog(onDismiss = { showRidMappingAdmin = false })
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
