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
import org.ncssar.rid2caltopo.data.R2CMqttManager
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
    private val _pendingDroneConfirmation = MutableStateFlow<DroneSpecConfirmationUiState?>(null)
    val pendingDroneConfirmation = _pendingDroneConfirmation.asStateFlow()
    private val _activeScreen = MutableStateFlow(ActiveScreen.MAIN)
    val activeScreen : StateFlow<ActiveScreen> = _activeScreen.asStateFlow()
    private val promptedFlightKeys = linkedSetOf<String>()
    // remoteIds already confirmed this session — prevents a re-prompt when
    // startMsecTimestamp changes as a new track segment initialises (which
    // produces a different flightKey from the one that was just added to
    // promptedFlightKeys, causing a duplicate confirmation dialog).
    private val confirmedRemoteIds = linkedSetOf<String>()

    var mapHierarchy by mutableStateOf<List<CaltopoNode>?>(null)
        private set

    val hasCredentials: Boolean
        get() = CaltopoCredentials.sniffTest(CaltopoClient.GetCaltopoCredentials())

    val hasNetwork: Boolean
        get() = !R2CMqttManager.GetMyIpAddress().isEmpty()

    // Map and U/I State management
    var connectionState by mutableStateOf<CaltopoConnectionState>(CaltopoConnectionState.StandAlone)
        private set
    var overlay by mutableStateOf<OverlayState>(OverlayState.None)
        private set

    init {
        CaltopoMap.AddMapStatusListener(this)
        overlay = OverlayState.None
        connectionState = CaltopoConnectionState.StandAlone
        delayedUptimePoll.start(this::uptimePoll, 1000, 1000)
    }
    fun onUIEvent(uiEvent: UIEvent) {
        val oldState = "      PRE: Overlay='${overlay}' ConnectionState: '${connectionState}'"

        when (uiEvent) {
            is UIEvent.HeaderClicked -> {
                overlay = when (connectionState) {
                    is CaltopoConnectionState.StandAlone -> OverlayState.ConnectionSetup
                    is CaltopoConnectionState.NoNetwork -> {
                        if (R2CMqttManager.GetMyIpAddress().isEmpty()) {
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
                if (R2CMqttManager.GetMyIpAddress().isEmpty()) {
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
        CTDebug(tag, "showStreams(): ${_activeScreen.value} -> ${ActiveScreen.STREAMS}")
        _activeScreen.value = ActiveScreen.STREAMS
    }
    fun showMain() {
        CTDebug(tag, "showMain(): ${_activeScreen.value} -> ${ActiveScreen.MAIN}")
        _activeScreen.value = ActiveScreen.MAIN
    }
    fun showSettings() {
        CTDebug(tag, "showSettings(): ${_activeScreen.value} -> ${ActiveScreen.SETTINGS}")
        _activeScreen.value = ActiveScreen.SETTINGS
    }
    fun showScanner() {
        CTDebug(tag, "showScanner(): ${_activeScreen.value} -> ${ActiveScreen.SCANNER}")
        _activeScreen.value = ActiveScreen.SCANNER
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

    fun updatePendingDroneConfirmation(
        organization: String? = null,
        pilotCallsign: String? = null,
        droneDescription: String? = null
    ) {
        val current = _pendingDroneConfirmation.value ?: return
        _pendingDroneConfirmation.value = current.copy(
            organization = organization ?: current.organization,
            pilotCallsign = pilotCallsign ?: current.pilotCallsign,
            droneDescription = droneDescription ?: current.droneDescription
        )
    }

    /**
     * The operator doesn't recognise this drone. Dismiss the panel without saving a dronespec.
     * The flight key was already added to [promptedFlightKeys] when the dialog was first shown,
     * so the panel will not reappear for the remainder of this flight.
     */
    fun dismissPendingDroneConfirmation() {
        _pendingDroneConfirmation.value?.remoteId?.let { confirmedRemoteIds.add(it) }
        _pendingDroneConfirmation.value = null
    }

    fun savePendingDroneConfirmation() {
        val current = _pendingDroneConfirmation.value ?: return
        val remoteId = current.remoteId.trim()
        val organization = current.organization.trim()
        val callsign = current.pilotCallsign.trim()
        val droneDescription = current.droneDescription.trim()
        if (remoteId.isEmpty() || organization.isEmpty() || callsign.isEmpty() || droneDescription.isEmpty()) {
            return
        }
        confirmedRemoteIds.add(remoteId)
        val existingMappedId = CaltopoClient.GetDroneSpec(remoteId)?.mappedId?.trim().orEmpty()
        val mappedId = if (existingMappedId.isNotEmpty() && existingMappedId != remoteId) {
            // Preserve an explicit stream-to-drone mapping made via long-press instead of
            // regenerating the mappedId from the confirmation fields.
            existingMappedId
        } else {
            CtDroneSpec.BuildMappedId(callsign, droneDescription, remoteId)
        }
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            organization,
            droneDescription,
            callsign,
            mappedId
        )
        _pendingDroneConfirmation.value = null
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
        droneSpecs.forEach { drone ->
            if (hasKnownDroneSpec(drone)) {
                confirmedRemoteIds.add(drone.remoteId)
            }
        }
        // Prune stale flight keys using ownership-agnostic keys so that a brief
        // ownership flip (MQTT arbitration) never clears a just-confirmed entry.
        val activeFlightKeys = droneSpecs.mapNotNullTo(linkedSetOf()) { drone ->
            anyPeerFlightKey(drone)
        }
        promptedFlightKeys.retainAll(activeFlightKeys)
        val pendingRemoteId = _pendingDroneConfirmation.value?.remoteId
        val pendingStillActive = droneSpecs.any { drone ->
            pendingRemoteId == drone.remoteId && currentFlightKey(drone) != null
        }
        if (!pendingStillActive) {
            if (pendingRemoteId != null) {
                CTDebug(tag, "Clearing pending confirmation for $pendingRemoteId: no longer active/eligible")
            }
            _pendingDroneConfirmation.value = null
        }
        if (_pendingDroneConfirmation.value == null) {
            val unknownActiveDrones = droneSpecs.filter { drone ->
                currentFlightKey(drone) != null && !hasKnownDroneSpec(drone)
            }
            val droneToConfirm = droneSpecs.firstOrNull { drone ->
                val flightKey = currentFlightKey(drone)
                // A drone is eligible for confirmation if it has a valid flight key,
                // its flightKey hasn't been prompted yet, AND its remoteId hasn't already
                // been confirmed/dismissed this session (guards against a re-prompt when
                // startMsecTimestamp changes mid-initialisation and creates a new flightKey).
                flightKey != null &&
                    !hasKnownDroneSpec(drone) &&
                    flightKey !in promptedFlightKeys &&
                    drone.remoteId !in confirmedRemoteIds
            }
            if (droneToConfirm != null) {
                val flightKey = currentFlightKey(droneToConfirm)
                if (flightKey != null) {
                    CTDebug(
                        tag,
                        "Queueing confirmation for ${droneToConfirm.remoteId}: " +
                            "flightKey=$flightKey mappedId=${droneToConfirm.mappedId} " +
                            "org='${droneToConfirm.org}' model='${droneToConfirm.model}' owner='${droneToConfirm.owner}'"
                    )
                    promptedFlightKeys.add(flightKey)
                    _pendingDroneConfirmation.value = buildConfirmationState(droneToConfirm)
                    if (_activeScreen.value == ActiveScreen.STREAMS) {
                        CaltopoClient.ShowToast("New drone needs confirmation. Returning to main screen.")
                        showMain()
                    }
                }
            } else if (unknownActiveDrones.isNotEmpty()) {
                unknownActiveDrones.forEach { drone ->
                    val flightKey = currentFlightKey(drone)
                    CTDebug(
                        tag,
                        "Skipping confirmation for ${drone.remoteId}: " +
                            "flightKey=$flightKey prompted=${flightKey != null && flightKey in promptedFlightKeys} " +
                            "confirmed=${drone.remoteId in confirmedRemoteIds} " +
                            "known=${hasKnownDroneSpec(drone)} mappedId=${drone.mappedId} " +
                            "org='${drone.org}' model='${drone.model}' owner='${drone.owner}'"
                    )
                }
            }
        }
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

    /**
     * A flight key that gates whether THIS device should prompt for confirmation.
     * Confirmation is an operator-facing workflow, so it should appear as soon as
     * this app sees a new active flight, regardless of which peer currently owns
     * CalTopo publishing for that drone.
     */
    private fun currentFlightKey(drone: CtDroneSpec): String? {
        val startMsec = drone.startMsecTimestamp
        if (!drone.isActive || startMsec <= 0L) return null
        return "${drone.remoteId}:$startMsec"
    }

    /**
     * A flight key used only for pruning [promptedFlightKeys].
     * Ignores ownership so that a brief MQTT arbitration ownership flip does not
     * evict an already-confirmed flight key and trigger a duplicate prompt.
     */
    private fun anyPeerFlightKey(drone: CtDroneSpec): String? {
        val startMsec = drone.startMsecTimestamp
        if (!drone.isActive || startMsec <= 0L) return null
        return "${drone.remoteId}:$startMsec"
    }

    private fun buildConfirmationState(drone: CtDroneSpec): DroneSpecConfirmationUiState {
        return DroneSpecConfirmationLogic.buildInitialState(
            drone = drone,
            defaultOrganization = CaltopoClient.GetHomeOrgName()
        )
    }

    private fun hasKnownDroneSpec(drone: CtDroneSpec): Boolean {
        val cached = CaltopoClient.GetDroneSpec(drone.remoteId)
        return hasMeaningfulDroneSpec(drone) || (cached != null && hasMeaningfulDroneSpec(cached))
    }

    private fun hasMeaningfulDroneSpec(drone: CtDroneSpec): Boolean {
        val mappedId = drone.mappedId.trim()
        return drone.org.isNotBlank() ||
            drone.model.isNotBlank() ||
            drone.owner.isNotBlank() ||
            (mappedId.isNotEmpty() && mappedId != drone.remoteId)
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
