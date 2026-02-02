package org.ncssar.rid2caltopo.video

import android.net.Uri
import org.ncssar.rid2caltopo.data.CtDroneSpec
import java.time.Instant

data class StreamInfo(
    val designator: String,    // MediaMTX designator s/b <callsign><dronedesc>, but could be anything.
    //val protocol: Protocol,  // MediaMTX protocol
    val state: StreamState,
)

enum class StreamState {
    CONNECTING,
    LIVE,
    STOPPED,
    ERROR
}

sealed class ResolutionState {
    object Pending
    object Resolved
    data class Ambiguous(val candidates: List<CtDroneSpec>)
    object Unknown
}

enum class Protocol {
    RTSP,
    RTMP
}

enum class StreamBindingState {
    UNBOUND,          // Just arrived
    MATCHED_EXACT,    // Exact designator match
    MATCHED_REMOTEID, // Bound via RemoteID
    MATCHED_HEURISTIC,// Closest match
    UNRESOLVED        // Needs user action
}