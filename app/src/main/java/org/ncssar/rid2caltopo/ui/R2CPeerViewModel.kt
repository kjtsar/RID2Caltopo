
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
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.data.SimpleTimer

/**
 * ViewModel for a single remote R2CPeer client.
 */
class R2CPeerViewModel(val r2cPeer: R2CPeer) : ViewModel(), R2CPeer.remoteUpdateListener, CtDroneSpec.DroneSpecsChangedListener {

    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()

    // Simple StateFlow for uptime, can be enhanced later
    private val _remoteUptime = MutableStateFlow("00:00:00")
    val remoteUptime: StateFlow<String> = _remoteUptime.asStateFlow()
    private val remoteTimer : SimpleTimer = SimpleTimer()

    private val _remoteAppVersion = MutableStateFlow("")
    val remoteAppVersion: StateFlow<String> = _remoteAppVersion.asStateFlow()
    private val uptimePoll : DelayedExec = DelayedExec()
    private val _remoteCtRtt = MutableStateFlow("0.000 sec")
    val remoteCtRtt : StateFlow<String> = _remoteCtRtt.asStateFlow()
    private val _remoteMapId = MutableStateFlow("")
    private val _remoteGroupId = MutableStateFlow("")
    val remoteMapId : StateFlow<String> = _remoteMapId.asStateFlow()
    val remoteGroupId : StateFlow<String> = _remoteGroupId.asStateFlow()


    override fun onRemoteAppConfig(version: String, mapId: String, groupId: String) {
        _remoteAppVersion.value = version
        _remoteMapId.value = mapId
        _remoteGroupId.value = groupId
    }

    override fun onRemoteStartTime(startTimeInMsec : Long) {
        remoteTimer.setStartTimeInMsec(startTimeInMsec)
        _remoteUptime.value = remoteTimer.durationAsString()
    }

    init {
        r2cPeer.setRemoteDroneSpecMonitor(this)
        r2cPeer.setRemoteUpdateListener(this)
        loadRemoteDrones()
        uptimePoll.start(this::uptimePollFun, 1000, 1000)
    }

    private fun loadRemoteDrones() {
        _drones.value = r2cPeer.remoteDroneSpecs
    }

    fun updateMappedId(drone: CtDroneSpec, newMappedId: String) {
        CTDebug("R2CPeerViewModel", "updateMappedId(${drone.remoteId}, ${newMappedId})")
        val droneSpec: CtDroneSpec ?= CaltopoClient.GetDroneSpec(drone.remoteId)
        if (null != droneSpec) {
            droneSpec.setMappedId(newMappedId)
        } else {
            r2cPeer.updateMappedId(drone, newMappedId)
        }
    }

    override fun onCleared() {
        r2cPeer.setRemoteDroneSpecMonitor(null)
        r2cPeer.setRemoteUpdateListener(null)
        super.onCleared()
    }

    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        for (ds in droneSpecs) {
            CTDebug("R2CPeerViewModel", "onDroneSpecsChanged(): $ds")
        }

        _drones.value = droneSpecs
        _remoteCtRtt.value = r2cPeer.getRemoteCtRttString()
        _remoteUptime.value = remoteTimer.durationAsString()
        if (droneSpecs.isEmpty()) {
            if (!uptimePoll.isRunning) {
                uptimePoll.start(this::uptimePollFun, 1000, 1000)
            }
        } else if (uptimePoll.isRunning) {
            uptimePoll.stop()
        }
    }

    fun uptimePollFun() {
        _remoteUptime.value = remoteTimer.durationAsString()
        _remoteCtRtt.value = r2cPeer.getRemoteCtRttString()
    }
}

/**
 * Factory to create R2CPeerViewModel instances, since it requires a constructor parameter.
 */
class R2CPeerViewModelFactory(private val r2cPeer: R2CPeer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CPeerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CPeerViewModel(r2cPeer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
