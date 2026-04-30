import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Debug
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.view.Surface
import org.osmdroid.api.IGeoPoint
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import org.ncssar.rid2caltopo.video.StreamRenderRouter
import org.ncssar.rid2caltopo.video.session.StreamSessionService
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CaltopoMap.MapStatusListener.mapStatus
import org.ncssar.rid2caltopo.data.CaltopoNode
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.DesignatorState
import org.ncssar.rid2caltopo.app.MediaMTXService
import org.ncssar.rid2caltopo.ui.ComplianceAlertCandidate
import org.ncssar.rid2caltopo.ui.ComplianceAlertCenter
import org.ncssar.rid2caltopo.video.anomaly.AnomalyAlgorithm
import org.ncssar.rid2caltopo.video.anomaly.AnomalyConfig
import org.ncssar.rid2caltopo.video.anomaly.AnomalyPrefs
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalyMode
import org.ncssar.rid2caltopo.video.anomaly.AppearanceAnomalySelection
import org.ncssar.rid2caltopo.video.ffmpeg.FfmpegProbeService
import org.ncssar.rid2caltopo.video.ffmpeg.StreamRuntimeSnapshot
import org.ncssar.rid2caltopo.video.ffmpeg.StreamTelemetrySnapshot
import org.ncssar.rid2caltopo.video.CoordinateDisplayFormat
import org.ncssar.rid2caltopo.video.PlaybackIndicatorState
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamAdmissionGuardResult
import org.ncssar.rid2caltopo.video.StreamAdmissionState
import org.ncssar.rid2caltopo.video.StreamRegistry
import org.ncssar.rid2caltopo.video.StreamState
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService

data class PendingClue(
    val droneSpec: CtDroneSpec,
    val designator: String,
    val droneLat: Double,
    val droneLng: Double,
    val droneAlt: Double,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val headingDeg: Double?,
    val headingSourceLabel: String?,
    val aglMeters: Double?,
    val atoMeters: Double?,
    val gimbalAngleDeg: Double,
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

enum class StreamsLayoutMode {
    Both,
    Streams,
    Map
}

data class ProximityMapFocusTarget(
    val requestId: Long,
    val firstLat: Double,
    val firstLng: Double,
    val secondLat: Double,
    val secondLng: Double
)

private const val COMPLIANCE_ALERT_AGL_LIMIT_FT = 200.0

data class OverLimitDroneUiState(
    val mappedId: String,
    val aglFt: Double,
    val thresholdFt: Double,
    val muted: Boolean,
    val staleDem: Boolean,
    val usingDemAgl: Boolean,
    val atoFt: Double?,
)

private data class ProcessLoadSnapshot(
    val observedAtMs: Long,
    val sampleWindowMs: Long,
    val processCpuFraction: Double,
    val mainThreadCpuFraction: Double,
    val javaHeapUsedMb: Long,
    val javaHeapMaxMb: Long,
    val nativeHeapUsedMb: Long,
    val mediaMtxPid: Int,
    val mediaMtxCpuFraction: Double,
    val liveStreamCount: Int,
    val ffmpegStreamCount: Int,
    val anomalyEnabledCount: Int,
    val thermalStatusLabel: String,
    val anomalyHeadroomLabel: String,
)

internal fun chooseResyncSnapshot(
    lastSyncedStreams: Map<String, StreamInfo>,
    latestFlowValue: Map<String, StreamInfo>,
): Map<String, StreamInfo> {
    return if (lastSyncedStreams.isNotEmpty() && latestFlowValue.isEmpty()) {
        lastSyncedStreams
    } else {
        latestFlowValue
    }
}

private val clueTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val DEFAULT_CLUE_GIMBAL_ANGLE_DEG = -90.0
private const val METERS_TO_FEET = 3.28084
private const val RID_INVALID_ALTITUDE_METERS = -1000.0

internal data class ClueProjection(
    val lat: Double,
    val lng: Double,
    val alt: Double,
)

internal fun buildClueDescriptionTemplate(
    timestampMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val localTime = Instant.ofEpochMilli(timestampMs)
        .atZone(zoneId)
        .format(clueTimeFormatter)
    return "time: $localTime\nfound by: \nreported to IC: yes|no\n"
}

internal fun buildClueCaptureSummary(clue: PendingClue): String {
    val lines = mutableListOf<String>()
    lines += "Projected clue location:"
    lines += String.format(
        Locale.US,
        "  Position: %.6f, %.6f alt %.0f'",
        clue.lat,
        clue.lng,
        clue.alt * METERS_TO_FEET,
    )
    lines += clue.headingDeg?.let {
        String.format(Locale.US, "  Heading used for clue: %.1f\u00b0", it)
    } ?: "  Heading used for clue: N/A"
    lines += "  Heading source: ${clue.headingSourceLabel ?: "N/A"}"
    lines += String.format(Locale.US, "  Gimbal angle at capture: %.1f\u00b0", clue.gimbalAngleDeg)
    return lines.joinToString("\n")
}

private data class HeadingSelection(
    val headingDeg: Double?,
    val sourceLabel: String?,
)

private fun selectClueHeading(
    telemetry: StreamTelemetrySnapshot?,
    displayHeadingDeg: Double?,
    ridTrackDeg: Double?,
): HeadingSelection {
    val cameraYaw = telemetry?.cameraYawDeg?.takeIf { it.isFinite() }
    if (cameraYaw != null) return HeadingSelection(cameraYaw, "Camera yaw")

    val streamHeading = telemetry?.headingDeg?.takeIf { it.isFinite() }
    if (streamHeading != null) return HeadingSelection(streamHeading, "Stream heading")

    val displayHeading = displayHeadingDeg?.takeIf { it.isFinite() }
    if (displayHeading != null) {
        val ridTrack = ridTrackDeg?.takeIf { it.isFinite() }
        val label = if (ridTrack != null && abs(displayHeading - ridTrack) < 0.1) {
            "RID aircraft track"
        } else {
            "Movement fallback"
        }
        return HeadingSelection(displayHeading, label)
    }

    val ridTrack = ridTrackDeg?.takeIf { it.isFinite() }
    if (ridTrack != null) return HeadingSelection(ridTrack, "RID aircraft track")

    return HeadingSelection(null, null)
}

internal fun projectClueLocation(
    droneLat: Double,
    droneLng: Double,
    droneAlt: Double,
    headingDeg: Double?,
    aglMeters: Double?,
    gimbalAngleDeg: Double,
): ClueProjection {
    val validHeading = headingDeg?.takeIf { it.isFinite() }
    val validAgl = aglMeters?.takeIf { it.isFinite() && it >= 0.0 }
    val projectedAlt = if (droneAlt.isFinite() && droneAlt > RID_INVALID_ALTITUDE_METERS && validAgl != null) {
        droneAlt - validAgl
    } else {
        droneAlt
    }
    if (validHeading == null || validAgl == null) {
        return ClueProjection(droneLat, droneLng, projectedAlt)
    }

    val clampedAngle = gimbalAngleDeg
        .takeIf { it.isFinite() }
        ?.coerceIn(-90.0, 0.0)
        ?: DEFAULT_CLUE_GIMBAL_ANGLE_DEG
    val tiltFromHorizonDeg = abs(clampedAngle).coerceIn(0.1, 90.0)
    val horizontalDistanceM = if (tiltFromHorizonDeg >= 89.9) {
        0.0
    } else {
        validAgl / tan(Math.toRadians(tiltFromHorizonDeg))
    }
    if (!horizontalDistanceM.isFinite() || horizontalDistanceM <= 0.0) {
        return ClueProjection(droneLat, droneLng, projectedAlt)
    }

    val destination = destinationPoint(
        startLat = droneLat,
        startLng = droneLng,
        bearingDeg = validHeading,
        distanceM = horizontalDistanceM,
    )
    return ClueProjection(destination.first, destination.second, projectedAlt)
}

internal suspend fun projectClueLocationWithDem(
    demElevationService: DemElevationService,
    droneLat: Double,
    droneLng: Double,
    droneAlt: Double,
    headingDeg: Double?,
    aglMeters: Double?,
    gimbalAngleDeg: Double,
): ClueProjection {
    return projectClueLocationWithDemSamples(
        droneLat = droneLat,
        droneLng = droneLng,
        droneAlt = droneAlt,
        headingDeg = headingDeg,
        aglMeters = aglMeters,
        gimbalAngleDeg = gimbalAngleDeg,
        sampleElevationMeters = { lat, lng ->
            demElevationService.sampleElevationMeters(lat, lng)?.elevationMeters
        },
    )
}

internal suspend fun projectClueLocationWithDemSamples(
    droneLat: Double,
    droneLng: Double,
    droneAlt: Double,
    headingDeg: Double?,
    aglMeters: Double?,
    gimbalAngleDeg: Double,
    sampleElevationMeters: suspend (Double, Double) -> Double?,
): ClueProjection {
    val flatProjection = projectClueLocation(
        droneLat = droneLat,
        droneLng = droneLng,
        droneAlt = droneAlt,
        headingDeg = headingDeg,
        aglMeters = aglMeters,
        gimbalAngleDeg = gimbalAngleDeg,
    )
    val validHeading = headingDeg?.takeIf { it.isFinite() } ?: return flatProjection
    val validAgl = aglMeters?.takeIf { it.isFinite() && it > 0.0 } ?: return flatProjection
    val clampedAngle = gimbalAngleDeg
        .takeIf { it.isFinite() }
        ?.coerceIn(-90.0, 0.0)
        ?: DEFAULT_CLUE_GIMBAL_ANGLE_DEG
    val tiltFromHorizonDeg = abs(clampedAngle).coerceIn(0.1, 90.0)
    if (tiltFromHorizonDeg >= 89.9) return flatProjection

    val slopeDown = tan(Math.toRadians(tiltFromHorizonDeg))
    if (!slopeDown.isFinite() || slopeDown <= 0.0) return flatProjection

    val flatDistanceM = validAgl / slopeDown
    if (!flatDistanceM.isFinite() || flatDistanceM <= 0.0) return flatProjection

    val flatGroundM = droneAlt - validAgl
    val droneDemRaw = sampleElevationMeters(droneLat, droneLng)?.takeIf { it.isFinite() }
    val demScaleToMeters = inferDemScaleToMeters(
        droneAltMeters = droneAlt,
        knownGroundMeters = flatGroundM,
        droneDemRaw = droneDemRaw,
    )

    val maxDistanceM = (maxOf(flatDistanceM * 3.0, flatDistanceM + 250.0)).coerceIn(60.0, 2_500.0)
    val stepM = when {
        maxDistanceM <= 180.0 -> 10.0
        maxDistanceM <= 600.0 -> 20.0
        else -> 30.0
    }

    var previousDistanceM = 0.0
    var previousPoint = Pair(droneLat, droneLng)
    var previousGroundM = flatGroundM

    var distanceM = stepM
    while (distanceM <= maxDistanceM + 0.001) {
        val candidate = destinationPoint(
            startLat = droneLat,
            startLng = droneLng,
            bearingDeg = validHeading,
            distanceM = distanceM,
        )
        val candidateDemRaw = sampleElevationMeters(candidate.first, candidate.second)
        val groundM = normalizeDemGroundMeters(
            candidateDemRaw = candidateDemRaw,
            droneDemRaw = droneDemRaw,
            flatGroundM = flatGroundM,
            demScaleToMeters = demScaleToMeters,
        ) ?: previousGroundM
        val rayAltitudeM = droneAlt - (slopeDown * distanceM)
        if (rayAltitudeM <= groundM) {
            var lowDistanceM = previousDistanceM
            var lowPoint = previousPoint
            var lowGroundM = previousGroundM
            var highDistanceM = distanceM
            var highPoint = candidate
            var highGroundM = groundM

            repeat(6) {
                val midDistanceM = (lowDistanceM + highDistanceM) / 2.0
                val midPoint = destinationPoint(
                    startLat = droneLat,
                    startLng = droneLng,
                    bearingDeg = validHeading,
                    distanceM = midDistanceM,
                )
                val midGroundM = normalizeDemGroundMeters(
                    candidateDemRaw = sampleElevationMeters(midPoint.first, midPoint.second),
                    droneDemRaw = droneDemRaw,
                    flatGroundM = flatGroundM,
                    demScaleToMeters = demScaleToMeters,
                ) ?: ((lowGroundM + highGroundM) / 2.0)
                val midRayAltitudeM = droneAlt - (slopeDown * midDistanceM)
                if (midRayAltitudeM <= midGroundM) {
                    highDistanceM = midDistanceM
                    highPoint = midPoint
                    highGroundM = midGroundM
                } else {
                    lowDistanceM = midDistanceM
                    lowPoint = midPoint
                    lowGroundM = midGroundM
                }
            }

            return ClueProjection(
                lat = highPoint.first,
                lng = highPoint.second,
                alt = highGroundM,
            )
        }
        previousDistanceM = distanceM
        previousPoint = candidate
        previousGroundM = groundM
        distanceM += stepM
    }

    return flatProjection
}

internal fun inferDemScaleToMeters(
    droneAltMeters: Double,
    knownGroundMeters: Double,
    droneDemRaw: Double?,
): Double {
    val raw = droneDemRaw?.takeIf { it.isFinite() } ?: return 1.0
    val directGroundErrorM = abs(raw - knownGroundMeters)
    val feetGroundErrorM = abs((raw * 0.3048) - knownGroundMeters)
    val directAltitudeErrorM = abs(raw - droneAltMeters)
    val feetAltitudeErrorM = abs((raw * 0.3048) - droneAltMeters)

    return if (feetGroundErrorM + feetAltitudeErrorM < directGroundErrorM + directAltitudeErrorM) {
        0.3048
    } else {
        1.0
    }
}

internal fun normalizeDemGroundMeters(
    candidateDemRaw: Double?,
    droneDemRaw: Double?,
    flatGroundM: Double,
    demScaleToMeters: Double,
): Double? {
    val candidate = candidateDemRaw?.takeIf { it.isFinite() } ?: return null
    val droneRaw = droneDemRaw?.takeIf { it.isFinite() } ?: return candidate * demScaleToMeters
    return flatGroundM + ((candidate - droneRaw) * demScaleToMeters)
}

private fun destinationPoint(
    startLat: Double,
    startLng: Double,
    bearingDeg: Double,
    distanceM: Double,
): Pair<Double, Double> {
    val earthRadiusM = 6_371_000.0
    val angularDistance = distanceM / earthRadiusM
    val bearing = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(startLat)
    val lon1 = Math.toRadians(startLng)

    val lat2 = asin(
        sin(lat1) * cos(angularDistance) +
            cos(lat1) * sin(angularDistance) * cos(bearing)
    )
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )
    return Pair(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

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

/** Display-ready values computed by [DroneAltitudeCoordinator] for use in labels and clues. */
data class DroneDisplayState(
    val headingDeg: Double?,
    val aglFt: Double?,
    /** True when the AGL value is DEM-sourced but the DEM data is stale or the drone has moved. */
    val aglStale: Boolean = false,
    /** True when aglFt is DEM-backed; false means we are falling back to ATO / flat-earth estimate. */
    val aglUsesDem: Boolean = false,
    val atoFt: Double?,
)

class StreamsViewModel(
    application: Application
) : AndroidViewModel(application),
    CtDroneSpec.DroneSpecsChangedListener,
    CaltopoMap.MapStatusListener {

    private data class CapturedVideoAppearanceGuess(
        val mode: AppearanceAnomalyMode,
        val width: Int?,
        val height: Int?,
        val grayscaleFraction: Float?,
        val reason: String,
    )

    private data class AppearanceObservationState(
        val current: AppearanceAnomalyMode,
        val pending: AppearanceAnomalyMode? = null,
        val streak: Int = 0,
    )

    private val tag = "StreamsViewModel"
    private val processLoadSampleIntervalMs = 1_000L
    private val hotProcessCpuFractionForSecondStream = 0.85
    private val hotProcessCpuFractionForThirdOrFourthStream = 0.55
    private val hotMainThreadCpuFractionForThirdOrFourthStream = 0.30
    private val processLoadLogIntervalMs = 5_000L
    private val ffmpegProbeService: FfmpegProbeService? = try {
        FfmpegProbeService(
            onLocalPlaybackEnded = { designator ->
                viewModelScope.launch {
                    val localInfo = streamInfoByDesignator[designator]
                    val exoPlayerActive = streamSessionService.playerFor(designator) != null
                    val ffmpegStillOwnsPlayback = renderRouteByDesignator[designator] == true
                    if (localInfo?.isLocalPlayback == true && !exoPlayerActive && ffmpegStillOwnsPlayback) {
                        closeStream(designator)
                    } else {
                        CTDebug(
                            tag,
                            "Ignoring FFmpeg local-playback end for $designator " +
                                "local=${localInfo?.isLocalPlayback == true} " +
                                "exoActive=$exoPlayerActive ffmpegOwns=$ffmpegStillOwnsPlayback"
                        )
                    }
                }
            }
        )
    } catch (t: Throwable) {
        CTError(tag, "FFmpeg probe service unavailable; stream playback will remain unavailable.", Exception(t))
        null
    }
    private val streamSessionService = StreamSessionService(
        context = application.applicationContext,
        scope = viewModelScope,
        // Prefer RTSP for ExoPlayer tiles. The bundled MediaMTX SDP now uses a
        // non-empty session name so ExoPlayer's RtspMediaSource can parse it.
        // This avoids the HLS muxing/playlist churn that was adding large lag and
        // repeated stalls in multi-stream scenarios.
        preferredModeProvider = { StreamSessionService.ProtocolMode.RTSP },
        // All drone video is bursty: the controller batches H.264 NAL units and sends
        // them in tight clusters with gaps of 200–800 ms between bursts.  Enable the
        // bursty-HLS tuning (larger buffer headroom, faster stall recovery) for every
        // stream so ExoPlayer handles inter-burst silences without needless buffering.
        burstyHlsSourceProvider = { true },
        sourcePathProvider = { designator -> streamInfoByDesignator[designator]?.sourcePath ?: designator },
        localPlaybackUriProvider = { designator -> streamInfoByDesignator[designator]?.playbackUri },
        listener = object : StreamSessionService.Listener {
            override fun onBuffering(designator: String) {
                _playbackIndicatorStateByDesignator[designator] = PlaybackIndicatorState.BUFFERING
            }

            override fun onLive(designator: String) {
                if (_renderDelayMsByDesignator[designator] == null &&
                    renderRouteByDesignator[designator] == false
                ) {
                    _playbackIndicatorStateByDesignator[designator] = PlaybackIndicatorState.LIVE_UNMEASURED
                } else {
                    _playbackIndicatorStateByDesignator.remove(designator)
                }
            }

            override fun onEnded(designator: String) {
                _playbackIndicatorStateByDesignator.remove(designator)
            }

            override fun onError(designator: String, error: androidx.media3.common.PlaybackException) {
                _playbackIndicatorStateByDesignator.remove(designator)
            }

            override fun onPlaybackDelayChanged(designator: String, delayMs: Long?) {
                if (delayMs == null) {
                    _renderDelayMsByDesignator.remove(designator)
                } else {
                    _renderDelayMsByDesignator[designator] = delayMs
                    if (_playbackIndicatorStateByDesignator[designator] == PlaybackIndicatorState.LIVE_UNMEASURED) {
                        _playbackIndicatorStateByDesignator.remove(designator)
                    }
                }
            }
        },
        isLocalPlaybackProvider = { designator -> streamInfoByDesignator[designator]?.isLocalPlayback == true },
    )

    private val _localPlaybackEntries = MutableStateFlow<Map<String, StreamInfo>>(emptyMap())

    val streams: StateFlow<Map<String, StreamInfo>> =
        combine(StreamRegistry.streams, _localPlaybackEntries) { liveStreams, localPlaybackEntries ->
            buildMap<String, StreamInfo> {
                putAll(liveStreams)
                putAll(localPlaybackEntries)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    private val _focusedPath = MutableStateFlow<String?>(null)
    val focusedPath: StateFlow<String?> = _focusedPath.asStateFlow()
    private val _streamsUiActive = MutableStateFlow(false)
    private val streamsUiConsumerLock = Any()
    private val streamsUiConsumers = mutableSetOf<Any>()

    private val _droneStates = mutableStateMapOf<String, DroneSpecState>()
    val droneStates: Map<String, DroneSpecState> get() = _droneStates
    private val _anomalyConfigByDesignator = mutableStateMapOf<String, AnomalyConfig>()
    private val _detectedAppearanceModeByDesignator = mutableStateMapOf<String, AppearanceAnomalyMode>()
    private val appearanceObservationStateByDesignator = mutableMapOf<String, AppearanceObservationState>()
    private var defaultAnomalyConfig by mutableStateOf(AnomalyConfig())
    private var lastCapturedVideoSelectionUri: Uri? by mutableStateOf(null)
    private val _renderDelayMsByDesignator = mutableStateMapOf<String, Long>()
    private val _playbackIndicatorStateByDesignator = mutableStateMapOf<String, PlaybackIndicatorState>()
    private val renderRouteByDesignator = mutableStateMapOf<String, Boolean>()
    private val streamInfoByDesignator = mutableMapOf<String, StreamInfo>()
    private val dismissedStreamRevisions = mutableStateMapOf<String, Long>()
    private var latestProcessLoadSnapshot by mutableStateOf<ProcessLoadSnapshot?>(null)
    private var lastProcessLoadLogAtMs = 0L
    /** Coordinator that owns all DEM / AGL / ATO / heading computation. */
    internal val altitudeCoordinator = DroneAltitudeCoordinator(
        scope = viewModelScope,
        appContext = application.applicationContext,
        droneStates = _droneStates,
    )

    /**
     * Register a UI consumer so the coordinator's update loop stays alive.
     * Call from a [DisposableEffect] and invoke the returned lambda from [onDispose].
     */
    fun addAltitudeConsumer(): () -> Unit = altitudeCoordinator.addConsumer()

    fun droneDisplayStateFor(designator: String): DroneDisplayState? =
        altitudeCoordinator.displayStateByDesignator[designator]

    /**
     * No-op stub retained for any call sites not yet migrated.
     * @deprecated Coordinator now owns display state; callers should be removed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateDroneDisplayState(designator: String, headingDeg: Double?, aglFt: Double?, atoFt: Double?) = Unit

    // --- Map Folders visibility state ---
    // Persisted in the ViewModel so user selections survive navigation away and back.

    /** Folder IDs whose contents should be hidden on the local map. */
    val hiddenFolderIds = mutableStateSetOf<String>()

    /** Individual feature IDs hidden regardless of their folder's visibility. */
    val hiddenItemIds = mutableStateSetOf<String>()

    /**
     * Tracks which folder IDs have already had their Caltopo default visibility applied,
     * so we don't override the user's manual selections on re-entry.
     */
    private val seenFolderIds = HashSet<String>()

    /**
     * Called when a Folder feature is first encountered in the artifact stream.
     * Applies the Caltopo server's `visible` flag as the initial default, but only once
     * per folder ID so that user overrides survive map reconnects and screen navigation.
     */
    fun applyCaltopoFolderDefault(folderId: String, caltopoVisible: Boolean) {
        if (folderId in seenFolderIds) return
        seenFolderIds.add(folderId)
        if (!caltopoVisible) hiddenFolderIds.add(folderId)
    }

    /** Clears all folder/item visibility state and the seen-folder registry (e.g. on map disconnect). */
    fun resetFolderVisibility() {
        hiddenFolderIds.clear()
        hiddenItemIds.clear()
        seenFolderIds.clear()
    }

    private val _pendingClue = mutableStateOf<PendingClue?>(null)
    val pendingClue: PendingClue?
        get() = _pendingClue.value

    private val _mapName = mutableStateOf<String?>(null)
    val mapName: String? by _mapName
    private val _layoutMode = MutableStateFlow(StreamsLayoutMode.Both)
    val layoutMode: StateFlow<StreamsLayoutMode> = _layoutMode.asStateFlow()
    private val _proximityMapFocusTarget = MutableStateFlow<ProximityMapFocusTarget?>(null)
    val proximityMapFocusTarget: StateFlow<ProximityMapFocusTarget?> = _proximityMapFocusTarget.asStateFlow()
    private val _coordinateDisplayFormat = mutableStateOf<CoordinateDisplayFormat>(
        CoordinateDisplayFormat.fromStorage(CaltopoClient.GetCoordinateDisplayFormat())
    )
    val coordinateDisplayFormat: CoordinateDisplayFormat
        get() = _coordinateDisplayFormat.value
    private val _baseLayer = mutableStateOf(org.ncssar.rid2caltopo.video.BaseLayerOption.OpenStreetMap)
    internal val baseLayer: org.ncssar.rid2caltopo.video.BaseLayerOption
        get() = _baseLayer.value
    private var persistedMapViewportState: MapViewportState? = null
    private var clueProjectionJob: Job? = null
    private val mutedComplianceAlertDesignators = mutableStateSetOf<String>()
    private val _overLimitDrones = MutableStateFlow<List<OverLimitDroneUiState>>(emptyList())
    val overLimitDrones: StateFlow<List<OverLimitDroneUiState>> = _overLimitDrones.asStateFlow()

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
        mutedComplianceAlertDesignators.retainAll(_droneStates.keys)
        val overLimit = _droneStates.mapNotNull { (designator, _) ->
            val displayState = altitudeCoordinator.displayStateByDesignator[designator] ?: return@mapNotNull null
            val aglFt = displayState.aglFt ?: return@mapNotNull null
            if (aglFt < COMPLIANCE_ALERT_AGL_LIMIT_FT) return@mapNotNull null
            OverLimitDroneUiState(
                mappedId = designator,
                aglFt = aglFt,
                thresholdFt = COMPLIANCE_ALERT_AGL_LIMIT_FT,
                muted = designator in mutedComplianceAlertDesignators,
                staleDem = displayState.aglStale,
                usingDemAgl = displayState.aglUsesDem,
                atoFt = displayState.atoFt
            )
        }.sortedByDescending { it.aglFt }
        _overLimitDrones.value = overLimit
        ComplianceAlertCenter.updateCandidates(
            overLimit
                .filterNot { it.muted }
                .map {
                    ComplianceAlertCandidate(
                        mappedId = it.mappedId,
                        aglFt = it.aglFt,
                        thresholdFt = it.thresholdFt,
                        staleDem = it.staleDem
                    )
                }
        )
    }

    fun setComplianceAlertMuted(mappedId: String, muted: Boolean) {
        if (muted) {
            mutedComplianceAlertDesignators.add(mappedId)
        } else {
            mutedComplianceAlertDesignators.remove(mappedId)
        }
        _overLimitDrones.value = _overLimitDrones.value.map { drone ->
            if (drone.mappedId == mappedId) drone.copy(muted = muted) else drone
        }
        ComplianceAlertCenter.updateCandidates(
            _overLimitDrones.value
                .filterNot { it.muted }
                .map {
                    ComplianceAlertCandidate(
                        mappedId = it.mappedId,
                        aglFt = it.aglFt,
                        thresholdFt = it.thresholdFt,
                        staleDem = it.staleDem
                    )
                }
        )
    }

    fun isStreamVisible(stream: StreamInfo): Boolean {
        return dismissedStreamRevisions[stream.designator] != stream.revision
    }

    fun isLocalPlayback(designator: String): Boolean {
        return streamInfoByDesignator[designator]?.isLocalPlayback == true
    }

    fun useFfmpegRender(designator: String): Boolean {
        return renderRouteByDesignator[designator] == true
    }

    fun bindFfmpegRenderSurface(designator: String, surface: Surface): Boolean {
        return ffmpegProbeService?.bindRenderSurface(designator, surface) == true
    }

    fun unbindFfmpegRenderSurface(designator: String, surface: Surface?) {
        ffmpegProbeService?.unbindRenderSurface(designator, surface)
    }

    fun getExoPlayerFor(designator: String): ExoPlayer? = streamSessionService.playerFor(designator)

    fun capturedVideoPickerInitialUri(): Uri? {
        return lastCapturedVideoSelectionUri
            ?: CaltopoClient.GetTodaysTrackDir()?.uri
            ?: CaltopoClient.GetArchiveUri()
            ?: CaltopoClient.GetArchiveUriSelectionHint()
    }

    fun openCapturedVideo(uri: Uri, displayName: String) {
        lastCapturedVideoSelectionUri = uri
        val normalizedName = displayName.ifBlank { "Captured Video" }
        val existingNames = buildSet {
            addAll(streams.value.keys)
            addAll(_localPlaybackEntries.value.keys)
        }
        var designator = normalizedName
        var suffix = 2
        while (designator in existingNames) {
            designator = "$normalizedName ($suffix)"
            suffix += 1
        }

        val pendingInfo = StreamInfo(
            designator = designator,
            sourcePath = uri.toString(),
            playbackUri = uri,
            isLocalPlayback = true,
            state = StreamState.CONNECTING,
            revision = 1L,
        )
        _localPlaybackEntries.value = _localPlaybackEntries.value.toMutableMap().apply {
            this[designator] = pendingInfo
        }
        _focusedPath.value = designator
        syncStreamSessions(currentResyncSnapshot())
        viewModelScope.launch(Dispatchers.IO) {
            val resolvedInfo = try {
                val cachedUri = stageCapturedVideoForPlayback(designator, uri, displayName)
                val appearanceGuess = guessCapturedVideoAppearance(cachedUri)
                CTDebug(
                    tag,
                    "Captured video appearance guess for $designator: mode=${appearanceGuess.mode.label} " +
                        "size=${appearanceGuess.width ?: -1}x${appearanceGuess.height ?: -1} " +
                        "gray=${appearanceGuess.grayscaleFraction?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"} " +
                        "reason=${appearanceGuess.reason}"
                )
                withContext(Dispatchers.Main) {
                    _detectedAppearanceModeByDesignator[designator] = appearanceGuess.mode
                }
                pendingInfo.copy(
                    sourcePath = cachedUri.toString(),
                    playbackUri = cachedUri,
                    state = StreamState.LIVE,
                    revision = pendingInfo.revision + 1L,
                )
            } catch (t: Throwable) {
                pendingInfo.copy(
                    state = StreamState.ERROR,
                    errorDetail = t.message ?: "Unable to prepare captured video",
                    revision = pendingInfo.revision + 1L,
                )
            }
            withContext(Dispatchers.Main) {
                val current = _localPlaybackEntries.value[designator] ?: return@withContext
                _localPlaybackEntries.value = _localPlaybackEntries.value.toMutableMap().apply {
                    this[designator] = resolvedInfo.copy(revision = maxOf(current.revision + 1L, resolvedInfo.revision))
                }
                syncStreamSessions(currentResyncSnapshot())
            }
        }
    }

    fun closeStream(designator: String) {
        _localPlaybackEntries.value[designator]?.let { localInfo ->
            _detectedAppearanceModeByDesignator.remove(designator)
            appearanceObservationStateByDesignator.remove(designator)
            _localPlaybackEntries.value = _localPlaybackEntries.value.toMutableMap().apply {
                remove(designator)
            }
            localInfo.playbackUri
                ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
                ?.path
                ?.let { path ->
                    runCatching { File(path).delete() }
                }
            dismissedStreamRevisions.remove(designator)
            if (_focusedPath.value == designator) {
                _focusedPath.value = null
            }
            applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
            syncStreamSessions(currentResyncSnapshot())
            CaltopoClient.ShowToast("Closed $designator.")
            return
        }
        if (_focusedPath.value != designator) {
            _focusedPath.value = designator
        }
        dismissFocusedStream()
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
        syncStreamSessions(currentResyncSnapshot())
    }

    fun ensureFocus(designator: String) {
        if (_focusedPath.value == designator) return
        _focusedPath.value = designator
        CTDebug(tag, "ensureFocus(): $designator has focus.")
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
    }

    fun addStreamsUiConsumer(): () -> Unit {
        val token = Any()
        var shouldSync = false
        synchronized(streamsUiConsumerLock) {
            streamsUiConsumers.add(token)
            if (!_streamsUiActive.value) {
                _streamsUiActive.value = true
                shouldSync = true
            }
            CTDebug(tag, "addStreamsUiConsumer(): count=${streamsUiConsumers.size}")
        }
        if (shouldSync) {
            syncStreamSessions(currentResyncSnapshot())
        }
        return {
            var becameInactive = false
            synchronized(streamsUiConsumerLock) {
                if (!streamsUiConsumers.remove(token)) return@synchronized
                CTDebug(tag, "removeStreamsUiConsumer(): count=${streamsUiConsumers.size}")
                if (streamsUiConsumers.isEmpty() && _streamsUiActive.value) {
                    _streamsUiActive.value = false
                    becameInactive = true
                }
            }
            if (becameInactive) {
                syncStreamSessions(currentResyncSnapshot())
            }
        }
    }

    override fun onCleared() {
        StreamRegistry.setAdmissionGuard(null)
        CaltopoMap.RemoveMapStatusListener(this)
        ffmpegProbeService?.close()
        streamSessionService.releaseAll()
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
            resetFolderVisibility()
        }
    }

    fun designatorStateFor(designator: String): DesignatorState {
        if (isLocalPlayback(designator)) return DesignatorState.Red
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

    fun playbackIndicatorStateFor(designator: String): PlaybackIndicatorState? {
        return _playbackIndicatorStateByDesignator[designator]
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

    fun setLayoutMode(layoutMode: StreamsLayoutMode) {
        _layoutMode.value = layoutMode
    }

    fun showMapOnly() {
        _layoutMode.value = StreamsLayoutMode.Map
    }

    fun requestProximityMapFocus(firstLat: Double, firstLng: Double, secondLat: Double, secondLng: Double) {
        _proximityMapFocusTarget.value = ProximityMapFocusTarget(
            requestId = System.currentTimeMillis(),
            firstLat = firstLat,
            firstLng = firstLng,
            secondLat = secondLat,
            secondLng = secondLng
        )
    }

    fun clearProximityMapFocus(requestId: Long) {
        if (_proximityMapFocusTarget.value?.requestId == requestId) {
            _proximityMapFocusTarget.value = null
        }
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
        val displayState = altitudeCoordinator.displayStateByDesignator[designator]
        val headingSelection = selectClueHeading(
            telemetry = telemetry,
            displayHeadingDeg = displayState?.headingDeg,
            ridTrackDeg = droneSpec.lastPositionTelemetry?.aircraftTrackDeg,
        )
        val clueBearing = headingSelection.headingDeg
        val clueAglMeters = displayState?.aglFt?.div(METERS_TO_FEET)
        val clueAtoMeters = displayState?.atoFt?.div(METERS_TO_FEET)
        val clueGimbalAngle = telemetry?.gimbalPitchDeg
            ?.takeIf { it.isFinite() }
            ?.coerceIn(-90.0, 0.0)
            ?: DEFAULT_CLUE_GIMBAL_ANGLE_DEG
        val projectedLocation = projectClueLocation(
            droneLat = clueLat,
            droneLng = clueLng,
            droneAlt = clueAlt,
            headingDeg = clueBearing,
            aglMeters = clueAglMeters,
            gimbalAngleDeg = clueGimbalAngle,
        )
        CTDebug(tag, String.format(
            Locale.US,
            "onSnapshotCaptured(%s): projection inputs droneLat=%.6f droneLng=%.6f droneAlt=%.1f bearingDeg=%s aglM=%s gimbalDeg=%s projectedLat=%.6f projectedLng=%.6f projectedAlt=%.1f",
            designator,
            clueLat,
            clueLng,
            clueAlt,
            clueBearing?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clueAglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            String.format(Locale.US, "%.1f", clueGimbalAngle),
            projectedLocation.lat,
            projectedLocation.lng,
            projectedLocation.alt,
        ))
        val summary = buildTelemetrySummary(designator, droneSpec, telemetry)

        _pendingClue.value = PendingClue(
            droneSpec = droneSpec,
            designator = designator,
            droneLat = clueLat,
            droneLng = clueLng,
            droneAlt = clueAlt,
            lat = projectedLocation.lat,
            lng = projectedLocation.lng,
            alt = projectedLocation.alt,
            headingDeg = clueBearing,
            headingSourceLabel = headingSelection.sourceLabel,
            aglMeters = clueAglMeters,
            atoMeters = clueAtoMeters,
            gimbalAngleDeg = clueGimbalAngle,
            timestamp = clueTimestamp,
            bitmap = bitmap,
            preview = null,
            title = "",
            description = buildClueDescriptionTemplate(clueTimestamp),
            streamTelemetrySummary = summary
        )

        requestDemClueProjectionRefresh(designator)

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

    fun updateClueGimbalAngle(gimbalAngleDeg: Double) {
        _pendingClue.value = _pendingClue.value?.let { clue ->
            val projection = projectClueLocation(
                droneLat = clue.droneLat,
                droneLng = clue.droneLng,
                droneAlt = clue.droneAlt,
                headingDeg = clue.headingDeg,
                aglMeters = clue.aglMeters,
                gimbalAngleDeg = gimbalAngleDeg,
            )
            CTDebug(tag, String.format(
                Locale.US,
                "updateClueGimbalAngle(): designator=%s bearingDeg=%s aglM=%s gimbalDeg=%.1f projectedLat=%.6f projectedLng=%.6f projectedAlt=%.1f",
                clue.designator,
                clue.headingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                clue.aglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                gimbalAngleDeg,
                projection.lat,
                projection.lng,
                projection.alt,
            ))
            clue.copy(
                lat = projection.lat,
                lng = projection.lng,
                alt = projection.alt,
                gimbalAngleDeg = gimbalAngleDeg,
            )
        }?.also { requestDemClueProjectionRefresh(it.designator) }
    }

    fun submitClue() {
        val clue = pendingClue ?: return
        CTDebug(tag, String.format(
            Locale.US,
            "submitting clue: '%s' for '%s' clueLat=%.6f clueLng=%.6f clueAlt=%.1f droneLat=%.6f droneLng=%.6f droneAlt=%.1f headingDeg=%s aglM=%s atoM=%s gimbalDeg=%.1f",
            clue.title,
            clue.droneSpec.trackLabel(),
            clue.lat,
            clue.lng,
            clue.alt,
            clue.droneLat,
            clue.droneLng,
            clue.droneAlt,
            clue.headingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clue.aglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clue.atoMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
            clue.gimbalAngleDeg,
        ))
        val withCaptureSummary = appendTelemetrySummary(clue.description, buildClueCaptureSummary(clue))
        val finalDescription = appendTelemetrySummary(withCaptureSummary, clue.streamTelemetrySummary)
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
        clueProjectionJob?.cancel()
        clueProjectionJob = null
        _pendingClue.value = null
    }

    private fun requestDemClueProjectionRefresh(designator: String) {
        val clue = _pendingClue.value ?: return
        if (clue.designator != designator) return
        clueProjectionJob?.cancel()
        clueProjectionJob = viewModelScope.launch(Dispatchers.Default) {
            val refined = projectClueLocationWithDem(
                demElevationService = altitudeCoordinator.demElevationService,
                droneLat = clue.droneLat,
                droneLng = clue.droneLng,
                droneAlt = clue.droneAlt,
                headingDeg = clue.headingDeg,
                aglMeters = clue.aglMeters,
                gimbalAngleDeg = clue.gimbalAngleDeg,
            )
            withContext(Dispatchers.Main) {
                val current = _pendingClue.value ?: return@withContext
                if (current.designator != clue.designator ||
                    current.timestamp != clue.timestamp ||
                    current.gimbalAngleDeg != clue.gimbalAngleDeg) {
                    return@withContext
                }
                _pendingClue.value = current.copy(
                    lat = refined.lat,
                    lng = refined.lng,
                    alt = refined.alt,
                )
                CTDebug(tag, String.format(
                    Locale.US,
                    "requestDemClueProjectionRefresh(%s): refined projectedLat=%.6f projectedLng=%.6f projectedAlt=%.1f headingDeg=%s aglM=%s gimbalDeg=%.1f",
                    clue.designator,
                    refined.lat,
                    refined.lng,
                    refined.alt,
                    clue.headingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                    clue.aglMeters?.let { String.format(Locale.US, "%.1f", it) } ?: "null",
                    clue.gimbalAngleDeg,
                ))
            }
        }
    }

    fun anomalyConfigFor(designator: String): AnomalyConfig {
        return _anomalyConfigByDesignator[designator] ?: defaultAnomalyConfig
    }

    fun resolvedAppearanceModeFor(designator: String): AppearanceAnomalyMode {
        val config = anomalyConfigFor(designator)
        return config.resolvedAppearanceMode(_detectedAppearanceModeByDesignator[designator])
    }

    fun observeRenderedAppearance(designator: String, bitmap: Bitmap) {
        val guess = classifyAppearanceFromBitmap(bitmap.width, bitmap.height, bitmap)
        val previous = appearanceObservationStateByDesignator[designator]
        val nextState = when {
            previous == null -> AppearanceObservationState(current = guess.mode)
            previous.current == guess.mode -> AppearanceObservationState(current = guess.mode)
            previous.pending == guess.mode -> {
                val streak = previous.streak + 1
                if (streak >= 3) {
                    AppearanceObservationState(current = guess.mode)
                } else {
                    previous.copy(streak = streak)
                }
            }
            else -> previous.copy(pending = guess.mode, streak = 1)
        }
        appearanceObservationStateByDesignator[designator] = nextState
        val resolved = nextState.current
        if (_detectedAppearanceModeByDesignator[designator] != resolved) {
            _detectedAppearanceModeByDesignator[designator] = resolved
            CTDebug(
                tag,
                "Observed stream appearance for $designator -> ${resolved.label} " +
                    "size=${guess.width ?: -1}x${guess.height ?: -1} gray=${guess.grayscaleFraction?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"} reason=${guess.reason}"
            )
            applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        }
    }

    fun toggleAnomalyEnabled(designator: String) {
        updateAnomalyConfig(designator) { current ->
            current.copy(enabled = !current.enabled)
        }
    }

    fun toggleAnomalyAlgorithm(designator: String, algorithm: AnomalyAlgorithm) {
        updateAnomalyConfig(designator) { current ->
            current.toggledAlgorithm(algorithm)
        }
    }

    fun setAppearanceAnomalySelection(designator: String, selection: AppearanceAnomalySelection) {
        updateAnomalyConfig(designator) { current ->
            current.withAppearanceSelection(selection)
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

    fun setAnomalyPixelStep(designator: String, pixelStep: Int) {
        updateAnomalyConfig(designator) { current ->
            current.copy(pixelStep = pixelStep.coerceIn(0, 8))
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

    fun setScanZone(designator: String, scanZone: Float) {
        updateAnomalyConfig(designator) { current ->
            current.copy(scanZone = scanZone.coerceIn(0.5f, 1.0f))
        }
    }

    fun cycleMinHits(designator: String) {
        updateAnomalyConfig(designator) { current ->
            val next = if (current.minHits >= 5) 1 else current.minHits + 1
            current.copy(minHits = next)
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
        lines += "Telemetry:"
        lines += String.format(
            Locale.US,
            "  Drone position: %.6f, %.6f alt %.0f'",
            droneSpec.lastLat,
            droneSpec.lastLng,
            droneSpec.lastAlt * METERS_TO_FEET,
        )

        // First three: Heading, AGL, ATO — use values computed by DroneAltitudeCoordinator
        val display = altitudeCoordinator.displayStateByDesignator[designator]
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
                val altText = telemetry.altitudeMeters?.let {
                    String.format(Locale.US, ", alt=%.0f'", it * METERS_TO_FEET)
                } ?: ""
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
        val trimmedDescription = description.trimEnd()
        if (trimmedDescription.isBlank()) return summary
        return "$trimmedDescription\n\n$summary"
    }

    private fun shouldUseFfmpegRender(designator: String): Boolean {
        val info = streamInfoByDesignator[designator]
        if (info?.isLocalPlayback == true) {
            val config = _anomalyConfigByDesignator[designator] ?: defaultAnomalyConfig
            return ffmpegProbeService != null &&
                config.enabled &&
                _focusedPath.value == designator &&
                displayedTileCountForCurrentLayout() == 1 &&
                info.state == StreamState.LIVE
        }
        return StreamRenderRouter.useFfmpeg(
            designator = designator,
            liveStreams = streamInfoByDesignator,
            focusedDesignator = _focusedPath.value,
            ffmpegAvailable = ffmpegProbeService != null,
            displayedTileCount = displayedTileCountForCurrentLayout(),
        )
    }

    private fun displayedTileCountForCurrentLayout(): Int {
        if (!_streamsUiActive.value) return 0
        if (_layoutMode.value == StreamsLayoutMode.Map) return 0
        val visibleLiveCount = streamInfoByDesignator.values.count { info ->
            info.state == StreamState.LIVE && isStreamVisible(info)
        }
        if (visibleLiveCount <= 0) return 0
        return if (_focusedPath.value != null) 1 else visibleLiveCount
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
        val streamsUiActive = _streamsUiActive.value
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
        if (streamsUiActive && focusedPath != null) {
            val newlyAttachedOffFocus = added.filter { it != focusedPath }
            if (newlyAttachedOffFocus.isNotEmpty()) {
                val msg = if (newlyAttachedOffFocus.size == 1) {
                    "New stream attached: ${newlyAttachedOffFocus.first()}"
                } else {
                    "New streams attached: ${newlyAttachedOffFocus.joinToString(", ")}"
                }
                CaltopoClient.ShowToast(msg)
                CTInfo(tag, "$msg -> keeping current focus")
            }
        }

        val removed = lastLiveRevisions.keys - liveDesignators
        removed.forEach { designator ->
            _detectedAppearanceModeByDesignator.remove(designator)
            appearanceObservationStateByDesignator.remove(designator)
            CTDebug(tag, "Stream $designator no longer live -> stop render")
            renderRouteByDesignator.remove(designator)
            _playbackIndicatorStateByDesignator.remove(designator)
            ffmpegProbeService?.setRenderEnabled(designator, false)
            ffmpegProbeService?.onStreamStopped(designator)
            streamSessionService.onStreamStopped(designator)
        }

        dismissedLiveDesignators.forEach { designator ->
            renderRouteByDesignator[designator] = false
            _playbackIndicatorStateByDesignator.remove(designator)
            ffmpegProbeService?.setRenderEnabled(designator, false)
            ffmpegProbeService?.onStreamStopped(designator)
            streamSessionService.onStreamStopped(designator)
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
            val useFfmpeg = streamsUiActive && shouldUseFfmpegRender(designator)
            val wasUsingFfmpeg = renderRouteByDesignator[designator] == true
            ffmpegProbeService?.updateSourcePath(designator, info.sourcePath)
            renderRouteByDesignator[designator] = useFfmpeg
            ffmpegProbeService?.setRenderEnabled(designator, useFfmpeg)
            if (!streamsUiActive) {
                ffmpegProbeService?.onStreamStopped(designator)
                streamSessionService.onStreamStopped(designator)
                CTDebug(tag, "Stream $designator live -> streams UI inactive, discarding packets")
                return@forEach
            }
            if (useFfmpeg) {
                _playbackIndicatorStateByDesignator.remove(designator)
                streamSessionService.onStreamStopped(designator)
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
            } else {
                if (wasUsingFfmpeg) {
                    ffmpegProbeService?.suspendRender(designator)
                }
                // If another stream is focused, suspend this one's ExoPlayer to save CPU.
                // Re-read _focusedPath.value here in case it was cleared above (e.g. a new
                // off-focus stream arriving auto-dismissed focus and returned to grid view).
                val currentFocus = _focusedPath.value
                if (currentFocus != null && currentFocus != designator) {
                    if (streamSessionService.playerFor(designator) != null) {
                        _playbackIndicatorStateByDesignator.remove(designator)
                        streamSessionService.onStreamStopped(designator)
                        CTDebug(tag, "Stream $designator -> ExoPlayer suspended (focus is on $currentFocus)")
                    }
                } else {
                    if (republishDetected) {
                        streamSessionService.onStreamRepublished(designator)
                        CTDebug(tag, "Stream $designator live -> using ExoPlayer render path (republished)")
                    } else if (newlyLive || wasUsingFfmpeg || streamSessionService.playerFor(designator) == null) {
                        streamSessionService.onStreamBecameLive(designator)
                        CTDebug(tag, "Stream $designator live -> using ExoPlayer render path")
                    }
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
        syncStreamSessions(currentResyncSnapshot())
    }

    fun dismissFocusedStream() {
        val designator = _focusedPath.value ?: return
        if (_localPlaybackEntries.value.containsKey(designator)) {
            closeStream(designator)
            return
        }
        val info = streams.value[designator] ?: return
        dismissedStreamRevisions[designator] = info.revision
        CTDebug(tag, "dismissFocusedStream(): hiding $designator at revision=${info.revision}")
        _focusedPath.value = null
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
        CaltopoClient.ShowToast("Closed $designator until it republishes.")
    }

    private fun updateAnomalyConfig(
        designator: String,
        reducer: (AnomalyConfig) -> AnomalyConfig,
    ) {
        val current = _anomalyConfigByDesignator[designator] ?: defaultAnomalyConfig
        val updated = reducer(current)
        _anomalyConfigByDesignator[designator] = updated
        defaultAnomalyConfig = updated
        AnomalyPrefs.save(getApplication<Application>().applicationContext, updated)
        applyFocusedAnomalyPolicy(lastLiveRevisions.keys)
        syncStreamSessions(currentResyncSnapshot())
    }

    private fun currentResyncSnapshot(): Map<String, StreamInfo> {
        val latestDirectSnapshot = buildMap<String, StreamInfo> {
            putAll(StreamRegistry.streams.value)
            putAll(_localPlaybackEntries.value)
        }
        return chooseResyncSnapshot(
            lastSyncedStreams = streamInfoByDesignator.toMap(),
            latestFlowValue = latestDirectSnapshot,
        )
    }

    private fun startProcessLoadMonitor() {
        viewModelScope.launch {
            var lastObservedAtMs = 0L
            var lastProcessCpuMs = 0L
            var lastMainThreadCpuNs = 0L
            var lastMediaMtxPid = 0
            var lastMediaMtxCpuTicks = 0L
            var smoothedProcessCpuFraction = 0.0
            var smoothedMainThreadCpuFraction = 0.0
            var smoothedMediaMtxCpuFraction = 0.0
            val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            while (true) {
                kotlinx.coroutines.delay(processLoadSampleIntervalMs)
                val nowMs = System.currentTimeMillis()
                val processCpuMs = Process.getElapsedCpuTime()
                val mainThreadCpuNs = Debug.threadCpuTimeNanos()
                val mediaMtxPid = if (MediaMTXService.IsRunning()) MediaMTXService.findNativeServerPid() else 0
                val mediaMtxCpuTicks = if (mediaMtxPid > 0) readProcCpuTicks(mediaMtxPid) else null
                if (lastObservedAtMs != 0L) {
                    val windowMs = nowMs - lastObservedAtMs
                    if (windowMs > 0L) {
                        val rawProcessCpuFraction =
                            ((processCpuMs - lastProcessCpuMs).toDouble() / windowMs.toDouble() / cpuCount.toDouble())
                                .coerceIn(0.0, 1.0)
                        val rawMainThreadCpuFraction =
                            (((mainThreadCpuNs - lastMainThreadCpuNs) / 1_000_000.0) / windowMs.toDouble())
                                .coerceIn(0.0, 1.0)
                        smoothedProcessCpuFraction = if (smoothedProcessCpuFraction == 0.0) {
                            rawProcessCpuFraction
                        } else {
                            (smoothedProcessCpuFraction * 0.7) + (rawProcessCpuFraction * 0.3)
                        }
                        smoothedMainThreadCpuFraction = if (smoothedMainThreadCpuFraction == 0.0) {
                            rawMainThreadCpuFraction
                        } else {
                            (smoothedMainThreadCpuFraction * 0.7) + (rawMainThreadCpuFraction * 0.3)
                        }
                        val rawMediaMtxCpuFraction = if (mediaMtxPid > 0 &&
                            mediaMtxPid == lastMediaMtxPid &&
                            mediaMtxCpuTicks != null &&
                            lastMediaMtxCpuTicks > 0L) {
                            (((mediaMtxCpuTicks - lastMediaMtxCpuTicks).toDouble() * 10.0) /
                                windowMs.toDouble() / cpuCount.toDouble()).coerceIn(0.0, 1.0)
                        } else {
                            0.0
                        }
                        smoothedMediaMtxCpuFraction = if (smoothedMediaMtxCpuFraction == 0.0) {
                            rawMediaMtxCpuFraction
                        } else {
                            (smoothedMediaMtxCpuFraction * 0.7) + (rawMediaMtxCpuFraction * 0.3)
                        }
                        val runtime = Runtime.getRuntime()
                        val javaHeapUsedMb =
                            ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)).coerceAtLeast(0L)
                        val javaHeapMaxMb = (runtime.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
                        val nativeHeapUsedMb =
                            (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).coerceAtLeast(0L)
                        val liveStreamCount = streamInfoByDesignator.values.count { it.state == StreamState.LIVE }
                        val ffmpegStreamCount = streamInfoByDesignator.values.count { info ->
                            info.state == StreamState.LIVE && shouldUseFfmpegRender(info.designator)
                        }
                        val anomalyEnabledCount = _anomalyConfigByDesignator.values.count { it.enabled }
                        val thermalStatusLabel = currentThermalStatusLabel()
                        val anomalyHeadroomLabel = estimateAnomalyHeadroom(
                            processCpuFraction = smoothedProcessCpuFraction,
                            mainThreadCpuFraction = smoothedMainThreadCpuFraction,
                            thermalStatusLabel = thermalStatusLabel,
                            liveStreamCount = liveStreamCount,
                            ffmpegStreamCount = ffmpegStreamCount,
                            anomalyEnabledCount = anomalyEnabledCount,
                        )
                        latestProcessLoadSnapshot = ProcessLoadSnapshot(
                            observedAtMs = nowMs,
                            sampleWindowMs = windowMs,
                            processCpuFraction = smoothedProcessCpuFraction,
                            mainThreadCpuFraction = smoothedMainThreadCpuFraction,
                            javaHeapUsedMb = javaHeapUsedMb,
                            javaHeapMaxMb = javaHeapMaxMb,
                            nativeHeapUsedMb = nativeHeapUsedMb,
                            mediaMtxPid = mediaMtxPid,
                            mediaMtxCpuFraction = smoothedMediaMtxCpuFraction,
                            liveStreamCount = liveStreamCount,
                            ffmpegStreamCount = ffmpegStreamCount,
                            anomalyEnabledCount = anomalyEnabledCount,
                            thermalStatusLabel = thermalStatusLabel,
                            anomalyHeadroomLabel = anomalyHeadroomLabel,
                        )
                        if (liveStreamCount > 0 && nowMs - lastProcessLoadLogAtMs >= processLoadLogIntervalMs) {
                            lastProcessLoadLogAtMs = nowMs
                            CTDebug(
                                tag,
                                String.format(
                                    Locale.US,
                                    "Device load: cpu=%d%% mediamtx=%d%% ui=%d%% java=%d/%dMB native=%dMB live=%d ffmpeg=%d anomaly=%d thermal=%s headroom=%s windowMs=%d",
                                    (smoothedProcessCpuFraction * 100.0).toInt(),
                                    (smoothedMediaMtxCpuFraction * 100.0).toInt(),
                                    (smoothedMainThreadCpuFraction * 100.0).toInt(),
                                    javaHeapUsedMb,
                                    javaHeapMaxMb,
                                    nativeHeapUsedMb,
                                    liveStreamCount,
                                    ffmpegStreamCount,
                                    anomalyEnabledCount,
                                    thermalStatusLabel,
                                    anomalyHeadroomLabel,
                                    windowMs,
                                )
                            )
                        }
                    }
                }
                lastObservedAtMs = nowMs
                lastProcessCpuMs = processCpuMs
                lastMainThreadCpuNs = mainThreadCpuNs
                lastMediaMtxPid = mediaMtxPid
                lastMediaMtxCpuTicks = mediaMtxCpuTicks ?: 0L
            }
        }
    }

    private fun admissionGuardDecision(
        state: StreamAdmissionState,
        designator: String,
        targetState: StreamState,
    ): StreamAdmissionGuardResult {
        if (targetState != StreamState.CONNECTING && targetState != StreamState.LIVE) {
            return StreamAdmissionGuardResult(allow = true)
        }
        val activeCount = state.active.size
        val projectedCount = activeCount + 1
        val snapshot = latestProcessLoadSnapshot ?: return StreamAdmissionGuardResult(allow = true)
        val sampleAgeMs = System.currentTimeMillis() - snapshot.observedAtMs
        if (sampleAgeMs > processLoadSampleIntervalMs * 2) {
            return StreamAdmissionGuardResult(allow = true)
        }
        if (projectedCount >= 3 &&
            (snapshot.processCpuFraction >= hotProcessCpuFractionForThirdOrFourthStream ||
                snapshot.mainThreadCpuFraction >= hotMainThreadCpuFractionForThirdOrFourthStream)
        ) {
            val processPct = (snapshot.processCpuFraction * 100.0).toInt()
            return StreamAdmissionGuardResult(
                allow = false,
                reason = "load_guard",
                toastMessage = "Rejected stream $designator (device load ${processPct}% too high for stream $projectedCount).",
            )
        }
        if (projectedCount == 2 && snapshot.processCpuFraction >= hotProcessCpuFractionForSecondStream) {
            val processPct = (snapshot.processCpuFraction * 100.0).toInt()
            return StreamAdmissionGuardResult(
                allow = false,
                reason = "load_guard",
                toastMessage = "Rejected stream $designator (device load ${processPct}% too high for a second stream).",
            )
        }
        return StreamAdmissionGuardResult(allow = true)
    }

    fun deviceLoadOverlayText(): String? {
        val snapshot = latestProcessLoadSnapshot ?: return null
        val ageMs = System.currentTimeMillis() - snapshot.observedAtMs
        if (ageMs > processLoadSampleIntervalMs * 3) return null
        return String.format(
            Locale.US,
            "CPU %d%% MX %d%% UI %d%% MEM %d/%dM N %dM S%d F%d A%d T%s H%s",
            (snapshot.processCpuFraction * 100.0).toInt(),
            (snapshot.mediaMtxCpuFraction * 100.0).toInt(),
            (snapshot.mainThreadCpuFraction * 100.0).toInt(),
            snapshot.javaHeapUsedMb,
            snapshot.javaHeapMaxMb,
            snapshot.nativeHeapUsedMb,
            snapshot.liveStreamCount,
            snapshot.ffmpegStreamCount,
            snapshot.anomalyEnabledCount,
            snapshot.thermalStatusLabel,
            snapshot.anomalyHeadroomLabel,
        )
    }

    fun streamPerformanceOverlayText(designator: String): String? {
        val runtime = ffmpegProbeService?.runtimeSnapshot(designator) ?: return null
        return String.format(
            Locale.US,
            "FPS d/r %.1f/%.1f FR %d/%d AGE %dms%s",
            runtime.avgDecodedFps,
            runtime.avgRenderedFps,
            runtime.decodedFrameCount,
            runtime.renderedFrameCount,
            runtime.lastFrameAgeMs,
            runtime.renderDelayMs?.let { " LAT ${it}ms" } ?: "",
        )
    }

    fun performancePanelText(focusedDesignator: String?): String {
        val lines = mutableListOf<String>()
        val snapshot = latestProcessLoadSnapshot
        if (snapshot == null) {
            lines += "Performance sample: not available yet."
        } else {
            val ageMs = System.currentTimeMillis() - snapshot.observedAtMs
            lines += "Device load"
            lines += String.format(Locale.US, "  App CPU load: %d%% of available core capacity", (snapshot.processCpuFraction * 100.0).toInt())
            lines += if (snapshot.mediaMtxPid > 0) {
                String.format(
                    Locale.US,
                    "  MediaMTX CPU load: %d%% of available core capacity (pid %d)",
                    (snapshot.mediaMtxCpuFraction * 100.0).toInt(),
                    snapshot.mediaMtxPid,
                )
            } else {
                "  MediaMTX CPU load: not available"
            }
            lines += String.format(Locale.US, "  Main-thread CPU load: %d%%", (snapshot.mainThreadCpuFraction * 100.0).toInt())
            lines += String.format(Locale.US, "  Java heap: %d MB used of %d MB max", snapshot.javaHeapUsedMb, snapshot.javaHeapMaxMb)
            lines += String.format(Locale.US, "  Native heap: %d MB", snapshot.nativeHeapUsedMb)
            lines += String.format(Locale.US, "  Live streams: %d", snapshot.liveStreamCount)
            lines += String.format(Locale.US, "  FFmpeg streams: %d", snapshot.ffmpegStreamCount)
            lines += String.format(Locale.US, "  Anomaly-enabled streams: %d", snapshot.anomalyEnabledCount)
            lines += "  Thermal status: ${snapshot.thermalStatusLabel}"
            lines += "  Estimated anomaly headroom: ${snapshot.anomalyHeadroomLabel}"
            lines += String.format(Locale.US, "  Sample age: %d ms over a %d ms window", ageMs, snapshot.sampleWindowMs)
        }

        if (!focusedDesignator.isNullOrBlank()) {
            lines += ""
            lines += "Focused stream: $focusedDesignator"
            val runtime = ffmpegProbeService?.runtimeSnapshot(focusedDesignator)
            if (runtime == null) {
                lines += "  Stream runtime stats: not available for current renderer."
            } else {
                lines += String.format(Locale.US, "  Average decoded FPS: %.1f", runtime.avgDecodedFps)
                lines += String.format(Locale.US, "  Average rendered FPS: %.1f", runtime.avgRenderedFps)
                lines += String.format(Locale.US, "  Decoded frames: %d", runtime.decodedFrameCount)
                lines += String.format(Locale.US, "  Rendered frames: %d", runtime.renderedFrameCount)
                lines += String.format(Locale.US, "  Last frame age: %d ms", runtime.lastFrameAgeMs)
                runtime.renderDelayMs?.let {
                    lines += String.format(Locale.US, "  Render latency: %d ms", it)
                }
                lines += String.format(Locale.US, "  Idle poll count: %d", runtime.idlePollCount)
            }
        }

        lines += ""
        lines += "Headroom guide"
        lines += "  ok: device appears to have room for anomaly work."
        lines += "  limit: usable, but additional streams or anomaly load may cause lag."
        lines += "  hot: thermal or CPU pressure is high; anomaly work may be risky."
        return lines.joinToString("\n")
    }

    private fun currentThermalStatusLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        val pm = getApplication<Application>().getSystemService(PowerManager::class.java) ?: return "n/a"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "cool"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "mod"
            PowerManager.THERMAL_STATUS_SEVERE -> "sev"
            PowerManager.THERMAL_STATUS_CRITICAL -> "crit"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emrg"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "sdwn"
            else -> "unk"
        }
    }

    private fun estimateAnomalyHeadroom(
        processCpuFraction: Double,
        mainThreadCpuFraction: Double,
        thermalStatusLabel: String,
        liveStreamCount: Int,
        ffmpegStreamCount: Int,
        anomalyEnabledCount: Int,
    ): String {
        if (thermalStatusLabel == "sev" || thermalStatusLabel == "crit" ||
            thermalStatusLabel == "emrg" || thermalStatusLabel == "sdwn") {
            return "hot"
        }
        if (processCpuFraction >= 0.85 || mainThreadCpuFraction >= 0.40) {
            return "hot"
        }
        if (processCpuFraction >= 0.65 || mainThreadCpuFraction >= 0.25 ||
            ffmpegStreamCount >= 2 || anomalyEnabledCount >= 1 || liveStreamCount >= 3 ||
            thermalStatusLabel == "mod") {
            return "limit"
        }
        return "ok"
    }

    private fun readProcCpuTicks(pid: Int): Long? {
        if (pid <= 0) return null
        return try {
            val stat = java.io.File("/proc/$pid/stat").readText()
            val closingParen = stat.lastIndexOf(')')
            if (closingParen < 0 || closingParen + 2 >= stat.length) return null
            val parts = stat.substring(closingParen + 2).trim().split(Regex("\\s+"))
            if (parts.size <= 12) return null
            val utimeTicks = parts[11].toLongOrNull() ?: return null
            val stimeTicks = parts[12].toLongOrNull() ?: return null
            utimeTicks + stimeTicks
        } catch (_: Exception) {
            null
        }
    }

    private fun applyFocusedAnomalyPolicy(liveDesignators: Set<String>) {
        val focused = _focusedPath.value
        (liveDesignators + _localPlaybackEntries.value.keys).forEach { designator ->
            val config = _anomalyConfigByDesignator[designator] ?: defaultAnomalyConfig
            val enableForDesignator = focused == designator && config.enabled
            ffmpegProbeService?.setAnomalyConfig(
                designator,
                config.toNativeConfig(
                    enabledOverride = enableForDesignator,
                    detectedAppearanceMode = _detectedAppearanceModeByDesignator[designator]
                )
            )
        }
    }

    private fun stageCapturedVideoForPlayback(designator: String, sourceUri: Uri, displayName: String): Uri {
        val context = getApplication<Application>().applicationContext
        val sanitizedBase = displayName
            .substringBeforeLast('.', displayName)
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "captured_video" }
        val extension = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() } ?: "mp4"
        val targetFile = File(
            context.cacheDir,
            "${sanitizedBase}_${designator.hashCode().toUInt().toString(16)}.$extension"
        )
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to read selected video")
        return Uri.fromFile(targetFile)
    }

    private fun guessCapturedVideoAppearance(sourceUri: Uri): CapturedVideoAppearanceGuess {
        val retriever = MediaMetadataRetriever()
        return try {
            val context = getApplication<Application>().applicationContext
            retriever.setDataSource(context, sourceUri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frame = runCatching {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            }.getOrNull()
            val guess = classifyAppearanceFromBitmap(width, height, frame)
            frame?.recycle()
            guess
        } catch (t: Throwable) {
            CTDebug(tag, "Captured video appearance guess failed for $sourceUri: ${t.message}")
            CapturedVideoAppearanceGuess(
                mode = AppearanceAnomalyMode.Color,
                width = null,
                height = null,
                grayscaleFraction = null,
                reason = "fallback",
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun classifyAppearanceFromBitmap(
        widthHint: Int?,
        heightHint: Int?,
        bitmap: Bitmap?,
    ): CapturedVideoAppearanceGuess {
        val width = widthHint ?: bitmap?.width
        val height = heightHint ?: bitmap?.height
        val grayscaleFraction = bitmap?.let { estimateGrayscaleFraction(it) }
        val smallThermalSize = width != null && height != null &&
            ((width <= 640 && height <= 512) || (width <= 704 && height <= 576))
        val likelyGrayscale = grayscaleFraction != null && grayscaleFraction >= 0.94f
        val mode = if (smallThermalSize && likelyGrayscale) {
            AppearanceAnomalyMode.Thermal
        } else if (likelyGrayscale && width != null && height != null && max(width, height) <= 960) {
            AppearanceAnomalyMode.Thermal
        } else {
            AppearanceAnomalyMode.Color
        }
        val reason = buildString {
            append(if (smallThermalSize) "small-frame" else "full-frame")
            append("/")
            append(if (likelyGrayscale) "grayscale" else "colorful")
        }
        return CapturedVideoAppearanceGuess(
            mode = mode,
            width = width,
            height = height,
            grayscaleFraction = grayscaleFraction,
            reason = reason,
        )
    }

    private fun estimateGrayscaleFraction(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return 0f
        val stepX = max(1, width / 24)
        val stepY = max(1, height / 24)
        var grayCount = 0
        var totalCount = 0
        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val chromaSpread = max(abs(r - g), max(abs(r - b), abs(g - b)))
                if (chromaSpread <= 8) {
                    grayCount++
                }
                totalCount++
            }
        }
        return if (totalCount > 0) grayCount.toFloat() / totalCount.toFloat() else 0f
    }

    init {
        defaultAnomalyConfig = AnomalyPrefs.load(application.applicationContext)
        CaltopoMap.AddMapStatusListener(this)
        CaltopoClient.AddDroneSpecsChangedListener(this)
        StreamRegistry.setAdmissionGuard(::admissionGuardDecision)
        startProcessLoadMonitor()

        viewModelScope.launch {
            combine(StreamRegistry.streams, _localPlaybackEntries) { liveStreams, localPlaybackEntries ->
                buildMap<String, StreamInfo> {
                    putAll(liveStreams)
                    putAll(localPlaybackEntries)
                }
            }.collect { map ->
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
