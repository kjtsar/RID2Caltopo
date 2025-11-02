package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.SimpleTimer


class R2CViewModel(hostName: String, uptimeTimer: SimpleTimer) : ViewModel(), CtDroneSpec.DroneSpecsChangedListener {
    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    private val _appUptime = MutableStateFlow(ScanningService.UpTime())
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()
    val appUpTime = _appUptime.asStateFlow()
    val uptimeTimer = uptimeTimer
    val hostName = hostName

    init {
        // Register this ViewModel to listen for changes from CaltopoClient
        // Load the initial list of drones
    }

    // Clean up the listener when the ViewModel is no longer in use.
    override fun onCleared() {
        super.onCleared()
    }

    fun updateMappedId(drone: CtDroneSpec, newMappedId: String) {
        drone.setMappedId(newMappedId)
    }

    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        _drones.value = droneSpecs
        _appUptime.value = uptimeTimer.durationAsString()
    }
}

class R2CViewModelFactory(private val hostname: String, private val uptimeTimer: SimpleTimer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CViewModel(hostname, uptimeTimer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
