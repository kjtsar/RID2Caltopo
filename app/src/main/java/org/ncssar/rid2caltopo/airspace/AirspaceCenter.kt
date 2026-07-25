package org.ncssar.rid2caltopo.airspace

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap

object AirspaceCenter {
    private const val LOOP_DELAY_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = AirspaceRepository()
    private val _uiState = MutableStateFlow(AirspaceUiState())
    val uiState: StateFlow<AirspaceUiState> = _uiState.asStateFlow()

    @Volatile
    private var initialized = false
    private var refreshJob: Job? = null
    private var lastRecords: List<FaaUasFacilityMapRecord> = emptyList()
    private var lastError: String? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        startLoop()
    }

    fun requestImmediateRefresh() {
        if (!initialized) return
        scope.launch { refresh() }
    }

    fun shutdown() {
        refreshJob?.cancel()
        refreshJob = null
        initialized = false
    }

    private fun startLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                refresh()
                delay(LOOP_DELAY_MS)
            }
        }
    }

    private suspend fun refresh() {
        val location = CaltopoMap.GetMyLocation()
        if (location == null) {
            CaltopoClient.CTDebug("Airspace", "Controlled-airspace refresh waiting for GPS location")
            _uiState.value = AirspacePolicy.buildUiState(
                records = lastRecords,
                loading = false,
                errorMessage = lastError ?: "Waiting for GPS location"
            )
            return
        }
        _uiState.value = AirspacePolicy.buildUiState(
            records = lastRecords,
            loading = true,
            errorMessage = lastError
        )
        try {
            lastRecords = repository.fetch(location)
            lastError = null
            CaltopoClient.CTDebug(
                "Airspace",
                "Loaded ${lastRecords.size} FAA facility-map grid(s); " +
                    "controlled=${lastRecords.count { it.airspaceClasses.isNotEmpty() }} " +
                    "laanc=${lastRecords.count { it.laancAvailable }}"
            )
        } catch (e: Exception) {
            lastError = e.message ?: "Controlled-airspace lookup unavailable"
            CaltopoClient.CTWarn("Airspace", lastError, e)
        }
        _uiState.value = AirspacePolicy.buildUiState(
            records = lastRecords,
            loading = false,
            errorMessage = lastError
        )
    }
}
