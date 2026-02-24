package org.ncssar.rid2caltopo.video

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoClient.ShowToast


object StreamRegistry {
    private val TAG = "StreamRegistry"
    private const val MAX_SIMULTANEOUS_STREAMS = 4
    private val _streams =
        MutableStateFlow<Map<String, StreamInfo>>(emptyMap())
    val streams: StateFlow<Map<String, StreamInfo>> = _streams
    private val rejectedPaths = mutableSetOf<String>()

    private fun canAdmit(path: String): Boolean {
        val current = _streams.value
        if (current.containsKey(path)) return true
        if (current.size < MAX_SIMULTANEOUS_STREAMS) return true

        if (rejectedPaths.add(path)) {
            val msg = "Rejecting stream '$path': max $MAX_SIMULTANEOUS_STREAMS active streams."
            CTWarn(TAG, msg)
            ShowToast("Rejected stream $path (max $MAX_SIMULTANEOUS_STREAMS streams).")
        }
        return false
    }

    fun onStreamConnecting(path: String) {
        if (!canAdmit(path)) return
        _streams.update { it + (path to StreamInfo(path,StreamState.CONNECTING)) }
        CTDebug(TAG, "onStreamConnecting(${path}): state:${StreamState.CONNECTING.name}")
    }

    fun onStreamError(path: String) {
        _streams.update { it + (path to StreamInfo(path,StreamState.ERROR)) }
        CTDebug(TAG, "onStreamError(${path}): state:${StreamState.ERROR.name}")
    }


    fun onStreamStarted(path: String) {
        if (!canAdmit(path)) return
        _streams.update {it + (path to StreamInfo(path, StreamState.LIVE))}
        CTDebug(TAG, "onStreamStarted(${path}): state:${StreamState.LIVE.name}")
    }

    fun onStreamStopped(path: String) {
        val existed = _streams.value.containsKey(path)
        _streams.update {it - path}
        rejectedPaths.remove(path)
        if (!existed) {
            CTDebug(TAG, "onStreamStopped(${path}): already absent.")
            return
        }
        CTDebug(TAG, "onStreamStopped(${path}): state:${StreamState.STOPPED.name}")
    }
}
