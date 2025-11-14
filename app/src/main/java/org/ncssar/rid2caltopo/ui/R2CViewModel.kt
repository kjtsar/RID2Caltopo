package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CaltopoClientMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.data.SimpleTimer


class R2CViewModel(val mapIdIn : String, val groupIdIn : String, val uptimeTimer: SimpleTimer) : ViewModel(),
    CtDroneSpec.DroneSpecsChangedListener, CaltopoClientMap.MapStatusListener {
    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    private val _appUptime = MutableStateFlow(ScanningService.UpTime())
    private val uptimePoll : DelayedExec = DelayedExec()
    private val _mapIsUp = MutableStateFlow(false)

    private val _mapId = MutableStateFlow(mapIdIn)
    private val _groupId = MutableStateFlow(groupIdIn)
    val mapId = _mapId.asStateFlow()
    val groupId = _groupId.asStateFlow()
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()
    val appUpTime = _appUptime.asStateFlow()
    val mapIsUp = _mapIsUp.asStateFlow()
    private val _hostname = MutableStateFlow(R2CActivity.MyDeviceName)
    val hostname = _hostname.asStateFlow()


    init {
        uptimePoll.start(this::uptimePoll, 1000, 1000)
        CaltopoClientMap.SetMapStatusListener(this);
    }

    override fun mapStatusUpdate(map: CaltopoClientMap, mapIsUpFlag: Boolean) {
        _mapIsUp.value = mapIsUpFlag;
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
        if (droneSpecs.isEmpty()) {
            if (!uptimePoll.isRunning) {
                uptimePoll.start(this::uptimePoll, 1000, 1000)
            }
        } else if (uptimePoll.isRunning) {
            uptimePoll.stop()
        }
    }
    fun uptimePoll() {
        _appUptime.value = uptimeTimer.durationAsString()
        _hostname.value = R2CActivity.MyDeviceName
    }
}

class R2CViewModelFactory(private val mapId : String, private val groupId : String, private val uptimeTimer: SimpleTimer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(R2CViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return R2CViewModel(mapId, groupId, uptimeTimer) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
