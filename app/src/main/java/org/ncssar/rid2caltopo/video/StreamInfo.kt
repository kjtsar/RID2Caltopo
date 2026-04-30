package org.ncssar.rid2caltopo.video

import android.net.Uri
import org.ncssar.rid2caltopo.data.CtDroneSpec
import java.time.Instant

data class StreamInfo(
    val designator: String,    // Display name shown in UI; usually the stream path leaf.
    val sourcePath: String = designator, // Full MediaMTX path, including any controller prefix.
    val controllerProfile: StreamControllerProfile = StreamControllerProfile.GENERIC,
    val playbackUri: Uri? = null,
    val isLocalPlayback: Boolean = false,
    //val protocol: Protocol,  // MediaMTX protocol
    val state: StreamState,
    val errorDetail: String? = null,
    val revision: Long = 0L,
    val publisherConnId: String? = null,
)

enum class StreamControllerProfile {
    GENERIC,
    RC2,
    RCPRO2,
    RCPRO1,
    ENTERPRISE2,
    AUTEL,
}

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
