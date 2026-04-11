
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
import org.ncssar.rid2caltopo.data.R2CMqttManager

/**
 * ViewModel for a single remote MQTT peer.
 */
class R2CPeerViewModel(val peerState: R2CMqttManager.PeerState) : ViewModel() {

    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()

    private val _remoteUptime = MutableStateFlow("00:00:00")
    val remoteUptime: StateFlow<String> = _remoteUptime.asStateFlow()

    private val _remoteAppVersion = MutableStateFlow("")
    val remoteAppVersion: StateFlow<String> = _remoteAppVersion.asStateFlow()

    private val _remoteCtRtt = MutableStateFlow("0.000 sec")
    val remoteCtRtt: StateFlow<String> = _remoteCtRtt.asStateFlow()

    private val uptimePoll: DelayedExec = DelayedExec()

    init {
        uptimePoll.start(this::pollFun, 1000, 1000)
    }

    private fun pollFun() {
        _drones.value = ArrayList(peerState.ownedDrones)
        _remoteUptime.value = peerState.uptime.durationAsString()
        val rttMs = peerState.caltopoRttMs
        _remoteCtRtt.value = String.format("%.3f sec", rttMs / 1000.0)
    }

    fun updateMappedId(drone: CtDroneSpec, newMappedId: String) {
        CTDebug("R2CPeerViewModel", "updateMappedId(${drone.remoteId}, $newMappedId)")
        val droneSpec: CtDroneSpec? = CaltopoClient.GetDroneSpec(drone.remoteId)
        droneSpec?.setMappedId(newMappedId)
    }

    override fun onCleared() {
        uptimePoll.stop()
        super.onCleared()
    }
}

/**
 * Factory to create R2CPeerViewModel instances, since it requires a constructor parameter.
 */
class R2CPeerViewModelFactory(private val peerState: R2CMqttManager.PeerState) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CPeerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CPeerViewModel(peerState) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
