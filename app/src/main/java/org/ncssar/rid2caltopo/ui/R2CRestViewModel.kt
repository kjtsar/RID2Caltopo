package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2CRest
import org.ncssar.rid2caltopo.data.SimpleTimer

/**
 * ViewModel for a single remote R2CRest client.
 */
class R2CRestViewModel(val r2cClient: R2CRest) : ViewModel(), R2CRest.remoteUpdateListener, CtDroneSpec.DroneSpecsChangedListener {

    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()

    // Simple StateFlow for uptime, can be enhanced later
    private val _remoteUptime = MutableStateFlow("00:00:00")
    val remoteUptime: StateFlow<String> = _remoteUptime.asStateFlow()
    private val remoteTimer : SimpleTimer = SimpleTimer()

    private val _appVersion = MutableStateFlow("")
    val appVersion: StateFlow<String> = _appVersion.asStateFlow()

    private val _remoteCtRtt = MutableStateFlow("")
    val remoteCtRtt : StateFlow<String> = _remoteCtRtt.asStateFlow()

    override fun onRemoteAppVersion(version: String) {
        _appVersion.value = version
    }

    override fun onRemoteStartTime(startTimeInMsec : Long) {
        remoteTimer.setStartTimeInMsec(startTimeInMsec)
    }

    init {
        r2cClient.setRemoteDroneSpecMonitor(this)
        r2cClient.setRemoteUpdateListener(this)
        loadRemoteDrones()
    }

    private fun loadRemoteDrones() {
        _drones.value = r2cClient.remoteDroneSpecs
    }

    fun updateMappedId(drone: CtDroneSpec, newMappedId: String) {
        // This logic will need to be a request to the remote client
        r2cClient.updateMappedId(drone, newMappedId)
    }

    fun onDroneSpecsChanged() {
        loadRemoteDrones()
    }

    override fun onCleared() {
        r2cClient.setRemoteDroneSpecMonitor(null)
        super.onCleared()
    }

    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        _drones.value = droneSpecs
        _remoteCtRtt.value = r2cClient.getRemoteCtRttString()
    }
}

/**
 * Factory to create R2CRestViewModel instances, since it requires a constructor parameter.
 */
class R2CRestViewModelFactory(private val r2cRest: R2CRest) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CRestViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CRestViewModel(r2cRest) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
