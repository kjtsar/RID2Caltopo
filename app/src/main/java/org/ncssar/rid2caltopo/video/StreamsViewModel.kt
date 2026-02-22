import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoMap.MapStatusListener.mapStatus
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.StreamState
import org.ncssar.rid2caltopo.video.session.StreamRecoveryPolicy
import org.ncssar.rid2caltopo.video.session.StreamSessionService

data class PendingClue(
    val droneSpec: CtDroneSpec,
    val designator: String,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val timestamp: Long,
    val bitmap: Bitmap?,
    val preview: Bitmap?,
    val title: String,
    val description: String
)

@Stable
class DroneSpecState(
    val source: CtDroneSpec
) {
    val remoteId = source.remoteId
    var lastLat by mutableStateOf(source.lastLat)
        private set
    var lastLng by mutableStateOf(source.lastLng)
        private set
    var lastAlt by mutableStateOf(source.lastAlt)
        private set
    var lastTimestamp by mutableStateOf(source.durationInSecAsString)
        private set
    var mappedId by mutableStateOf(source.mappedId)
        private set

    fun changeMappedId(id: String) { source.setMappedId(id) }

    fun updateFrom(spec: CtDroneSpec) {
        lastLat = spec.lastLat
        lastLng = spec.lastLng
        lastAlt = spec.lastAlt
        lastTimestamp = spec.durationInSecAsString
        mappedId = spec.mappedId
    }
}

class StreamsViewModel(
    application: Application
) : AndroidViewModel(application),
    CtDroneSpec.DroneSpecsChangedListener,
    CaltopoMap.MapStatusListener {

    private val tag = "StreamsViewModel"
    private val context = application.applicationContext

    private val streamSessions = StreamSessionService(
        context = context,
        scope = viewModelScope,
        policy = StreamRecoveryPolicy(),
        listener = object : StreamSessionService.Listener {
            override fun onBuffering(designator: String) {
                this@StreamsViewModel.onBuffering(designator)
            }

            override fun onLive(designator: String) {
                this@StreamsViewModel.onLive(designator)
            }

            override fun onEnded(designator: String) {
                this@StreamsViewModel.onEnded(designator)
            }

            override fun onError(designator: String, error: PlaybackException) {
                CTWarn(tag, "Session error for $designator", error)
            }
        }
    )

    val streams: StateFlow<Map<String, StreamInfo>> =
        StreamRegistry.streams.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    private val _focusedPath = MutableStateFlow<String?>(null)
    val focusedPath: StateFlow<String?> = _focusedPath.asStateFlow()

    val players: Map<String, ExoPlayer> get() = streamSessions.players

    private val _droneStates = mutableStateMapOf<String, DroneSpecState>()
    val droneStates: Map<String, DroneSpecState> get() = _droneStates

    private val _pendingClue = mutableStateOf<PendingClue?>(null)
    val pendingClue: PendingClue?
        get() = _pendingClue.value

    private val _mapName = mutableStateOf<String?>(null)
    val mapName: String? by _mapName

    private var lastLiveDesignators: Set<String> = emptySet()

    /**
     * Received from CaltopoClient at a maximum rate of once per second if
     * there are any active dronespecs.
     */
    override fun onDroneSpecsChanged(currentDrones: List<CtDroneSpec>) {
        if (currentDrones.isNotEmpty()) {
            CTInfo(tag, "onDroneSpecsChanged(): received ${currentDrones.size} dronespecs.")
            currentDrones.forEach { spec ->
                val key = spec.mappedId
                val state = _droneStates.getOrPut(key) { DroneSpecState(spec) }
                state.updateFrom(spec)
            }
        }
    }

    fun playerFor(designator: String): ExoPlayer? {
        CTDebug(tag, "playerFor(${designator}):${players.containsKey(designator)}")
        return streamSessions.playerFor(designator)
    }

    fun ensurePlayer(designator: String) {
        streamSessions.ensurePlayer(designator)
    }

    fun releasePlayer(designator: String) {
        streamSessions.releasePlayer(designator)
    }

    fun recreatePlayer(designator: String) {
        streamSessions.recreatePlayer(designator)
    }

    fun onFatalPlayerError(
        designator: String,
        error: PlaybackException
    ) {
        CTWarn(tag, "onFatalPlayerError(${designator}).", error)
        recreatePlayer(designator)
    }

    fun toggleFocus(designator: String) {
        var fString = "has"
        _focusedPath.value =
            if (_focusedPath.value == designator) {
                fString = "does not have"
                null
            } else designator
        CTDebug(tag, "toggleFocus(): ${designator} ${fString} focus.")
    }

    override fun onCleared() {
        streamSessions.releaseAll()
    }

    override fun mapStatusUpdate(status: mapStatus?, mapNode: CaltopoNode.MapNode?, optErrmsg: String?) {
        if (status == CaltopoMap.MapStatusListener.mapStatus.up) {
            CTDebug(tag, "XYZZY: Connected to ${mapNode?.title}")
            _mapName.value = mapNode?.title
        } else {
            _mapName.value = null
            CTDebug(tag, "XYZZY: Disconnected from map")
        }
    }

    fun designatorStateFor(designator: String): DesignatorState {
        if (_droneStates.isEmpty()) return DesignatorState.Red

        val dss = _droneStates[designator]
        return if (dss != null) {
            DesignatorState.Green(dss)
        } else {
            DesignatorState.Yellow(droneStates)
        }
    }

    fun onSnapshotCaptured(designator: String, bitmap: Bitmap) {
        val droneSpec = droneStates[designator]?.source

        if (droneSpec == null) {
            CTDebug(tag, "onSnapshotCaptured(${designator}): No associated dronespec.")
            return
        }

        _pendingClue.value = PendingClue(
            droneSpec = droneSpec,
            designator = designator,
            lat = droneSpec.lastLat,
            lng = droneSpec.lastLng,
            alt = droneSpec.lastAlt,
            timestamp = droneSpec.mostRecentMsecTimestamp,
            bitmap = bitmap,
            preview = null,
            title = "",
            description = ""
        )

        viewModelScope.launch(Dispatchers.Default) {
            val width = 600
            val height = (width * bitmap.height / bitmap.width)
            val preview = bitmap.scale(width, height)

            withContext(Dispatchers.Main) {
                val clue = _pendingClue.value
                if (clue != null && clue.designator == designator) {
                    _pendingClue.value = clue.copy(
                        bitmap = bitmap,
                        preview = preview,
                    )
                }
            }
        }

        CTDebug(tag, "onSnapshotCaptured(${designator}): clue started for ${bitmap.width}x${bitmap.height} snapshot.")
    }

    fun updateClueTitle(title: String) {
        _pendingClue.value = _pendingClue.value?.copy(title = title)
    }

    fun updateClueDescription(description: String) {
        _pendingClue.value = _pendingClue.value?.copy(description = description)
    }

    fun submitClue() {
        val clue = pendingClue ?: return
        CTDebug(tag, "submitting clue: '${clue.title}' for '${clue.droneSpec.trackLabel()}'")
        CaltopoClient.SubmitClue(
            clue.droneSpec,
            clue.bitmap,
            clue.lat,
            clue.lng,
            clue.alt,
            clue.title,
            clue.description,
            clue.timestamp
        )

        clearPendingClue()
    }

    fun clearPendingClue() {
        _pendingClue.value = null
    }

    private fun syncStreamSessions(streamsMap: Map<String, StreamInfo>) {
        val liveDesignators = streamsMap.values
            .filter { it.state == StreamState.LIVE }
            .map { it.designator }
            .toSet()

        val removed = lastLiveDesignators - liveDesignators
        removed.forEach { designator ->
            CTDebug(tag, "Stream $designator no longer live -> release player")
            streamSessions.onStreamStopped(designator)
        }

        liveDesignators.forEach { designator ->
            CTDebug(tag, "Stream $designator live -> ensure player")
            streamSessions.onStreamBecameLive(designator)
        }

        lastLiveDesignators = liveDesignators
    }

    fun onBuffering(designator: String) {
        CTDebug(tag, "onBuffering(${designator})")
    }

    fun onLive(designator: String) {
        CTDebug(tag, "onLive(${designator})")
    }

    fun onEnded(designator: String) {
        CTDebug(tag, "onEnded(${designator})")
    }

    fun onError(designator: String, error: String) {
        CTError(tag, "onError(${designator}): ${error}")
        recreatePlayer(designator)
    }

    fun clearFocus() {
        _focusedPath.value = null
    }

    init {
        CaltopoMap.AddMapStatusListener(this)
        CaltopoClient.AddDroneSpecsChangedListener(this)

        viewModelScope.launch {
            StreamRegistry.streams.collect { map ->
                syncStreamSessions(map)
            }
        }
    }
}
