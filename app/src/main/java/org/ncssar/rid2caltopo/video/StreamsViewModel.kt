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
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
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
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegProbeService
import org.ncssar.rid2caltopo.video.ffmpeg.StreamTelemetrySnapshot
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
    val description: String,
    val streamTelemetrySummary: String? = null
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
    private val ffmpegProbeService: FfmpegProbeService? = try {
        FfmpegProbeService()
    } catch (t: Throwable) {
        CTError(tag, "FFmpeg probe service unavailable; falling back to Exo-only playback.", Exception(t))
        null
    }

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
        val activeKeys = HashSet<String>(currentDrones.size)
        if (currentDrones.isNotEmpty()) {
            CTInfo(tag, "onDroneSpecsChanged(): received ${currentDrones.size} dronespecs.")
            currentDrones.forEach { spec ->
                val key = spec.mappedId
                activeKeys.add(key)
                val state = _droneStates.getOrPut(key) { DroneSpecState(spec) }
                state.updateFrom(spec)
            }
        }
        _droneStates.keys.toList().forEach { key ->
            if (!activeKeys.contains(key)) {
                _droneStates.remove(key)
            }
        }
    }

    fun playerFor(designator: String): ExoPlayer? {
        CTDebug(tag, "playerFor(${designator}):${players.containsKey(designator)}")
        return streamSessions.playerFor(designator)
    }

    fun useFfmpegRender(designator: String): Boolean {
        return ffmpegProbeService?.isRenderEnabled(designator) == true
    }

    fun bindFfmpegRenderSurface(designator: String, surface: Surface): Boolean {
        return ffmpegProbeService?.bindRenderSurface(designator, surface) == true
    }

    fun unbindFfmpegRenderSurface(designator: String) {
        ffmpegProbeService?.unbindRenderSurface(designator)
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
        CaltopoMap.RemoveMapStatusListener(this)
        ffmpegProbeService?.close()
        streamSessions.releaseAll()
        super.onCleared()
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

        val telemetry = ffmpegProbeService?.telemetrySnapshot(designator)
        val clueLat = telemetry?.latitude ?: droneSpec.lastLat
        val clueLng = telemetry?.longitude ?: droneSpec.lastLng
        val clueAlt = telemetry?.altitudeMeters ?: droneSpec.lastAlt
        val clueTimestamp = telemetry?.sourceTimestampUs?.let { it / 1000L } ?: droneSpec.mostRecentMsecTimestamp
        val summary = buildTelemetrySummary(designator, droneSpec, telemetry)

        _pendingClue.value = PendingClue(
            droneSpec = droneSpec,
            designator = designator,
            lat = clueLat,
            lng = clueLng,
            alt = clueAlt,
            timestamp = clueTimestamp,
            bitmap = bitmap,
            preview = null,
            title = "",
            description = summary ?: "",
            streamTelemetrySummary = summary
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
        val finalDescription = appendTelemetrySummary(clue.description, clue.streamTelemetrySummary)
        CaltopoClient.SubmitClue(
            clue.droneSpec,
            clue.bitmap,
            clue.lat,
            clue.lng,
            clue.alt,
            clue.title,
            finalDescription,
            clue.timestamp
        )

        clearPendingClue()
    }

    fun clearPendingClue() {
        _pendingClue.value = null
    }

    private fun buildTelemetrySummary(
        designator: String,
        droneSpec: CtDroneSpec,
        telemetry: StreamTelemetrySnapshot?
    ): String? {
        val ridTelemetry = droneSpec.lastPositionTelemetry
        if (telemetry == null && ridTelemetry == null) return null

        val lines = mutableListOf<String>()
        lines += "Designator: $designator"
        lines += "Mapped ID: ${droneSpec.mappedId}"
        lines += "RID (track): ${droneSpec.remoteId}"

        ridTelemetry?.let { rid ->
            lines += "[RID Telemetry]"
            rid.aircraftAltitudeFt?.let { lines += String.format(Locale.US, "Aircraft altitude: %.0f ft", it) }
            rid.aircraftAltitudeRateFpm?.let { lines += String.format(Locale.US, "Aircraft vertical rate: %.0f fpm", it) }
            rid.aircraftGsKnots?.let { lines += String.format(Locale.US, "Aircraft ground speed: %.1f kt", it) }
            rid.aircraftHeadingDeg?.let { lines += String.format(Locale.US, "Aircraft heading: %.1f deg", it) }
            rid.aircraftTrackDeg?.let { lines += String.format(Locale.US, "Aircraft track: %.1f deg", it) }
        }

        telemetry?.let {
            lines += "[Stream Telemetry]"

            telemetry.latestRemoteId?.let { lines += "RID (stream): $it" }
            if (telemetry.remoteIdCandidates.isNotEmpty()) {
                lines += "RID candidates: ${telemetry.remoteIdCandidates.joinToString(",")}" 
            }
            if (telemetry.latitude != null && telemetry.longitude != null) {
                val altText = telemetry.altitudeMeters?.let { String.format(Locale.US, ", alt=%.1fm", it) } ?: ""
                lines += String.format(Locale.US, "Stream position: %.6f, %.6f%s", telemetry.latitude, telemetry.longitude, altText)
            }
            telemetry.headingDeg?.let { lines += String.format(Locale.US, "Heading: %.1f deg", it) }
            telemetry.gimbalPitchDeg?.let { lines += String.format(Locale.US, "Gimbal pitch: %.1f deg", it) }
            telemetry.cameraYawDeg?.let { lines += String.format(Locale.US, "Camera yaw: %.1f deg", it) }
            telemetry.sourceTag?.let { src ->
                val confidenceText = telemetry.confidence?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"
                lines += "Telemetry source: $src (confidence=$confidenceText)"
            }
            telemetry.sourceTimestampUs?.let { lines += "Telemetry timestamp(us): $it" }
        }
        return lines.joinToString("\n")
    }

    private fun appendTelemetrySummary(description: String, summary: String?): String {
        if (summary.isNullOrBlank()) return description
        if (description.trim() == summary.trim()) return description
        if (description.contains("[Stream Telemetry]")) return description
        if (description.contains("[RID Telemetry]")) return description
        if (description.isBlank()) return summary
        return "$description\n\n$summary"
    }

    private fun syncStreamSessions(streamsMap: Map<String, StreamInfo>) {
        val focused = _focusedPath.value
        if (focused != null && !streamsMap.containsKey(focused)) {
            CTDebug(tag, "Focused stream $focused is no longer present -> clearing focus")
            _focusedPath.value = null
        }

        val liveDesignators = streamsMap.values
            .filter { it.state == StreamState.LIVE }
            .map { it.designator }
            .toSet()
        val added = liveDesignators - lastLiveDesignators
        val focusedPath = _focusedPath.value
        if (focusedPath != null) {
            val newlyAttachedOffFocus = added.filter { it != focusedPath }
            if (newlyAttachedOffFocus.isNotEmpty()) {
                val msg = if (newlyAttachedOffFocus.size == 1) {
                    "New stream attached: ${newlyAttachedOffFocus.first()}"
                } else {
                    "New streams attached: ${newlyAttachedOffFocus.joinToString(", ")}"
                }
                CaltopoClient.ShowToast("$msg. Tap focused stream to return to grid.")
                CTInfo(tag, msg)
            }
        }

        val removed = lastLiveDesignators - liveDesignators
        removed.forEach { designator ->
            CTDebug(tag, "Stream $designator no longer live -> release player")
            ffmpegProbeService?.onStreamStopped(designator)
            streamSessions.onStreamStopped(designator)
        }

        liveDesignators.forEach { designator ->
            ffmpegProbeService?.onStreamBecameLive(designator)
            if (ffmpegProbeService?.isRenderEnabled(designator) == true) {
                CTDebug(tag, "Stream $designator live -> FFmpeg render active, skipping Exo player")
                streamSessions.onStreamStopped(designator)
            } else {
                CTDebug(tag, "Stream $designator live -> ensure Exo player")
                streamSessions.onStreamBecameLive(designator)
            }
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
