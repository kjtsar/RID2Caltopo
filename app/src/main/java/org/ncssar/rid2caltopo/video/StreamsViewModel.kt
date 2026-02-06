import android.app.Application
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.video.ClueSnapshot
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.DelayedExec
import org.ncssar.rid2caltopo.video.StreamState
import java.util.Collections
import java.util.WeakHashMap

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

    fun changeMappedId(id: String) {source.setMappedId(id)}
    fun updateFrom(spec: CtDroneSpec) {
        lastLat = spec.lastLat
        lastLng = spec.lastLng
        lastAlt = spec.lastAlt
        lastTimestamp = spec.durationInSecAsString
        mappedId = spec.mappedId
    }
}

class StreamsViewModel (
    application: Application
) : AndroidViewModel(application),
    CtDroneSpec.DroneSpecsChangedListener {
    private val TAG = "StreamsViewModel"

    private val context = application.applicationContext

    val streams: StateFlow<Map<String, StreamInfo>> =
        StreamRegistry.streams
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )
    var focusedPath by mutableStateOf<String?>(null)
        private set

    private val _players =
        mutableStateMapOf<String, ExoPlayer>()
    val players: Map<String, ExoPlayer> get() = _players

    private val restarting = mutableSetOf<String>()
    private val lastError = mutableMapOf<String, Long>()

    private fun isRestarting(designator: String): Boolean {
        return restarting.contains(designator)
    }

    private val lastBufferingTime = mutableMapOf<String, Long>()

    private val firstFrameRendered = mutableSetOf<String>()
    private val BUFFERING_GRACE_MS = 1500L
    private val RESTART_COOLDOWN_MS = 8_000L

    private val preparedPlayers =
        Collections.newSetFromMap(WeakHashMap<Player, Boolean>())

    private val restartIds = mutableMapOf<String, Long>()

    private val _droneStates = mutableMapOf<String, DroneSpecState>()
    val droneStates: Map<String, DroneSpecState> get() = _droneStates



    private val stallPoll : DelayedExec = DelayedExec()
    private val MAX_BUFFERING_MS = 3_000L
    private fun startStallPoll() {
        val sampleTimeMs = MAX_BUFFERING_MS / 2
        stallPoll.start(this::pollForStalledPlayers, sampleTimeMs, sampleTimeMs)
    }
    private fun pollForStalledPlayers() {
        val now = System.currentTimeMillis()
        lastBufferingTime.forEach { (designator, since) ->
            if (now - since > MAX_BUFFERING_MS && !isRestarting(designator)) {
                CTWarn(TAG, "Video stalled for $designator -> restarting player")
                recreatePlayer(designator)
            }
        }
    }


    /***
     * Received from CaltopoClient at a maximum rate of once per second if
     * there are any active dronespecs.  If currentDrones.isEmpty() we need
     * to fire a one second timer to check for frozen streams.
     * Only update _activeDroneSpecs when any of the members have changed.
     */
    override fun onDroneSpecsChanged(currentDrones: List<CtDroneSpec>) {
        if (!currentDrones.isEmpty()) {
            CTDebug(TAG, "onDroneSpecsChanged(): received ${currentDrones.size} dronespecs.")
            stallPoll.stop()
            pollForStalledPlayers()
            currentDrones.forEach { spec ->
                val key = spec.mappedId
                val state = _droneStates.getOrPut(key) {
                    DroneSpecState(spec)
                }
                state.updateFrom(spec)
            }
        } else {
            startStallPoll()
        }
    }

    private fun createPlayer(designator: String): ExoPlayer {
        CTDebug(TAG, "createPlayer(${designator})...")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,
                5_000,
                500,
                500
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
        // restartId to force unique URL for each stream that gets reused - to
        // prevent HLS player from reusing old context:
        val restartId = System.currentTimeMillis()
        val url = "http://127.0.0.1:8888/$designator/index.m3u8?rid=${restartId}"
        CTDebug(TAG, "Starting HLS player with url: '${url}'")

        val mediaSource = HlsMediaSource.Factory(
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
        ).createMediaSource(MediaItem.fromUri(url))
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.setMediaSource(mediaSource)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        attachPlayerListeners(player, designator)
        CTDebug(TAG, "createPlayer() HLS player started for $designator")
        return player
    }

    fun playerFor(designator: String): ExoPlayer? {
        CTDebug(TAG, "playerFor(${designator}):${players.containsKey(designator)}")
        return _players[designator]
    }

    fun releasePlayer(designator: String) {
        CTDebug(TAG, "releasePlayer(${designator})")
        _players.remove(designator)?.let { player ->
            CTDebug(TAG, "releasePlayer(${designator}): clearing surface and /releasing...")
            player.clearVideoSurface()
            player.release()
        }
    }

    fun ensurePlayer(designator: String) {
        // Already playing or already restarting? Do nothing.
        CTDebug(TAG, "ensurePlayer(${designator})...")
        if (_players.containsKey(designator)) return
        if (restarting.contains(designator)) return

        restarting += designator
        CTDebug(TAG, "ensurePlayer(${designator}) restarting...")

        viewModelScope.launch {
            val player = createPlayer(designator)
            _players[designator] = player
            restarting -= designator
        }
    }

    private fun markPrepared(player: Player): Boolean {
        return preparedPlayers.add(player)
    }

    private fun attachPlayerListeners(
        player: ExoPlayer,
        designator: String
    ) {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val now = System.currentTimeMillis()
                onLive(designator)
            }
            override fun onPlayerError(error: PlaybackException) {
                // there are different degrees of errors... this seems to
                // happen on normal stream closure
                onFatalPlayerError(designator, error)
            }
            override fun onTracksChanged(tracks: Tracks) {
                val now = System.currentTimeMillis()

                val hasSelectedVideo =
                    tracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_VIDEO && group.isSelected
                    }

                if (hasSelectedVideo && markPrepared(player)) {
                    // prepare() must happen exactly once per Player instance,
                    // after the first video track becomes available
                    CTDebug(TAG, "Video track available → prepare + play")
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                val now = System.currentTimeMillis()
                when (state) {
                    Player.STATE_BUFFERING -> {
                        lastBufferingTime.putIfAbsent(designator, now)
                        onBuffering(designator)
                    }
                    Player.STATE_READY     -> {
                        lastBufferingTime.remove(designator)
                        restarting.remove(designator)
                        onLive(designator)
                    }
                    Player.STATE_ENDED     -> {
                        onEnded(designator)
                    }
                }
            }
        })
    }

    fun recreatePlayer(designator: String) {
        if (isRestarting(designator)) return
        resetPlayerState(designator)
        restarting.add(designator)
        lastError[designator] = System.currentTimeMillis()

        viewModelScope.launch {
            // Let MediaMTX settle (muxer recreate, etc)
            delay(750)

            _players.remove(designator)?.let { old ->
                old.release()
            }

            _players[designator] = createPlayer(designator)
        }
    }


    fun onFatalPlayerError(
        designator: String,
        error: PlaybackException
    ) {
        CTWarn(TAG, "onFatalPlayerError(${designator}).", error)
        // recreatePlayer(designator)
    }

    fun toggleFocus(designator: String) {
        focusedPath =
            if (focusedPath == designator) null else designator
    }

    override fun onCleared() {
        _players.values.forEach { it.release() }
        _players.clear()
        firstFrameRendered.clear()
        lastBufferingTime.clear()
        restarting.clear()
    }

    fun designatorStateFor(designator: String): DesignatorState {
        if (_droneStates.isEmpty()) return DesignatorState.Red

        val dss = _droneStates[designator]
        if (dss != null) {
            return DesignatorState.Green(dss)
        }

        return DesignatorState.Yellow(droneStates)
    }

    fun onSnapshotCaptured(snapshot: ClueSnapshot) {
        /***
        _pendingSnapshot.val = snapshot
        ***/
    }

    init {
        startStallPoll()
        CaltopoClient.AddDroneSpecsChangedListener(this)
        viewModelScope.launch {
            StreamRegistry.streams.collect { map ->
                map.values.forEach { info ->
                    when (info.state) {
                        StreamState.LIVE -> {
                            val rid = System.currentTimeMillis()
                            restartIds[info.designator] = rid
                            CTDebug(TAG, "Stream ${info.designator} is LIVE → ensurePlayer - rid:$rid")
                            ensurePlayer(info.designator)
                        }
                        StreamState.STOPPED -> {
                            CTDebug(TAG, "Stream ${info.designator} STOPPED → releasePlayer")
                            releasePlayer(info.designator)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun onStreamLive(designator: String) {
        val rid = System.currentTimeMillis()
        restartIds[designator] = rid
        CTDebug(TAG, "onStreamLive($designator) restartId:$rid")
    }

    private fun resetPlayerState(designator: String) {
        firstFrameRendered.remove(designator)
        lastBufferingTime.remove(designator)
        restarting.remove(designator)
        _players.remove(designator)?.let {
            it.release()
            CTDebug(TAG, "Released player for $designator")
        }
    }
    private fun onStreamStopped(designator: String) {
        CTDebug(TAG, "onStreamStopped($designator)")
        resetPlayerState(designator)
    }

    fun onBuffering(designator: String) {
        CTDebug(TAG, "onBuffering(${designator})")
    }
    fun onLive(designator: String) {
        CTDebug(TAG, "onLive(${designator})")
    }
    fun onEnded(designator: String) {
        CTDebug(TAG, "onEnded(${designator})")
    }
    fun onError(designator: String, error: String) {
        CTError(TAG, "onError(${designator}): ${error}")
        if (isRestarting(designator)) return;
        viewModelScope.launch {
            delay(750) // let HLS settle
            recreatePlayer(designator)
        }
    }


    fun clearFocus() {
        focusedPath = null
    }
}
