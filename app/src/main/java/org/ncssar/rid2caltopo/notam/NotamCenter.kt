package org.ncssar.rid2caltopo.notam

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object NotamCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        scope.launch {
            repository.refresh(force = true)
        }
    }

    private fun startLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                repository.refresh()
                delay(15_000L)
            }
        }
    }
}

