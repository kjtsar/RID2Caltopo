import android.app.Application
import android.graphics.Bitmap
import android.view.Surface
import org.osmdroid.api.IGeoPoint
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoMap.MapStatusListener.mapStatus
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm
import org.ncssar.rid2caltopo.video.anomaly.AnomalyConfig
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegProbeService
import org.ncssar.rid2caltopo.video.ffmpeg.StreamTelemetrySnapshot
import org.ncssar.rid2caltopo.video.CoordinateDisplayFormat
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.StreamState

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

data class MapViewportState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double
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

/** Display-ready values pushed by SplitMapPane's render loop for use in clue descriptions. */
data class DroneDisplayState(
    val headingDeg: Double?,
    val aglFt: Double?,
    val atoFt: Double?,
)

class StreamsViewModel(
    application: Application
) : AndroidViewModel(application),
    CtDroneSpec.DroneSpecsChangedListener,
    CaltopoMap.MapStatusListener {

    private val tag = "StreamsViewModel"
    private val ffmpegProbeService: FfmpegProbeService? = try {
        FfmpegProbeService()
    } catch (t: Throwable) {
        CTError(tag, "FFmpeg probe service unavailable; stream playback will remain unavailable.", Exception(t))
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

    private val _droneStates = mutableStateMapOf<String, DroneSpecState>()
    val droneStates: Map<String, DroneSpecState> get() = _droneStates
    private val _anomalyConfigByDesignator = mutableStateMapOf<String, AnomalyConfig>()
    private val _renderDelayMsByDesignator = mutableStateMapOf<String, Long>()
    private val renderRouteByDesignator = mutableStateMapOf<String, Boolean>()
    private val streamInfoByDesignator = mutableMapOf<String, StreamInfo>()
    private val dismissedStreamRevisions = mutableStateMapOf<String, Long>()
    private val droneDisplayStateMap = HashMap<String, DroneDisplayState>()

    /** Called by SplitMapPane's render loop to push computed display values for use in clue descriptions. */
    fun updateDroneDisplayState(designator: String, headingDeg: Double?, aglFt: Double?, atoFt: Double?) {
        droneDisplayStateMap[designator] = DroneDisplayState(headingDeg, aglFt, atoFt)
    }

    private val _pendingClue = mutableStateOf<PendingClue?>(null)
    val pendingClue: PendingClue?
        get() = _pendingClue.value

    private val _mapName = mutableStateOf<String?>(null)
    val mapName: String? by _mapName
    private val _coordinateDisplayFormat = mutableStateOf<CoordinateDisplayFormat>(
        CoordinateDisplayFormat.fromStorage(CaltopoClient.GetCoordinateDisplayFormat())
    )
    val coordinateDisplayFormat: CoordinateDisplayFormat
        get() = _coordinateDisplayFormat.value
    private val _baseLayer = mutableStateOf(org.ncssar.rid2caltopo.video.BaseLayerOption.OpenStreetMap)
    internal val baseLayer: org.ncssar.rid2caltopo.video.BaseLayerOption
        get() = _baseLayer.value
    private var persistedMapViewportState: MapViewportState? = null

    private var lastLiveRevisions: Map<String, Long> = emptyMap()
    private var lastLivePublisherConnIds: Map<String, String?> = emptyMap()

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

    fun isStreamVisible(stream: StreamInfo): Boolean {
        return dismissedStreamRevisions[stream.designator] != stream.revision
    }

    fun useFfmpegRender(designator: String): Boolean {
        return renderRouteByDesignator[designator] == true
    }

    fun bindFfmpegRenderSurface(designator: String, surface: Surface): Boolean {
        return ffmpegProbeService?.bindRenderSurface(designator, surface) == true
    }

    fun unbindFfmpegRenderSurface(designator: String) {
        ffmpegProbeService?.unbindRenderSurface(designator)
    }

    fun toggleFocus(designator: String) {
        var fString = "has"
        _focusedPath.value =
            if (_focusedPath.value == designator) {
                fString = "does not have"
                null
            } else {
                designator
            }
        CTDebug(tag, "toggleFocus(): ${designator} ${fString} focus.")
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(streams.value)
    }

    override fun onCleared() {
        CaltopoMap.RemoveMapStatusListener(this)
        ffmpegProbeService?.close()
        super.onCleared()
    }

    override fun mapStatusUpdate(status: mapStatus?, mapNode: CaltopoNode.MapNode?, optErrmsg: String?) {
        val newName = mapNode?.title;
        val oldName = _mapName.value;
        if (status == CaltopoMap.MapStatusListener.mapStatus.up) {
            if (!oldName.equals(newName)) {
                persistedMapViewportState = null
                CTDebug(tag, "Connected to ${newName}")
                _mapName.value = newName
            }
        } else if (_mapName.value != null) {
            _mapName.value = null
            CTDebug(tag, "Disconnected from ${oldName} map")
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

    fun renderDelayMsFor(designator: String): Long? {
        return _renderDelayMsByDesignator[designator]
    }

    fun setCoordinateDisplayFormat(format: CoordinateDisplayFormat) {
        if (_coordinateDisplayFormat.value == format) return
        _coordinateDisplayFormat.value = format
        CaltopoClient.SetCoordinateDisplayFormat(format.storageValue)
    }

    internal fun setBaseLayer(baseLayer: org.ncssar.rid2caltopo.video.BaseLayerOption) {
        if (_baseLayer.value == baseLayer) return
        _baseLayer.value = baseLayer
    }

    fun mapViewportState(): MapViewportState? = persistedMapViewportState

    fun persistMapViewportState(center: IGeoPoint?, zoom: Double) {
        val lat = center?.latitude ?: return
        val lng = center.longitude
        if (!lat.isFinite() || !lng.isFinite() || !zoom.isFinite()) return
        persistedMapViewportState = MapViewportState(
            latitude = lat,
            longitude = lng,
            zoom = zoom
        )
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

    fun anomalyConfigFor(designator: String): AnomalyConfig {
        return _anomalyConfigByDesignator[designator] ?: AnomalyConfig()
    }

    fun toggleAnomalyEnabled(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(enabled = !current.enabled)
        }
    }

    fun toggleAnomalyAlgorithm(designator: String, algorithm: AnomalyAlgorithm) {
        updateAnomalyConfig(designator) { current ->
            val updated = current.algorithms.toMutableSet()
            if (!updated.add(algorithm)) {
                updated.remove(algorithm)
            }
            current.copy(algorithms = updated)
        }
    }

    fun cycleAnomalyFrameStride(designator: String) {
        val frameStrideSteps = listOf(1, 2, 3, 4)
        updateAnomalyConfig(designator) { current ->
            val idx = frameStrideSteps.indexOf(current.frameStride)
            val next = if (idx < 0) frameStrideSteps[0] else frameStrideSteps[(idx + 1) % frameStrideSteps.size]
            current.copy(frameStride = next)
        }
    }

    fun cycleAnomalySensitivity(designator: String) {
        val sensitivitySteps = listOf(0.25f, 0.60f, 0.90f)
        updateAnomalyConfig(designator) { current ->
            val currentClamped = current.sensitivity.coerceIn(0f, 1f)
            val idx = sensitivitySteps.indexOfFirst { kotlin.math.abs(it - currentClamped) < 0.01f }
            val next = if (idx < 0) sensitivitySteps[1] else sensitivitySteps[(idx + 1) % sensitivitySteps.size]
            current.copy(sensitivity = next)
        }
    }

    fun setAnomalySensitivity(designator: String, sensitivity: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(sensitivity = sensitivity.coerceIn(0f, 1f))
        }
    }

    fun cycleAnomalyThermalPolarity(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(thermalPolarity = current.thermalPolarity.next())
        }
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
        lines += "RID: ${droneSpec.remoteId}"
        lines += "Telemetry:"

        // First three: Heading, AGL, ATO — use values already computed by SplitMapPane's render loop
        val display = droneDisplayStateMap[designator]
        lines += if (display?.headingDeg != null)
            String.format(Locale.US, "  Heading: %.1f\u00b0", display.headingDeg)
        else
            "  Heading: N/A"
        lines += if (display?.aglFt != null)
            String.format(Locale.US, "  AGL: %.0f'", display.aglFt)
        else
            "  AGL: N/A"
        lines += if (display?.atoFt != null)
            String.format(Locale.US, "  ATO: %.0f'", display.atoFt)
        else
            "  ATO: N/A"

        // Remaining RID telemetry
        ridTelemetry?.let { rid ->
            rid.aircraftAltitudeRateFpm?.let { lines += String.format(Locale.US, "  Vertical rate: %.0f fpm", it) }
            rid.aircraftGsKnots?.let { lines += String.format(Locale.US, "  Ground speed: %.1f kt", it) }
            rid.aircraftTrackDeg?.let { lines += String.format(Locale.US, "  Track: %.1f\u00b0", it) }
        }

        // Stream telemetry
        telemetry?.let {
            telemetry.latestRemoteId?.let { lines += "  RID (stream): $it" }
            if (telemetry.remoteIdCandidates.isNotEmpty()) {
                lines += "  RID candidates: ${telemetry.remoteIdCandidates.joinToString(",")}"
            }
            if (telemetry.latitude != null && telemetry.longitude != null) {
                val altText = telemetry.altitudeMeters?.let { String.format(Locale.US, ", alt=%.1fm", it) } ?: ""
                lines += String.format(Locale.US, "  Stream position: %.6f, %.6f%s", telemetry.latitude, telemetry.longitude, altText)
            }
            telemetry.gimbalPitchDeg?.let { lines += String.format(Locale.US, "  Gimbal pitch: %.1f\u00b0", it) }
            telemetry.cameraYawDeg?.let { lines += String.format(Locale.US, "  Camera yaw: %.1f\u00b0", it) }
            telemetry.sourceTag?.let { src ->
                val confidenceText = telemetry.confidence?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"
                lines += "  Telemetry source: $src (confidence=$confidenceText)"
            }
            telemetry.sourceTimestampUs?.let { lines += "  Telemetry timestamp(us): $it" }
        }
        return lines.joinToString("\n")
    }

    private fun appendTelemetrySummary(description: String, summary: String?): String {
        if (summary.isNullOrBlank()) return description
        if (description.trim() == summary.trim()) return description
        if (description.contains("Telemetry:")) return description
        if (description.isBlank()) return summary
        return "$description\n\n$summary"
    }

    private fun shouldUseFfmpegRender(designator: String): Boolean {
        return streamInfoByDesignator[designator]?.state == StreamState.LIVE
    }

    private fun syncStreamSessions(streamsMap: Map<String, StreamInfo>) {
        streamInfoByDesignator.clear()
        streamInfoByDesignator.putAll(streamsMap)

        val focused = _focusedPath.value
        if (focused != null && !streamsMap.containsKey(focused)) {
            CTDebug(tag, "Focused stream $focused is no longer present -> clearing focus")
            _focusedPath.value = null
        }

        val liveStreams = streamsMap.values
            .filter { it.state == StreamState.LIVE }
        val liveRevisions = liveStreams.associate { it.designator to it.revision }
        val livePublisherConnIds = liveStreams.associate { it.designator to it.publisherConnId }
        dismissedStreamRevisions.entries.toList().forEach { (designator, dismissedRevision) ->
            val liveRevision = liveRevisions[designator]
            if (liveRevision == null || liveRevision != dismissedRevision) {
                dismissedStreamRevisions.remove(designator)
            }
        }
        val dismissedLiveDesignators = liveStreams
            .filterNot(::isStreamVisible)
            .map { it.designator }
            .toSet()
        val activeLiveStreams = liveStreams.filter(::isStreamVisible)
        val liveDesignators = liveRevisions.keys
        val added = activeLiveStreams.map { it.designator }.toSet() - lastLiveRevisions.keys
        val republished = activeLiveStreams
            .filter { info ->
                val previousRevision = lastLiveRevisions[info.designator]
                val revisionChanged = previousRevision != null && info.revision != previousRevision
                val previousPublisherConnId = lastLivePublisherConnIds[info.designator]
                val publisherChanged =
                    previousPublisherConnId != null &&
                    info.publisherConnId != null &&
                    info.publisherConnId != previousPublisherConnId
                revisionChanged || publisherChanged
            }
            .map { it.designator }
            .toSet()
        val focusedPath = _focusedPath.value
        if (focusedPath != null) {
            val newlyAttachedOffFocus = added.filter { it != focusedPath }
            if (newlyAttachedOffFocus.isNotEmpty()) {
                val msg = if (newlyAttachedOffFocus.size == 1) {
                    "New stream attached: ${newlyAttachedOffFocus.first()}"
                } else {
                    "New streams attached: ${newlyAttachedOffFocus.joinToString(", ")}"
                }
                _focusedPath.value = null
                CaltopoClient.ShowToast("$msg. Returning to grid.")
                CTInfo(tag, "$msg -> clearing focus to return to grid")
            }
        }

        val removed = lastLiveRevisions.keys - liveDesignators
        removed.forEach { designator ->
            CTDebug(tag, "Stream $designator no longer live -> stop FFmpeg render")
            renderRouteByDesignator.remove(designator)
            ffmpegProbeService?.setRenderEnabled(designator, false)
            ffmpegProbeService?.onStreamStopped(designator)
        }

        dismissedLiveDesignators.forEach { designator ->
            renderRouteByDesignator[designator] = false
            ffmpegProbeService?.setRenderEnabled(designator, false)
            ffmpegProbeService?.onStreamStopped(designator)
        }

        activeLiveStreams.forEach { info ->
            val designator = info.designator
            val newlyLive = designator in added
            val previousPublisherConnId = lastLivePublisherConnIds[designator]
            val publisherChanged =
                previousPublisherConnId != null &&
                info.publisherConnId != null &&
                info.publisherConnId != previousPublisherConnId
            val republishDetected = designator in republished
            val useFfmpeg = shouldUseFfmpegRender(designator)
            val wasUsingFfmpeg = renderRouteByDesignator[designator] == true
            ffmpegProbeService?.updateSourcePath(designator, info.sourcePath)
            renderRouteByDesignator[designator] = useFfmpeg
            ffmpegProbeService?.setRenderEnabled(designator, useFfmpeg)
            if (useFfmpeg) {
                if (republishDetected) {
                    if (publisherChanged) {
                        CTDebug(
                            tag,
                            "Stream $designator publisherConn=${previousPublisherConnId} -> ${info.publisherConnId} -> evaluating FFmpeg render session"
                        )
                    } else {
                        CTDebug(tag, "Stream $designator live revision=${info.revision} -> tolerating controller republish")
                    }
                    ffmpegProbeService?.onStreamRepublished(
                        designator,
                        publisherChanged = publisherChanged,
                        previousPublisherConnId = previousPublisherConnId,
                        publisherConnId = info.publisherConnId,
                    )
                    CTDebug(tag, "Stream $designator live -> using FFmpeg render path")
                } else if (newlyLive || !wasUsingFfmpeg) {
                    ffmpegProbeService?.onStreamBecameLive(designator)
                    CTDebug(tag, "Stream $designator live -> using FFmpeg render path")
                }
            }
        }

        applyFocusedAnomalyPolicy(liveDesignators)
        lastLiveRevisions = liveRevisions
        lastLivePublisherConnIds = livePublisherConnIds
    }

    fun clearFocus() {
        _focusedPath.value = null
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(streams.value)
    }

    fun dismissFocusedStream() {
        val designator = _focusedPath.value ?: return
        val info = streams.value[designator] ?: return
        dismissedStreamRevisions[designator] = info.revision
        CTDebug(tag, "dismissFocusedStream(): hiding $designator at revision=${info.revision}")
        _focusedPath.value = null
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(streams.value)
        CaltopoClient.ShowToast("Closed $designator until it republishes.")
    }

    private fun updateAnomalyConfig(
        designator: String,
        reducer: (AnomalyConfig) -> AnomalyConfig,
    ) {
        val current = _anomalyConfigByDesignator[designator] ?: AnomalyConfig()
        val updated = reducer(current)
        _anomalyConfigByDesignator[designator] = updated
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(streams.value)
    }

    private fun applyFocusedAnomalyPolicy(liveDesignators: Set<String>) {
        val focused = _focusedPath.value
        liveDesignators.forEach { designator ->
            val config = _anomalyConfigByDesignator[designator] ?: AnomalyConfig()
            val enableForDesignator = focused == designator && config.enabled
            ffmpegProbeService?.setAnomalyConfig(
                designator,
                config.toNativeConfig(enabledOverride = enableForDesignator)
            )
        }
    }

    init {
        CaltopoMap.AddMapStatusListener(this)
        CaltopoClient.AddDroneSpecsChangedListener(this)

        viewModelScope.launch {
            StreamRegistry.streams.collect { map ->
                syncStreamSessions(map)
            }
        }
        ffmpegProbeService?.let { service ->
            viewModelScope.launch {
                service.renderDelayMsByDesignatorFlow.collect { delays ->
                    _renderDelayMsByDesignator.clear()
                    _renderDelayMsByDesignator.putAll(delays)
                }
            }
        }
    }
}
