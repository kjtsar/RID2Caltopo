package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast

data class StreamAdmissionState(
    val active: Map<String, StreamInfo>,
    val stateChangedAtMs: Map<String, Long>,
    val rejectedPaths: Set<String>,
)

data class StreamAdmissionResult(
    val state: StreamAdmissionState,
    val admitted: Boolean,
    val shouldNotifyRejection: Boolean,
    val evictedPaths: Set<String>,
)

data class StreamTransitionResult(
    val state: StreamAdmissionState,
    val changed: Boolean,
    val existed: Boolean,
)

object StreamAdmissionPolicy {
    fun admit(
        state: StreamAdmissionState,
        path: String,
        targetState: StreamState,
        nowMs: Long,
        maxSimultaneousStreams: Int,
        staleConnectingMs: Long,
        staleErrorMs: Long,
    ): StreamAdmissionResult {
        val active = state.active.toMutableMap()
        val changedAt = state.stateChangedAtMs.toMutableMap()
        val rejected = state.rejectedPaths.toMutableSet()

        val evicted = active.values
            .filter { info ->
                val ageMs = nowMs - (changedAt[info.designator] ?: nowMs)
                when (info.state) {
                    StreamState.CONNECTING -> ageMs > staleConnectingMs
                    StreamState.ERROR -> ageMs > staleErrorMs
                    else -> false
                }
            }
            .map { it.designator }
            .toSet()

        evicted.forEach { stalePath ->
            active.remove(stalePath)
            changedAt.remove(stalePath)
            rejected.remove(stalePath)
        }

        val canAdmit = active.containsKey(path) || active.size < maxSimultaneousStreams
        if (!canAdmit) {
            val firstReject = rejected.add(path)
            return StreamAdmissionResult(
                state = StreamAdmissionState(active, changedAt, rejected),
                admitted = false,
                shouldNotifyRejection = firstReject,
                evictedPaths = evicted,
            )
        }

        active[path] = StreamInfo(path, targetState)
        changedAt[path] = nowMs
        rejected.remove(path)
        return StreamAdmissionResult(
            state = StreamAdmissionState(active, changedAt, rejected),
            admitted = true,
            shouldNotifyRejection = false,
            evictedPaths = evicted,
        )
    }

    fun markError(state: StreamAdmissionState, path: String, nowMs: Long): StreamTransitionResult {
        val active = state.active.toMutableMap()
        if (!active.containsKey(path)) {
            return StreamTransitionResult(state = state, changed = false, existed = false)
        }
        val changedAt = state.stateChangedAtMs.toMutableMap()
        active[path] = StreamInfo(path, StreamState.ERROR)
        changedAt[path] = nowMs
        return StreamTransitionResult(
            state = StreamAdmissionState(active, changedAt, state.rejectedPaths),
            changed = true,
            existed = true,
        )
    }

    fun markStopped(state: StreamAdmissionState, path: String): StreamTransitionResult {
        val existed = state.active.containsKey(path)
        val active = state.active.toMutableMap().apply { remove(path) }
        val changedAt = state.stateChangedAtMs.toMutableMap().apply { remove(path) }
        val rejected = state.rejectedPaths.toMutableSet().apply { remove(path) }
        return StreamTransitionResult(
            state = StreamAdmissionState(active, changedAt, rejected),
            changed = existed,
            existed = existed,
        )
    }
}

object StreamRegistry {
    private val TAG = "StreamRegistry"
    private const val MAX_SIMULTANEOUS_STREAMS = 4
    private const val STALE_CONNECTING_MS = 30_000L
    private const val STALE_ERROR_MS = 120_000L
    private val _streams = MutableStateFlow<Map<String, StreamInfo>>(emptyMap())
    val streams: StateFlow<Map<String, StreamInfo>> = _streams
    private val rejectedPaths = mutableSetOf<String>()
    private val stateChangedAtMs = mutableMapOf<String, Long>()
    private val lock = Any()
    internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

    private fun snapshotLocked(): StreamAdmissionState {
        return StreamAdmissionState(
            active = _streams.value,
            stateChangedAtMs = stateChangedAtMs,
            rejectedPaths = rejectedPaths,
        )
    }

    private fun applyStateLocked(state: StreamAdmissionState) {
        _streams.value = state.active
        stateChangedAtMs.clear()
        stateChangedAtMs.putAll(state.stateChangedAtMs)
        rejectedPaths.clear()
        rejectedPaths.addAll(state.rejectedPaths)
    }

    fun onStreamConnecting(path: String) {
        val admitted = synchronized(lock) {
            val result = StreamAdmissionPolicy.admit(
                state = snapshotLocked(),
                path = path,
                targetState = StreamState.CONNECTING,
                nowMs = nowMsProvider(),
                maxSimultaneousStreams = MAX_SIMULTANEOUS_STREAMS,
                staleConnectingMs = STALE_CONNECTING_MS,
                staleErrorMs = STALE_ERROR_MS,
            )
            applyStateLocked(result.state)
            result.evictedPaths.forEach { stalePath ->
                CTWarn(TAG, "Evicting stale stream '$stalePath' to prevent capacity lockout.")
            }
            if (!result.admitted && result.shouldNotifyRejection) {
                val msg = "Rejecting stream '$path': max $MAX_SIMULTANEOUS_STREAMS active streams."
                CTWarn(TAG, msg)
                ShowToast("Rejected stream $path (max $MAX_SIMULTANEOUS_STREAMS streams).")
            }
            result.admitted
        }
        if (admitted) {
            CTDebug(TAG, "onStreamConnecting(${path}): state:${StreamState.CONNECTING.name}")
        }
    }

    fun onStreamError(path: String) {
        val updated = synchronized(lock) {
            val result = StreamAdmissionPolicy.markError(snapshotLocked(), path, nowMsProvider())
            applyStateLocked(result.state)
            result.changed
        }
        if (!updated) {
            CTDebug(TAG, "onStreamError($path): ignoring orphan error transition.")
            return
        }
        CTDebug(TAG, "onStreamError(${path}): state:${StreamState.ERROR.name}")
    }

    fun onStreamStarted(path: String) {
        val admitted = synchronized(lock) {
            val result = StreamAdmissionPolicy.admit(
                state = snapshotLocked(),
                path = path,
                targetState = StreamState.LIVE,
                nowMs = nowMsProvider(),
                maxSimultaneousStreams = MAX_SIMULTANEOUS_STREAMS,
                staleConnectingMs = STALE_CONNECTING_MS,
                staleErrorMs = STALE_ERROR_MS,
            )
            applyStateLocked(result.state)
            result.evictedPaths.forEach { stalePath ->
                CTWarn(TAG, "Evicting stale stream '$stalePath' to prevent capacity lockout.")
            }
            if (!result.admitted && result.shouldNotifyRejection) {
                val msg = "Rejecting stream '$path': max $MAX_SIMULTANEOUS_STREAMS active streams."
                CTWarn(TAG, msg)
                ShowToast("Rejected stream $path (max $MAX_SIMULTANEOUS_STREAMS streams).")
            }
            result.admitted
        }
        if (admitted) {
            CTDebug(TAG, "onStreamStarted(${path}): state:${StreamState.LIVE.name}")
        }
    }

    fun onStreamStopped(path: String) {
        val existed = synchronized(lock) {
            val result = StreamAdmissionPolicy.markStopped(snapshotLocked(), path)
            applyStateLocked(result.state)
            result.existed
        }
        if (!existed) {
            CTDebug(TAG, "onStreamStopped(${path}): already absent.")
            return
        }
        CTDebug(TAG, "onStreamStopped(${path}): state:${StreamState.STOPPED.name}")
    }

    internal fun resetForTests() {
        synchronized(lock) {
            _streams.value = emptyMap()
            rejectedPaths.clear()
            stateChangedAtMs.clear()
            nowMsProvider = { System.currentTimeMillis() }
        }
    }
}
