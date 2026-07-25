/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.app.R2CApplication
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.ExternalDisplayAlertRouting
import org.ncssar.rid2caltopo.data.ExternalDisplayConfig
import org.ncssar.rid2caltopo.data.ExternalDisplayContentMode
import org.ncssar.rid2caltopo.data.ExternalDisplayMode
import org.ncssar.rid2caltopo.data.ExternalDisplayPrefs
import org.ncssar.rid2caltopo.notam.NotamAuthManager
import org.ncssar.rid2caltopo.notam.NotamCenter
import org.ncssar.rid2caltopo.landrestrictions.LandRestrictionCenter
class CaltopoSettingsViewModel : ViewModel(), CaltopoClient.ClientSettingsListener {

    // --- Live Data for UI --- //

    private val _minDistance = MutableStateFlow(CaltopoClient.GetMinDistanceInFeet().toString())
    val minDistance = _minDistance.asStateFlow()

    private val _newTrackDelay = MutableStateFlow(CaltopoClient.GetNewTrackDelayInSeconds().toString())
    val newTrackDelay = _newTrackDelay.asStateFlow()

    private val _maxFlatlineToneDuration = MutableStateFlow(CaltopoClient.GetMaxFlatlineToneDurationInSeconds().toString())
    val maxFlatlineToneDuration = _maxFlatlineToneDuration.asStateFlow()

    private val _bridgeCheckDistanceFeet = MutableStateFlow(CaltopoClient.GetBridgeCheckDistanceFeet().toString())
    val bridgeCheckDistanceFeet = _bridgeCheckDistanceFeet.asStateFlow()

    private val _alarmVolumePercent = MutableStateFlow(CaltopoClient.GetAlarmVolumePercent())
    val alarmVolumePercent = _alarmVolumePercent.asStateFlow()

    private val _usePeers = MutableStateFlow(CaltopoClient.GetUsePeersFlag())
    val usePeers = _usePeers.asStateFlow()

    private val _standaloneR2cCoordinationEnabled = MutableStateFlow(CaltopoClient.GetStandaloneR2cCoordinationEnabled())
    val standaloneR2cCoordinationEnabled = _standaloneR2cCoordinationEnabled.asStateFlow()

    private val _captureIncomingVideo = MutableStateFlow(CaltopoClient.GetCaptureVideoStreamsFlag())
    val captureIncomingVideo = _captureIncomingVideo.asStateFlow()
    private val _predictiveHeadEnabled = MutableStateFlow(CaltopoClient.GetPredictiveHeadEnabled())
    val predictiveHeadEnabled = _predictiveHeadEnabled.asStateFlow()
    private val _proximityAlertSpacingFeet = MutableStateFlow(CaltopoClient.GetProximityAlertSpacingFeet().toString())
    val proximityAlertSpacingFeet = _proximityAlertSpacingFeet.asStateFlow()

    private val _maxIdleTimeInMinutes = MutableStateFlow(CaltopoClient.GetMaxIdleTimeInMinutes().toString())
    val maxIdleTimeInMinutes = _maxIdleTimeInMinutes.asStateFlow()

    private val _incident = MutableStateFlow( CaltopoClient.GetIncident())
    val incident = _incident.asStateFlow()

    private val _opPeriod = MutableStateFlow( CaltopoClient.GetOpPeriod())
    val opPeriod = _opPeriod.asStateFlow()

    private val _caltopoDomainAndPort = MutableStateFlow( CaltopoClient.GetCaltopoDomainAndPort())
    val caltopoUrl = _caltopoDomainAndPort.asStateFlow()

    private val _notamEnabled = MutableStateFlow(CaltopoClient.GetNotamEnabled())
    val notamEnabled = _notamEnabled.asStateFlow()

    private val _notamRadiusNm = MutableStateFlow(CaltopoClient.GetNotamRadiusNm().toString())
    val notamRadiusNm = _notamRadiusNm.asStateFlow()

    private val _notamRefreshIntervalSeconds = MutableStateFlow(CaltopoClient.GetNotamRefreshIntervalSeconds().toString())
    val notamRefreshIntervalSeconds = _notamRefreshIntervalSeconds.asStateFlow()

    private val _notamAutoRefresh = MutableStateFlow(CaltopoClient.GetNotamAutoRefresh())
    val notamAutoRefresh = _notamAutoRefresh.asStateFlow()

    private val _notamStatus = MutableStateFlow(buildNotamStatus())
    val notamStatus = _notamStatus.asStateFlow()

    private val _landRestrictionsEnabled = MutableStateFlow(CaltopoClient.GetLandRestrictionsEnabled())
    val landRestrictionsEnabled = _landRestrictionsEnabled.asStateFlow()
    private val _landRestrictionsShowOnMap = MutableStateFlow(CaltopoClient.GetLandRestrictionsShowOnMap())
    val landRestrictionsShowOnMap = _landRestrictionsShowOnMap.asStateFlow()
    private val _landRestrictionsAutoRefresh = MutableStateFlow(CaltopoClient.GetLandRestrictionsAutoRefresh())
    val landRestrictionsAutoRefresh = _landRestrictionsAutoRefresh.asStateFlow()
    private val _landRestrictionsRadiusNm = MutableStateFlow(CaltopoClient.GetLandRestrictionsRadiusNm().toString())
    val landRestrictionsRadiusNm = _landRestrictionsRadiusNm.asStateFlow()

    private val initialExternalConfig = R2CApplication.getAppCtxt()?.let { ExternalDisplayPrefs.load(it) }
        ?: ExternalDisplayConfig()
    private val _externalDisplayMode = MutableStateFlow(initialExternalConfig.mode)
    val externalDisplayMode = _externalDisplayMode.asStateFlow()
    private val _externalDisplayAutoOpen = MutableStateFlow(initialExternalConfig.autoOpenOnConnect)
    val externalDisplayAutoOpen = _externalDisplayAutoOpen.asStateFlow()
    private val _externalDisplayReturnToPhoneOnly = MutableStateFlow(initialExternalConfig.returnToPhoneOnlyLayoutOnDisconnect)
    val externalDisplayReturnToPhoneOnly = _externalDisplayReturnToPhoneOnly.asStateFlow()
    private val _externalDisplayAllowInteraction = MutableStateFlow(initialExternalConfig.allowInteraction)
    val externalDisplayAllowInteraction = _externalDisplayAllowInteraction.asStateFlow()
    private val _externalDisplayContentMode = MutableStateFlow(initialExternalConfig.contentMode)
    val externalDisplayContentMode = _externalDisplayContentMode.asStateFlow()
    private val _externalDisplayAlertRouting = MutableStateFlow(initialExternalConfig.alertRouting)
    val externalDisplayAlertRouting = _externalDisplayAlertRouting.asStateFlow()



    init {
        CaltopoClient.SetSettingsListener(this)
        settingsChanged() // load initial values.
    }

    override fun settingsChanged() {
        _usePeers.value = CaltopoClient.GetUsePeersFlag()
        _standaloneR2cCoordinationEnabled.value = CaltopoClient.GetStandaloneR2cCoordinationEnabled()
        _captureIncomingVideo.value = CaltopoClient.GetCaptureVideoStreamsFlag()
        _predictiveHeadEnabled.value = CaltopoClient.GetPredictiveHeadEnabled()
        _proximityAlertSpacingFeet.value = CaltopoClient.GetProximityAlertSpacingFeet().toString()
        _newTrackDelay.value = CaltopoClient.GetNewTrackDelayInSeconds().toString()
        _maxFlatlineToneDuration.value = CaltopoClient.GetMaxFlatlineToneDurationInSeconds().toString()
        _bridgeCheckDistanceFeet.value = CaltopoClient.GetBridgeCheckDistanceFeet().toString()
        _alarmVolumePercent.value = CaltopoClient.GetAlarmVolumePercent()
        _minDistance.value = CaltopoClient.GetMinDistanceInFeet().toString()
        _maxIdleTimeInMinutes.value = CaltopoClient.GetMaxIdleTimeInMinutes().toString()
        _caltopoDomainAndPort.value = CaltopoClient.GetCaltopoDomainAndPort()
        _notamEnabled.value = CaltopoClient.GetNotamEnabled()
        _notamRadiusNm.value = CaltopoClient.GetNotamRadiusNm().toString()
        _notamRefreshIntervalSeconds.value = CaltopoClient.GetNotamRefreshIntervalSeconds().toString()
        _notamAutoRefresh.value = CaltopoClient.GetNotamAutoRefresh()
        _notamStatus.value = buildNotamStatus()
        _landRestrictionsEnabled.value = CaltopoClient.GetLandRestrictionsEnabled()
        _landRestrictionsShowOnMap.value = CaltopoClient.GetLandRestrictionsShowOnMap()
        _landRestrictionsAutoRefresh.value = CaltopoClient.GetLandRestrictionsAutoRefresh()
        _landRestrictionsRadiusNm.value = CaltopoClient.GetLandRestrictionsRadiusNm().toString()
        val externalConfig = R2CApplication.getAppCtxt()?.let { ExternalDisplayPrefs.load(it) }
            ?: ExternalDisplayConfig()
        _externalDisplayMode.value = externalConfig.mode
        _externalDisplayAutoOpen.value = externalConfig.autoOpenOnConnect
        _externalDisplayReturnToPhoneOnly.value = externalConfig.returnToPhoneOnlyLayoutOnDisconnect
        _externalDisplayAllowInteraction.value = externalConfig.allowInteraction
        _externalDisplayContentMode.value = externalConfig.contentMode
        _externalDisplayAlertRouting.value = externalConfig.alertRouting
    }

    // --- UI Event Handlers --- //

    fun onMinDistanceChanged(newMinDistance: String) {
        _minDistance.value = newMinDistance
    }

    fun onNewTrackDelayChanged(newDelay: String) {
        _newTrackDelay.value = newDelay
    }

    fun onMaxFlatlineToneDurationChanged(newDuration: String) {
        _maxFlatlineToneDuration.value = newDuration
    }

    fun onBridgeCheckDistanceFeetChanged(newDistance: String) {
        _bridgeCheckDistanceFeet.value = newDistance
    }

    fun onAlarmVolumePercentChanged(percent: Int) {
        _alarmVolumePercent.value = CaltopoClient.SetAlarmVolumePercent(percent)
    }

    fun onMaxIdleTimeInMinutesChanged(newVal: String) {
        _maxIdleTimeInMinutes.value = newVal
    }

    fun onUsePeersChanged(usePeers: Boolean) {
        _usePeers.value = usePeers
    }
    fun onStandaloneR2cCoordinationEnabledChanged(enabled: Boolean) {
        _standaloneR2cCoordinationEnabled.value = enabled
    }
    fun onCaptureIncomingVideoChanged(enabled: Boolean) {
        _captureIncomingVideo.value = enabled
    }
    fun onPredictiveHeadEnabledChanged(enabled: Boolean) {
        _predictiveHeadEnabled.value = enabled
    }
    fun onProximityAlertSpacingFeetChanged(feet: String) {
        _proximityAlertSpacingFeet.value = feet
    }
    fun onCaltopoDomainAndPortChanged(url: String) {
        _caltopoDomainAndPort.value = url
    }

    fun onNotamEnabledChanged(enabled: Boolean) {
        _notamEnabled.value = enabled
    }

    fun onNotamRadiusNmChanged(radiusNm: String) {
        _notamRadiusNm.value = radiusNm
    }

    fun onNotamRefreshIntervalSecondsChanged(seconds: String) {
        _notamRefreshIntervalSeconds.value = seconds
    }

    fun onNotamAutoRefreshChanged(enabled: Boolean) {
        _notamAutoRefresh.value = enabled
    }

    fun onLandRestrictionsEnabledChanged(enabled: Boolean) {
        _landRestrictionsEnabled.value = enabled
    }

    fun onLandRestrictionsShowOnMapChanged(enabled: Boolean) {
        _landRestrictionsShowOnMap.value = enabled
    }

    fun onLandRestrictionsAutoRefreshChanged(enabled: Boolean) {
        _landRestrictionsAutoRefresh.value = enabled
    }

    fun onLandRestrictionsRadiusNmChanged(radiusNm: String) {
        _landRestrictionsRadiusNm.value = radiusNm
    }

    fun onExternalDisplayModeChanged(mode: ExternalDisplayMode) {
        _externalDisplayMode.value = mode
    }

    fun onExternalDisplayAutoOpenChanged(enabled: Boolean) {
        _externalDisplayAutoOpen.value = enabled
    }

    fun onExternalDisplayReturnToPhoneOnlyChanged(enabled: Boolean) {
        _externalDisplayReturnToPhoneOnly.value = enabled
    }

    fun onExternalDisplayAllowInteractionChanged(enabled: Boolean) {
        _externalDisplayAllowInteraction.value = enabled
    }

    fun onExternalDisplayContentModeChanged(mode: ExternalDisplayContentMode) {
        _externalDisplayContentMode.value = mode
    }

    fun onExternalDisplayAlertRoutingChanged(routing: ExternalDisplayAlertRouting) {
        _externalDisplayAlertRouting.value = routing
    }

    fun saveSettings() {
        val restartMediaMtx = CaltopoClient.GetCaptureVideoStreamsFlag() != _captureIncomingVideo.value
        _minDistance.value.toLongOrNull()?.let { CaltopoClient.setMinDistanceInFeet(it) }
        _newTrackDelay.value.toLongOrNull()?.let { CaltopoClient.SetNewTrackDelayInSeconds(it) }
        (_maxFlatlineToneDuration.value.toLongOrNull()
            ?: CaltopoClient.DEFAULT_MAX_FLATLINE_TONE_DURATION_SECONDS).let {
            CaltopoClient.SetMaxFlatlineToneDurationInSeconds(it)
        }
        _bridgeCheckDistanceFeet.value.toLongOrNull()?.let { CaltopoClient.SetBridgeCheckDistanceFeet(it) }
        if (CaltopoClient.GetAlarmVolumeConfigured()) {
            CaltopoClient.SetAlarmVolumePercent(_alarmVolumePercent.value)
        }
        _maxIdleTimeInMinutes.value.toLongOrNull()?.let { CaltopoClient.SetMaxIdleTimeInMinutes(it) }
        CaltopoClient.SetUsePeers(_usePeers.value)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(_standaloneR2cCoordinationEnabled.value)
        CaltopoClient.SetCaptureVideoStreamsFlag(_captureIncomingVideo.value)
        CaltopoClient.SetPredictiveHeadEnabled(_predictiveHeadEnabled.value)
        _proximityAlertSpacingFeet.value.toLongOrNull()?.let { CaltopoClient.SetProximityAlertSpacingFeet(it) }
        CaltopoClient.SetCaltopoDomainAndPort(_caltopoDomainAndPort.value)
        CaltopoClient.SetNotamEnabled(_notamEnabled.value)
        _notamRadiusNm.value.toIntOrNull()?.let { CaltopoClient.SetNotamRadiusNm(it) }
        _notamRefreshIntervalSeconds.value.toIntOrNull()?.let { CaltopoClient.SetNotamRefreshIntervalSeconds(it) }
        CaltopoClient.SetNotamAutoRefresh(_notamAutoRefresh.value)
        CaltopoClient.SetLandRestrictionsEnabled(_landRestrictionsEnabled.value)
        CaltopoClient.SetLandRestrictionsShowOnMap(_landRestrictionsShowOnMap.value)
        CaltopoClient.SetLandRestrictionsAutoRefresh(_landRestrictionsAutoRefresh.value)
        _landRestrictionsRadiusNm.value.toIntOrNull()?.let(CaltopoClient::SetLandRestrictionsRadiusNm)
        R2CApplication.getAppCtxt()?.let { context ->
            ExternalDisplayPrefs.save(
                context,
                ExternalDisplayConfig(
                    mode = _externalDisplayMode.value,
                    autoOpenOnConnect = _externalDisplayAutoOpen.value,
                    returnToPhoneOnlyLayoutOnDisconnect = _externalDisplayReturnToPhoneOnly.value,
                    allowInteraction = _externalDisplayAllowInteraction.value,
                    contentMode = _externalDisplayContentMode.value,
                    alertRouting = _externalDisplayAlertRouting.value
                )
            )
        }
        _notamStatus.value = buildNotamStatus()
        NotamCenter.requestImmediateRefresh()
        LandRestrictionCenter.settingsChanged()
        if (restartMediaMtx) {
            R2CApplication.getAppCtxt()?.let { MediaMTXService.requestRestart(it) }
        }
    }

    private fun buildNotamStatus(): String {
        val authConfigured = NotamAuthManager.isConfigured()
        val enabled = CaltopoClient.GetNotamEnabled()
        val radius = CaltopoClient.GetNotamRadiusNm()
        return when {
            !enabled -> "Disabled"
            authConfigured -> "Enabled, credentials loaded, ${radius} NM radius"
            else -> "Enabled, waiting for FAA credentials, ${radius} NM radius"
        }
    }
}
