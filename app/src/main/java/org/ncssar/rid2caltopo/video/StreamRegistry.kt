package org.ncssar.rid2caltopo.video

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CtDroneSpec
import java.time.Duration
import java.time.Instant


object StreamRegistry {
    private val TAG = "StreamRegistry"
    private val _streams =
        MutableStateFlow<Map<String, StreamInfo>>(emptyMap())
    val streams: StateFlow<Map<String, StreamInfo>> = _streams

    fun onStreamConnecting(path: String) {
        _streams.update { it + (path to StreamInfo(path,StreamState.CONNECTING)) }
        CTDebug(TAG, "onStreamConnecting(${path}): state:${StreamState.CONNECTING.name}")
    }

    fun onStreamError(path: String) {
        _streams.update { it + (path to StreamInfo(path,StreamState.ERROR)) }
        CTDebug(TAG, "onStreamError(${path}): state:${StreamState.ERROR.name}")
    }


    fun onStreamStarted(path: String) {
        _streams.update {it + (path to StreamInfo(path, StreamState.LIVE))}
        CTDebug(TAG, "onStreamStarted(${path}): state:${StreamState.LIVE.name}")
    }

    fun onStreamStopped(path: String) {
        _streams.update {it - path}
        CTDebug(TAG, "onStreamStopped(${path}): state:${StreamState.STOPPED.name}")
    }
}
