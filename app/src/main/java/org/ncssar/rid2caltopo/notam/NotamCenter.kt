package org.ncssar.rid2caltopo.notam

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

object NotamCenter {
    private const val LOOP_DELAY_MS = 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = NotamRepository()
    val uiState = repository.uiState

    @Volatile
    private var initialized = false
    private var refreshJob: Job? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        startLoop()
    }

    fun requestImmediateRefresh() {
        if (!initialized) return
        scope.launch {
            repository.refresh(force = true)
        }
    }

    fun resetRuntimeState() {
        repository.resetRuntimeState()
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
                repository.refresh()
                delay(LOOP_DELAY_MS)
            }
        }
    }
}
