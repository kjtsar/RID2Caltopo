package org.ncssar.rid2caltopo.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MutualAidExportCoordinator {
    private val _requestId = MutableStateFlow(0L)
    val requestId: StateFlow<Long> = _requestId.asStateFlow()

    @JvmStatic
    fun requestExportDialog() {
        _requestId.value = _requestId.value + 1L
    }
}
