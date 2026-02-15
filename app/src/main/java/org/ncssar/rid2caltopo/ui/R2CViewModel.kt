/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.app.R2CActivity
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoCredentials
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.data.R2CPeer
import org.ncssar.rid2caltopo.data.SimpleTimer

enum class ActiveScreen {
    MAIN,
    STREAMS,
    SETTINGS,
    SCANNER
}
sealed class CaltopoConnectionState(val displayName: String) {
    override fun toString(): String = displayName
    // Standard operating mode: local logging only
    object StandAlone : CaltopoConnectionState("StandAlone")

    object NoNetwork : CaltopoConnectionState("NoNetwork")

    // Transient state: UI shows "Verifying Team Access..." with an animation
    object CheckingCredentials : CaltopoConnectionState("CheckingCredentials")

    // Credentials verified, we have the hierarchy, waiting for user to pick a map
    object CredentialsVerified : CaltopoConnectionState("CredentialsVerified")

    object CredentialsLoaded : CaltopoConnectionState("CredentialsLoaded")

    // Transition state: Handing the MapNode to your Java openMap() logic
    object Connecting : CaltopoConnectionState("Connecting")

    // Active state: Map is UP
    data class MapSelected(val map: CaltopoNode.MapNode) : CaltopoConnectionState("MapSelected")
}

sealed class UIEvent(val displayName: String) {
    override fun toString(): String = displayName
    // User Actions
    object HeaderClicked : UIEvent("HeaderClicked")
    object ConnectionRequested: UIEvent("ConnectionRequested")
    object DismissRequested : UIEvent("DismissRequested")
    object DisconnectRequested : UIEvent("DisconnectRequested")
    object SwitchMapRequested: UIEvent("SwitchMapRequested")
    object ConfigFileLoaded: UIEvent("ConfigFileLoaded")
    object NotAbleToReadConfigFile: UIEvent("NotAbleToReadConfigFile")
    data class MapSelected(val map: CaltopoNode.MapNode) : UIEvent("MapSelected")

    // System/Network Notifications
    data class ConnectionStatusChanged(val mapStatus: CaltopoMap.MapStatusListener.mapStatus) : UIEvent("ConnectionStatusChanged")
}

sealed class OverlayState(val displayName: String) {
    override fun toString(): String = displayName
    object None : OverlayState("None")
    object ConnectionSetup : OverlayState("ConnectionSetup") // Formerly showStandAlonePopup
    object RequestConfigFile : OverlayState("RequestConfigFile")
    object Connecting: OverlayState("Connecting")      // spinning icon.
    object MapBrowser : OverlayState("MapBrowser")     // Formerly showMapBrowser
    object Management : OverlayState("Management")     // Formerly showConnectedOptions
    data class Error(val message: String) : OverlayState("Error") // Added for the error dialog
}

class R2CViewModel(val uptimeTimer: SimpleTimer) : ViewModel(),
    CtDroneSpec.DroneSpecsChangedListener, CaltopoMap.MapStatusListener {
    private val tag = "R2CViewModel"
    private val _drones = MutableStateFlow<List<CtDroneSpec>>(emptyList())
    private val _appUptime = MutableStateFlow(ScanningService.UpTime())
    private val delayedUptimePoll : DelayedExec = DelayedExec()
    val drones: StateFlow<List<CtDroneSpec>> = _drones.asStateFlow()
    val appUpTime = _appUptime.asStateFlow()
    private val _hostname = MutableStateFlow("")
    val hostname = _hostname.asStateFlow()
    private val _activeScreen = MutableStateFlow(ActiveScreen.MAIN)
    val activeScreen : StateFlow<ActiveScreen> = _activeScreen.asStateFlow()

    var mapHierarchy by mutableStateOf<List<CaltopoNode>?>(null)
        private set

    val hasCredentials: Boolean
        get() = CaltopoCredentials.sniffTest(CaltopoClient.GetCaltopoCredentials())

    val hasNetwork: Boolean
        get() = !R2CPeer.GetMyIpAddress(false).isEmpty()

    // Map and U/I State management
    var connectionState by mutableStateOf<CaltopoConnectionState>(CaltopoConnectionState.StandAlone)
        private set
    var overlay by mutableStateOf<OverlayState>(OverlayState.None)
        private set

    init {
        // Assuming CaltopoMap.registerListener is static now
        CaltopoMap.AddMapStatusListener(this)
        overlay = OverlayState.None
        connectionState = CaltopoConnectionState.StandAlone
    }
    fun onUIEvent(uiEvent: UIEvent) {
        val oldState = "      PRE: Overlay='${overlay}' ConnectionState: '${connectionState}'"

        when (uiEvent) {
            is UIEvent.HeaderClicked -> {
                overlay = when (connectionState) {
                    is CaltopoConnectionState.StandAlone -> OverlayState.ConnectionSetup
                    is CaltopoConnectionState.NoNetwork -> {
                        if (R2CPeer.GetMyIpAddress(false).isEmpty()) {
                            OverlayState.ConnectionSetup
                        } else {
                            OverlayState.None
                        }
                    }
                    is CaltopoConnectionState.CredentialsLoaded -> OverlayState.MapBrowser
                    is CaltopoConnectionState.CredentialsVerified -> OverlayState.MapBrowser
                    is CaltopoConnectionState.MapSelected -> OverlayState.Management
                    else -> overlay // do nothing if busy
                }
            }
            is UIEvent.NotAbleToReadConfigFile -> {
                CaltopoClient.ShowToast("Not able to read Config File")
                overlay = OverlayState.None
                connectionState = CaltopoConnectionState.StandAlone
            }
            is UIEvent.ConfigFileLoaded -> {
                if (overlay is OverlayState.None) {
                    // then just loaded a configfile independent of our fancy widget.
                } else {
                    if (this.hasCredentials) {
                        CTDebug(
                            tag,
                            "onUIEvent(${uiEvent}): have credentials, fetching team info..."
                        )
                        overlay = OverlayState.Connecting
                        CaltopoMap.Init() // Use credentials to fetch/update the CaltopoNode List
                    } else {
                        CTDebug(
                            tag,
                            "onUIEvent(${uiEvent}): No credentials loaded requesting credentials..."
                        )
                        overlay = OverlayState.RequestConfigFile
                    }
                }
            }
            is UIEvent.ConnectionRequested -> {
                if (R2CPeer.GetMyIpAddress(false).isEmpty()) {
                    connectionState = CaltopoConnectionState.NoNetwork
                } else {
                    if (this.hasCredentials) {
                        overlay = OverlayState.Connecting
                        CaltopoMap.Init() // Use credentials to fetch/update the CaltopoNode List
                    } else {
                        // We don't have creds, go get the file
                        overlay = OverlayState.RequestConfigFile
                    }
                }
            }

            is UIEvent.SwitchMapRequested -> {
                overlay = OverlayState.MapBrowser // Close the management dialog and open the browser
            }

            is UIEvent.DisconnectRequested -> {
                overlay = OverlayState.None
                CaltopoMap.OpenMap(null) // Java call to drop connection
                // Note: connectionState will update via the MapStatusListener callback
            }

            is UIEvent.MapSelected -> {
                CaltopoMap.OpenMap(uiEvent.map)
            }

            is UIEvent.ConnectionStatusChanged -> {
                // Directly map the Java Enum to our Sealed Class state
                val status = uiEvent.mapStatus
                CTDebug(tag, "onUIEvent(${uiEvent}): ConnectionStatus: '${status.name}'")
                connectionState = when (status) {
                    CaltopoMap.MapStatusListener.mapStatus.credentialsVerified -> {
                        mapHierarchy = CaltopoMap.GetSessionNodeMap()
                        overlay = OverlayState.MapBrowser
                        CaltopoConnectionState.CredentialsVerified
                    }
                    CaltopoMap.MapStatusListener.mapStatus.up -> {
                        overlay = OverlayState.None
                        CaltopoConnectionState.MapSelected(CaltopoMap.GetMapNode())
                    }
                    CaltopoMap.MapStatusListener.mapStatus.down -> {
                        overlay = OverlayState.None
                        CaltopoConnectionState.StandAlone
                    }
                    CaltopoMap.MapStatusListener.mapStatus.connecting -> {
                        overlay = OverlayState.Connecting
                        CaltopoConnectionState.Connecting
                    }
                }
            }

            is UIEvent.DismissRequested -> {
                connectionState = CaltopoConnectionState.StandAlone
                overlay = OverlayState.None
            }
        }

        val newState = "     POST: Overlay='${overlay}' ConnectionState: '${connectionState}'"
        CTDebug(tag, "onUIEvent(${uiEvent})\n$oldState\n$newState")
    }

    override fun mapStatusUpdate(status: CaltopoMap.MapStatusListener.mapStatus, mapNode: CaltopoNode.MapNode?, optErrmsg: String?) {
        viewModelScope.launch(Dispatchers.Main) {
            onUIEvent(UIEvent.ConnectionStatusChanged(status))
        }
    }

    fun showStreams() {
        _activeScreen.value = ActiveScreen.STREAMS
    }
    fun showMain() {
        _activeScreen.value = ActiveScreen.MAIN
    }
    fun showSettings() {
        _activeScreen.value = ActiveScreen.SETTINGS
    }
    fun showScanner() {
        _activeScreen.value = ActiveScreen.SCANNER
    }

    init {
        delayedUptimePoll.start(this::uptimePoll, 1000, 1000)
        CaltopoMap.AddMapStatusListener(this)
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
                if (_hostname.value.isEmpty() || _hostname.value != newDeviceName) {
            _hostname.value = newDeviceName
        }
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
