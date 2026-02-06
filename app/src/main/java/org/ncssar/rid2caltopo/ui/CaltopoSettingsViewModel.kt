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
import org.ncssar.rid2caltopo.data.CaltopoClient
class CaltopoSettingsViewModel : ViewModel(), CaltopoClient.ClientSettingsListener {

    // --- Live Data for UI --- //

    private val _minDistance = MutableStateFlow(CaltopoClient.GetMinDistanceInFeet().toString())
    val minDistance = _minDistance.asStateFlow()

    private val _newTrackDelay = MutableStateFlow(CaltopoClient.GetNewTrackDelayInSeconds().toString())
    val newTrackDelay = _newTrackDelay.asStateFlow()

    private val _goLiveFlag = MutableStateFlow(CaltopoClient.GetGoLiveFlag())
    val goLiveFlag = _goLiveFlag.asStateFlow()

    private val _usePeers = MutableStateFlow(CaltopoClient.GetUsePeersFlag())
    val usePeers = _usePeers.asStateFlow()

    private val _maxIdleTimeInMinutes = MutableStateFlow(CaltopoClient.GetMaxIdleTimeInMinutes().toString())
    val maxIdleTimeInMinutes = _maxIdleTimeInMinutes.asStateFlow()

    private val _incident = MutableStateFlow( CaltopoClient.GetIncident())
    val incident = _incident.asStateFlow()

    private val _opPeriod = MutableStateFlow( CaltopoClient.GetOpPeriod())
    val opPeriod = _opPeriod.asStateFlow()

    private val _caltopoDomainAndPort = MutableStateFlow( CaltopoClient.GetCaltopoDomainAndPort())
    val caltopoUrl = _caltopoDomainAndPort.asStateFlow()



    init {
        CaltopoClient.SetSettingsListener(this)
        settingsChanged() // load initial values.
    }

    override fun settingsChanged() {
        _goLiveFlag.value = CaltopoClient.GetGoLiveFlag()
        _usePeers.value = CaltopoClient.GetUsePeersFlag()
        _newTrackDelay.value = CaltopoClient.GetNewTrackDelayInSeconds().toString()
        _minDistance.value = CaltopoClient.GetMinDistanceInFeet().toString()
        _maxIdleTimeInMinutes.value = CaltopoClient.GetMaxIdleTimeInMinutes().toString()
    }

    // --- UI Event Handlers --- //

    fun onMinDistanceChanged(newMinDistance: String) {
        _minDistance.value = newMinDistance
    }

    fun onNewTrackDelayChanged(newDelay: String) {
        _newTrackDelay.value = newDelay
    }

    fun onMaxIdleTimeInMinutesChanged(newVal: String) {
        _maxIdleTimeInMinutes.value = newVal
    }

    fun onSendLiveChanged(goLiveFlag: Boolean) {
        _goLiveFlag.value = goLiveFlag
    }
    fun onUsePeersChanged(usePeers: Boolean) {
        _usePeers.value = usePeers
    }
    fun onCaltopoDomainAndPortChanged(url: String) {
        _caltopoDomainAndPort.value = url
    }
    fun saveSettings() {
        _minDistance.value.toLongOrNull()?.let { CaltopoClient.setMinDistanceInFeet(it) }
        _newTrackDelay.value.toLongOrNull()?.let { CaltopoClient.SetNewTrackDelayInSeconds(it) }
        _maxIdleTimeInMinutes.value.toLongOrNull()?.let { CaltopoClient.SetMaxIdleTimeInMinutes(it) }
        CaltopoClient.SetGoLiveFlag(_goLiveFlag.value)
        CaltopoClient.SetUsePeers(_usePeers.value)
        CaltopoClient.SetCaltopoDomainAndPort(_caltopoDomainAndPort.value)
    }
}
