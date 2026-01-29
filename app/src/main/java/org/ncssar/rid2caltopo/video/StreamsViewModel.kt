import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.video.ClueSnapshot
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.StreamState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class StreamsViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val TAG = "StreamsViewModel"

    val streams: StateFlow<Map<String, StreamInfo>> =
        StreamRegistry.streams
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )

    // ---- Focused path (designator) ----
    var focusedPath by mutableStateOf<String?>(null)
        private set

    private val players =
        mutableMapOf<String, ExoPlayer>()

   init {
        viewModelScope.launch {
            StreamRegistry.streams.collect { map ->
                val focused = focusedPath
                if (focused != null && focused !in map.keys) {
                    focusedPath = null
                }
            }
        }
    }

    fun toggleFocus(designator: String) {
        focusedPath =
            if (focusedPath == designator) null else designator
    }

    /***
    fun getPlayer(designator: String): ExoPlayer {
        return players.getOrPut(designator) {
            ExoPlayer.Builder(context).build().apply {
                val uri = Uri.parse("rtsp://127.0.0.1:8554/${designator}")
                CTDebug(TAG, "Attached player to ${uri}")
                val mediaItem = MediaItem.fromUri(uri)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
                volume = 0f // mute for now
            }
        }
    }
***/
    override fun onCleared() {
        players.values.forEach { it.release() }
        players.clear()
    }

    fun onSnapshotCaptured(snapshot: ClueSnapshot) {
        /***
        _pendingSnapshot.val = snapshot
        ***/
    }

    fun resolveStream(stream: StreamInfo) {
        val direct = CaltopoClient.GetDroneSpec(stream.designator)
/***
        if (direct != null) {
            bind(stream, direct, confidence = HIGH)
            return
        }

        val active = CaltopoClient.getActiveDrones()

        val best = findBestMatch(stream, active)
        when {
            best == null ->
                markUnresolved(stream)

            best.confidence >= THRESHOLD ->
                bind(stream, best.spec, best.confidence)

            else ->
                markAmbiguous(stream, active)
        }
***/
    }

    init {
        viewModelScope.launch {
            streams.collect {
                CTDebug(TAG, "StreamsViewModel VM streams -> ${it.keys}")
            }
        }
    }


    fun clearFocus() {
        focusedPath = null
    }
}
