package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.R2CRest
import java.util.ArrayList

/**
 * ViewModel for a single remote R2CRest client.
 */
class R2CRestViewModel(val r2cClient: R2CRest) : ViewModel(), CtDroneSpec.DroneSpecsChangedListener {

    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()

    // Simple StateFlow for uptime, can be enhanced later
    private val _remoteUptime = MutableStateFlow("00:00:00")
    val remoteUptime: StateFlow<String> = _remoteUptime.asStateFlow()

    init {
        r2cClient.setRemoteDroneSpecMonitor(this)
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
