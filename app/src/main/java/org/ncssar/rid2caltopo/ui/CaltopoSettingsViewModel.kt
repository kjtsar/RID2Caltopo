package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient

class CaltopoSettingsViewModel : ViewModel() {

    // --- Live Data for UI --- //
    private val _groupId = MutableStateFlow(CaltopoClient.GetGroupId())
    val groupId = _groupId.asStateFlow()

    private val _mapId = MutableStateFlow(CaltopoClient.GetMapId())
    val mapId = _mapId.asStateFlow()

    private val _minDistance = MutableStateFlow(CaltopoClient.GetMinDistanceInFeet().toString())
    val minDistance = _minDistance.asStateFlow()

    private val _newTrackDelay = MutableStateFlow(CaltopoClient.GetNewTrackDelayInSeconds().toString())
    val newTrackDelay = _newTrackDelay.asStateFlow()

    private val _useDirect = MutableStateFlow(CaltopoClient.GetUseDirectFlag())
    val useDirect = _useDirect.asStateFlow()

    init {
        _groupId.value = CaltopoClient.GetGroupId()
        _useDirect.value = CaltopoClient.GetUseDirectFlag()
        _newTrackDelay.value = CaltopoClient.GetNewTrackDelayInSeconds().toString()
        _minDistance.value = CaltopoClient.GetMinDistanceInFeet().toString()
        _mapId.value = CaltopoClient.GetMapId()
    }

    // --- UI Event Handlers --- //

    fun onGroupIdChanged(newGroupId: String) {
        _groupId.value = newGroupId
    }

    fun onMapIdChanged(newMapId: String) {
        _mapId.value = newMapId
    }

    fun onMinDistanceChanged(newMinDistance: String) {
        _minDistance.value = newMinDistance
    }

    fun onNewTrackDelayChanged(newDelay: String) {
        _newTrackDelay.value = newDelay
    }


    fun onUseDirectChanged(isDirect: Boolean) {
        _useDirect.value = isDirect
    }

    fun saveSettings() {
        CaltopoClient.SetGroupId(_groupId.value)
        CaltopoClient.SetMapId(_mapId.value)
        _minDistance.value.toLongOrNull()?.let { CaltopoClient.setMinDistanceInFeet(it) }
        _newTrackDelay.value.toLongOrNull()?.let { CaltopoClient.SetNewTrackDelayInSeconds(it) }
        CaltopoClient.SetUseDirect(_useDirect.value)
    }
}
