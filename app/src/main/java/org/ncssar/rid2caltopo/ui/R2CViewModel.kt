/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.data.SimpleTimer

enum class ActiveScreen {
    MAIN,
    STREAMS,
    SETTINGS,
    SCANNER,
    MAPID_DIALOG,
}
class R2CViewModel(val uptimeTimer: SimpleTimer) : ViewModel(),
    CtDroneSpec.DroneSpecsChangedListener, CaltopoMap.MapStatusListener {
    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    private val _appUptime = MutableStateFlow(ScanningService.UpTime())
    private val delayedUptimePoll : DelayedExec = DelayedExec()
    private val _mapStatus = MutableStateFlow(CaltopoMap.MapStatusListener.mapStatus.down)

    private val _mapId = MutableStateFlow("")
    private val _groupId = MutableStateFlow("")
    val mapId = _mapId.asStateFlow()
    val groupId = _groupId.asStateFlow()
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()
    val appUpTime = _appUptime.asStateFlow()
    val mapStatus = _mapStatus.asStateFlow()
    private val _hostname = MutableStateFlow("")
    val hostname = _hostname.asStateFlow()
    private val _activeScreen = MutableStateFlow(ActiveScreen.MAIN)
    val activeScreen : StateFlow<ActiveScreen> = _activeScreen.asStateFlow()

    fun showStreams() {
        _activeScreen.value = ActiveScreen.STREAMS
    }
    fun showMain() {
        _activeScreen.value = ActiveScreen.MAIN
    }
    fun showSettings() {
        _activeScreen.value = ActiveScreen.SETTINGS
    }
    fun showMapidDialog() {
        _activeScreen.value = ActiveScreen.MAPID_DIALOG
    }
    fun showScanner() {
        _activeScreen.value = ActiveScreen.SCANNER
    }

    init {
        delayedUptimePoll.start(this::uptimePoll, 1000, 1000)
        CaltopoMap.SetMapStatusListener(this)
    }

    override fun mapStatusUpdate(map: CaltopoMap, mapStatus: CaltopoMap.MapStatusListener.mapStatus) {
        _mapStatus.value = mapStatus
    }

    // Clean up the listener when the ViewModel is no longer in use.
    override fun onCleared() {
        super.onCleared()
        CaltopoMap.RemoveMapStatusListener(this)
        delayedUptimePoll.stop()
    }

    fun updateMappedId(drone: CtDroneSpec, newMappedId: String) {
        drone.setMappedId(newMappedId)
    }

    fun housekeeping() {
        _appUptime.value = uptimeTimer.durationAsString()
        val newDeviceName = R2CActivity.MyDeviceName
        val newMapId: String = CaltopoClient.GetMapId()
        val newGroupId: String = CaltopoClient.GetGroupId()
        if (_hostname.value.isEmpty() || _hostname.value != newDeviceName) {
            _hostname.value = newDeviceName
        }
        if (_mapId.value != newMapId) _mapId.value = newMapId
        if (_groupId.value != newGroupId) _groupId.value = newGroupId
    }

    /* We only need the uptimePoll() before there are dronespecs.
     * Once there are dronespecs, we will receive frequent updates.
     */
    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        _drones.value = droneSpecs
        housekeeping()
        if (droneSpecs.isEmpty()) {
            if (!delayedUptimePoll.isRunning) {
                delayedUptimePoll.start(this::uptimePoll, 1000, 1000)
            }
        } else if (delayedUptimePoll.isRunning) {
            delayedUptimePoll.stop()
        }
    }

    fun uptimePoll() {
        housekeeping()
    }
}

class R2CViewModelFactory(private val uptimeTimer: SimpleTimer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CViewModel(uptimeTimer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
