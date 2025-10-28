package org.ncssar.rid2caltopo.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CtDroneSpec

class R2CViewModel : ViewModel(), CaltopoClient.CtDroneSpecArrayMonitor {

    private val _aircrafts = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    private val _appUptime = MutableStateFlow(ScanningService.UpTime())
    val aircrafts: StateFlow<List<CtDroneSpec>> = _aircrafts.asStateFlow()
    val appUpTime = _appUptime.asStateFlow()

    init {
        // Register this ViewModel to listen for changes from CaltopoClient
        CaltopoClient.SetDroneSpecMonitor(this)
        // Load the initial list of aircraft
        loadAircrafts()
    }

    private fun loadAircrafts() {
        CTDebug("R2CViewModel", "loadAircrafts()")
        _aircrafts.value = CaltopoClient.GetSortedCurrentDroneSpecArray()
        _appUptime.value = ScanningService.UpTime()
    }

    // This callback will be triggered by CaltopoClient whenever the data changes.
    override fun droneSpecArrayChanged() {
        loadAircrafts()
    }

    // Clean up the listener when the ViewModel is no longer in use.
    override fun onCleared() {
        CaltopoClient.SetDroneSpecMonitor(null)
        super.onCleared()
    }
}
