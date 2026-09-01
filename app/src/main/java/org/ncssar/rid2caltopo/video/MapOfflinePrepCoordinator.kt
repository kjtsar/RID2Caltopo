package org.ncssar.rid2caltopo.video

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Process-lifetime signal used by app shutdown and relocation policy. */
object MapOfflinePrepRuntime {
    private val active = AtomicBoolean(false)

    @Volatile
    private var cancelAction: ((Boolean) -> Unit)? = null

    @JvmStatic
    fun isActive(): Boolean = active.get()

    internal fun begin(onCancel: (hideDialog: Boolean) -> Unit) {
        cancelAction = onCancel
        active.set(true)
    }

    internal fun finish() {
        active.set(false)
        cancelAction = null
    }

    @JvmStatic
    fun cancelActive() {
        cancelAction?.invoke(true)
    }

    internal fun resetForTesting() {
        finish()
    }
}

/**
 * Owns the Android offline-map job independently of any particular MapPane composition.
 * A replacement MapPane observes the same state and reattaches to the active progress UI.
 */
internal object AndroidMapOfflinePrepCoordinator {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val showDialog = mutableStateOf(false)
    val inFlight = mutableStateOf(false)
    val preset = mutableStateOf(OFFLINE_PREP_PRESETS[1])
    val includeDem = mutableStateOf(true)
    val demResolution = mutableStateOf(DemResolutionOption.STANDARD_30M)
    val includeContours = mutableStateOf(false)
    val maximizeThroughput = mutableStateOf(false)
    val areaMode = mutableStateOf(OfflinePrepAreaMode.Viewport)
    val boundaryId = mutableStateOf<String?>(null)
    val progress = mutableStateOf(OfflinePrepProgress())
    val cancelRequested = mutableStateOf(false)
    val completedSelectionKey = mutableStateOf<String?>(null)
    val job = mutableStateOf<Job?>(null)
    val autoCloseJob = mutableStateOf<Job?>(null)
    val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun begin() {
        MapOfflinePrepRuntime.begin(::requestCancel)
    }

    fun finish() {
        MapOfflinePrepRuntime.finish()
    }

    fun requestCancel(hideDialog: Boolean = false) {
        cancelRequested.value = true
        progress.value = progress.value.copy(phase = "Cancelling")
        activeCalls.forEach(Call::cancel)
        job.value?.cancel()
        job.value = null
        autoCloseJob.value?.cancel()
        autoCloseJob.value = null
        activeCalls.clear()
        inFlight.value = false
        cancelRequested.value = false
        progress.value = progress.value.copy(phase = "Cancelled")
        if (hideDialog) showDialog.value = false
        finish()
    }
}

internal fun offlinePrepMenuStatus(inFlight: Boolean, progress: OfflinePrepProgress): String? {
    if (!inFlight) return null
    if (progress.total <= 0) return progress.phase
    val percent = (progress.completed.toDouble() * 100.0 / progress.total.toDouble())
        .coerceIn(0.0, 100.0)
        .toInt()
    return "$percent%"
}
