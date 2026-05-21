package org.ncssar.rid2caltopo.ui

import android.location.Location
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.R
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import kotlin.math.max

private const val SIGNAL_LOSS_ALERT_TAG = "DroneSignalLossAlert"

private data class DroneSignalLossCandidate(
    val flightKey: String,
    val remoteId: String,
    val mappedId: String,
    val signalIdleMs: Long,
    val thresholdMs: Long,
    val learnedIntervalMs: Long,
    val learnedSamples: Int,
    val newTrackDelayMs: Long,
    val bridgeCheckDistanceFt: Double,
    val outOfRange: Boolean,
    val lossObservedWhileFar: Boolean,
    val hasExceededDistanceThreshold: Boolean,
    val distanceFromTabletFt: Double,
    val distanceFromTakeoffFt: Double?
)

data class DroneSignalLossAlertUiState(
    val flightKey: String,
    val remoteId: String,
    val mappedId: String,
    val signalIdleMs: Long,
    val distanceFromTabletFt: Double,
    val volumeFraction: Float
)

data class DroneSignalLossFlightUiState(
    val flightKey: String,
    val remoteId: String,
    val mappedId: String,
    val muted: Boolean,
    val alerting: Boolean,
    val signalIdleMs: Long?,
    val distanceFromTabletFt: Double?
)

private data class FlightMonitorState(
    val hasExceededDistanceThreshold: Boolean = false,
    val lossObservedWhileFar: Boolean = false,
    val alertStartedAtMs: Long? = null,
    val toneLimited: Boolean = false,
    val outOfRangeNotified: Boolean = false
)

object DroneSignalLossAlertCenter : CtDroneSpec.DroneSpecsChangedListener {
    private const val ALERT_IDLE_THRESHOLD_MS = 2_000L
    private const val ALERT_BOOTSTRAP_IDLE_THRESHOLD_MS = 10_000L
    private const val MIN_VOLUME = 0.20f
    private const val MAX_VOLUME = 0.80f

    private val _uiState = MutableStateFlow<DroneSignalLossAlertUiState?>(null)
    val uiState: StateFlow<DroneSignalLossAlertUiState?> = _uiState.asStateFlow()

    private val _flights = MutableStateFlow<List<DroneSignalLossFlightUiState>>(emptyList())
    val flights: StateFlow<List<DroneSignalLossFlightUiState>> = _flights.asStateFlow()

    @Volatile
    private var registered = false
    private var currentAlertFlightKey: String? = null
    private var previouslyLoggedAlertFlightKey: String? = null
    private var lastDroneSpecs: List<CtDroneSpec> = emptyList()
    private val mutedFlightKeys = linkedSetOf<String>()
    private val flightMonitorState = linkedMapOf<String, FlightMonitorState>()

    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            CaltopoClient.AddDroneSpecsChangedListener(this)
            registered = true
        }
    }

    override fun onDroneSpecsChanged(droneSpecs: List<CtDroneSpec>) {
        synchronized(this) {
            lastDroneSpecs = droneSpecs
            recomputeLocked()
        }
    }

    fun setMuted(flightKey: String, muted: Boolean) {
        synchronized(this) {
            if (muted) {
                mutedFlightKeys.add(flightKey)
            } else {
                mutedFlightKeys.remove(flightKey)
            }
            recomputeLocked()
        }
    }

    private fun recomputeLocked() {
        val activeFlights = linkedMapOf<String, CtDroneSpec>()
        lastDroneSpecs.forEach { spec ->
            val flightKey = spec.flightKey() ?: return@forEach
            activeFlights[flightKey] = spec
        }
        mutedFlightKeys.retainAll(activeFlights.keys)
        flightMonitorState.keys.retainAll(activeFlights.keys)

        val nowMs = System.currentTimeMillis()
        val newTrackDelayMs = CaltopoClient.GetNewTrackDelayInSeconds() * 1000L
        val bridgeCheckDistanceFt = CaltopoClient.GetBridgeCheckDistanceFeet().toDouble()
        val outOfRangeDistanceFt = CaltopoClient.OUT_OF_RANGE_DISTANCE_FEET.toDouble()
        val returnToTakeoffDistanceFt = CaltopoClient.RETURN_TO_TAKEOFF_DISTANCE_FEET.toDouble()
        val signalToneDurationMs = CaltopoClient.LOSS_OF_SIGNAL_TONE_DURATION_SECONDS * 1000L
        val tabletLocation = CaltopoMap.GetMyLocation()
        val eligible = if (tabletLocation != null && newTrackDelayMs > ALERT_IDLE_THRESHOLD_MS) {
            activeFlights.values.mapNotNull { spec ->
                if (spec.lastLat == 0.0 || spec.lastLng == 0.0) return@mapNotNull null
                val signalIdleMs = spec.signalIdleTimeInMsec(nowMs)
                val distanceFt = distanceFeetFromTablet(spec, tabletLocation) ?: return@mapNotNull null
                val takeoffDistanceFt = distanceFeetFromTakeoff(spec)
                val oorReferenceDistanceFt = takeoffDistanceFt ?: distanceFt
                val flightKey = spec.flightKey() ?: return@mapNotNull null
                val priorState = flightMonitorState[flightKey] ?: FlightMonitorState()
                val effectiveIdleThresholdMs = effectiveIdleThresholdMs(
                    learnedIntervalMs = spec.learnedSignalIntervalMs,
                    learnedSamples = spec.learnedSignalIntervalSamples,
                    maxTrackDelayMs = newTrackDelayMs
                )
                if (spec.isOutOfRange &&
                    (signalIdleMs <= effectiveIdleThresholdMs || oorReferenceDistanceFt < outOfRangeDistanceFt)
                ) {
                    CTDebug(
                        SIGNAL_LOSS_ALERT_TAG,
                        "Clearing OOR for ${spec.mappedId} flightKey=$flightKey " +
                            "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                            "thresholdMs=$effectiveIdleThresholdMs " +
                            "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"}"
                    )
                    spec.setOutOfRange(false)
                    CaltopoClient.InvalidateTrackAgingSchedule()
                }
                val bridgeVerified = priorState.hasExceededDistanceThreshold ||
                    distanceFt > bridgeCheckDistanceFt ||
                    (takeoffDistanceFt != null && takeoffDistanceFt > bridgeCheckDistanceFt)
                var lossWhileFar = priorState.lossObservedWhileFar ||
                    (bridgeVerified &&
                        signalIdleMs > effectiveIdleThresholdMs &&
                        distanceFt > bridgeCheckDistanceFt &&
                        (takeoffDistanceFt == null || takeoffDistanceFt > returnToTakeoffDistanceFt))
                val returnedToBridge = isReturnedToBridge(
                    bridgeVerified = bridgeVerified,
                    distanceFt = distanceFt,
                    bridgeCheckDistanceFt = bridgeCheckDistanceFt
                )
                val returnedToTakeoff = isReturnedToTakeoff(
                    bridgeVerified = bridgeVerified,
                    takeoffDistanceFt = takeoffDistanceFt,
                    returnToTakeoffDistanceFt = returnToTakeoffDistanceFt
                )
                val stationaryNearBridge = isStationaryNearBridge(
                    bridgeVerified = bridgeVerified,
                    stationaryRidReports = spec.hasStationaryRidReports(),
                    referenceDistanceFt = oorReferenceDistanceFt,
                    bridgeCheckDistanceFt = bridgeCheckDistanceFt
                )
                if (returnedToBridge || returnedToTakeoff || stationaryNearBridge) {
                    if (priorState.lossObservedWhileFar) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Clearing far-loss latch for ${spec.mappedId} flightKey=$flightKey " +
                                "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                                "thresholdMs=$effectiveIdleThresholdMs " +
                                "bridgeCheckDistanceFt=${"%.1f".format(bridgeCheckDistanceFt)} " +
                                "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"}"
                        )
                    }
                    lossWhileFar = false
                    if (signalIdleMs > effectiveIdleThresholdMs) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Suppressing LOS for returned/stationary ${spec.mappedId} flightKey=$flightKey " +
                                "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                                "thresholdMs=$effectiveIdleThresholdMs " +
                                "bridgeCheckDistanceFt=${"%.1f".format(bridgeCheckDistanceFt)} " +
                                "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"} " +
                                "returnToTakeoffDistanceFt=${"%.1f".format(returnToTakeoffDistanceFt)} " +
                                "stationaryRidReports=${spec.hasStationaryRidReports()}"
                        )
                    }
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = true,
                        lossObservedWhileFar = false,
                        alertStartedAtMs = null,
                        outOfRangeNotified = priorState.outOfRangeNotified && spec.isOutOfRange
                    )
                    return@mapNotNull null
                }
                val shouldEvaluateForAlertWindow =
                    signalIdleMs > effectiveIdleThresholdMs && signalIdleMs < newTrackDelayMs
                if (!shouldEvaluateForAlertWindow) {
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = bridgeVerified,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = null,
                        outOfRangeNotified = priorState.outOfRangeNotified && spec.isOutOfRange
                    )
                    return@mapNotNull null
                }
                val outOfRangeLoss = oorReferenceDistanceFt >= outOfRangeDistanceFt
                if (outOfRangeLoss && !spec.isOutOfRange) {
                    CTDebug(
                        SIGNAL_LOSS_ALERT_TAG,
                        "Classifying ${spec.mappedId} as OOR flightKey=$flightKey " +
                            "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                            "thresholdMs=$effectiveIdleThresholdMs " +
                            "takeoffDistanceFt=${takeoffDistanceFt?.let { "%.1f".format(it) } ?: "unknown"} " +
                            "oorTrackDelayMs=${CaltopoClient.OUT_OF_RANGE_TRACK_DELAY_SECONDS * 1000L}"
                    )
                    spec.setOutOfRange(true)
                    CaltopoClient.InvalidateTrackAgingSchedule()
                }
                if (outOfRangeLoss && priorState.outOfRangeNotified) {
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = bridgeVerified,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = priorState.alertStartedAtMs,
                        toneLimited = true,
                        outOfRangeNotified = true
                    )
                    return@mapNotNull null
                }
                val startedAtMs = priorState.alertStartedAtMs ?: nowMs
                if (signalToneDurationMs > 0L && nowMs - startedAtMs >= signalToneDurationMs) {
                    if (!priorState.toneLimited) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Stopping LOS tone for ${spec.mappedId} flightKey=$flightKey after " +
                                "${nowMs - startedAtMs} ms playback outOfRange=$outOfRangeLoss"
                        )
                    }
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = bridgeVerified,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = startedAtMs,
                        toneLimited = true,
                        outOfRangeNotified = priorState.outOfRangeNotified || outOfRangeLoss
                    )
                    return@mapNotNull null
                }
                val nextState = FlightMonitorState(
                    hasExceededDistanceThreshold = bridgeVerified,
                    lossObservedWhileFar = lossWhileFar,
                    alertStartedAtMs = startedAtMs,
                    outOfRangeNotified = priorState.outOfRangeNotified
                )
                flightMonitorState[flightKey] = nextState
                val shouldAlertNearTablet = !nextState.hasExceededDistanceThreshold || nextState.lossObservedWhileFar
                if (distanceFt <= bridgeCheckDistanceFt && !shouldAlertNearTablet) {
                    return@mapNotNull null
                }
                DroneSignalLossCandidate(
                    flightKey = flightKey,
                    remoteId = spec.remoteId,
                    mappedId = spec.mappedId,
                    signalIdleMs = signalIdleMs,
                    thresholdMs = effectiveIdleThresholdMs,
                    learnedIntervalMs = spec.learnedSignalIntervalMs,
                    learnedSamples = spec.learnedSignalIntervalSamples,
                    newTrackDelayMs = newTrackDelayMs,
                    bridgeCheckDistanceFt = bridgeCheckDistanceFt,
                    outOfRange = spec.isOutOfRange,
                    lossObservedWhileFar = nextState.lossObservedWhileFar,
                    hasExceededDistanceThreshold = nextState.hasExceededDistanceThreshold,
                    distanceFromTabletFt = distanceFt,
                    distanceFromTakeoffFt = takeoffDistanceFt
                )
            }
        } else {
            emptyList()
        }

        val eligibleByKey = eligible.associateBy { it.flightKey }
        val stickyFlights = activeFlights.keys.filter { it in mutedFlightKeys }
        val orderedKeys = linkedSetOf<String>()
        eligible.sortedWith(
            compareByDescending<DroneSignalLossCandidate> { it.signalIdleMs }
                .thenByDescending { it.distanceFromTabletFt }
        ).forEach { orderedKeys.add(it.flightKey) }
        stickyFlights.forEach { orderedKeys.add(it) }

        _flights.value = orderedKeys.mapNotNull { flightKey ->
            val spec = activeFlights[flightKey] ?: return@mapNotNull null
            val candidate = eligibleByKey[flightKey]
            DroneSignalLossFlightUiState(
                flightKey = flightKey,
                remoteId = spec.remoteId,
                mappedId = spec.mappedId,
                muted = flightKey in mutedFlightKeys,
                alerting = candidate != null,
                signalIdleMs = candidate?.signalIdleMs,
                distanceFromTabletFt = candidate?.distanceFromTabletFt
            )
        }

        val chosen = eligible
            .filterNot { it.flightKey in mutedFlightKeys }
            .let { unmutedEligible ->
                unmutedEligible.firstOrNull { it.flightKey == currentAlertFlightKey }
                    ?: unmutedEligible.maxWithOrNull(
                        compareBy<DroneSignalLossCandidate> { it.signalIdleMs }
                            .thenBy { it.distanceFromTabletFt }
                    )
            }

        currentAlertFlightKey = chosen?.flightKey
        if (chosen?.flightKey != previouslyLoggedAlertFlightKey) {
            if (chosen != null) {
                CTDebug(
                    SIGNAL_LOSS_ALERT_TAG,
                    "Alerting ${chosen.mappedId} flightKey=${chosen.flightKey} " +
                        "signalIdleMs=${chosen.signalIdleMs} thresholdMs=${chosen.thresholdMs} " +
                        "learnedIntervalMs=${chosen.learnedIntervalMs} " +
                        "learnedSamples=${chosen.learnedSamples} " +
                        "newTrackDelayMs=${chosen.newTrackDelayMs} " +
                        "bridgeCheckDistanceFt=${"%.1f".format(chosen.bridgeCheckDistanceFt)} " +
                        "distanceFt=${"%.1f".format(chosen.distanceFromTabletFt)} " +
                        "takeoffDistanceFt=${chosen.distanceFromTakeoffFt?.let { "%.1f".format(it) } ?: "unknown"} " +
                        "outOfRange=${chosen.outOfRange} " +
                        "hasExceededDistance=${chosen.hasExceededDistanceThreshold} " +
                        "lossObservedWhileFar=${chosen.lossObservedWhileFar}"
                )
            } else if (previouslyLoggedAlertFlightKey != null) {
                CTDebug(
                    SIGNAL_LOSS_ALERT_TAG,
                    "Clearing active signal-loss alert for flightKey=$previouslyLoggedAlertFlightKey"
                )
            }
            previouslyLoggedAlertFlightKey = chosen?.flightKey
        }
        _uiState.value = chosen?.toUiState(newTrackDelayMs)
    }

    private fun DroneSignalLossCandidate.toUiState(newTrackDelayMs: Long): DroneSignalLossAlertUiState {
        val rampTargetMs = max(ALERT_IDLE_THRESHOLD_MS, newTrackDelayMs / 2L)
        val volumeFraction = when {
            signalIdleMs >= rampTargetMs -> MAX_VOLUME
            rampTargetMs == ALERT_IDLE_THRESHOLD_MS -> MAX_VOLUME
            else -> {
                val progress = (signalIdleMs - ALERT_IDLE_THRESHOLD_MS).toFloat() /
                    (rampTargetMs - ALERT_IDLE_THRESHOLD_MS).toFloat()
                MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * progress.coerceIn(0f, 1f)
            }
        }
        val adjustedVolumeFraction = (volumeFraction * CaltopoClient.GetAlarmVolumeMultiplier())
            .coerceIn(0f, 1f)
        return DroneSignalLossAlertUiState(
            flightKey = flightKey,
            remoteId = remoteId,
            mappedId = mappedId,
            signalIdleMs = signalIdleMs,
            distanceFromTabletFt = distanceFromTabletFt,
            volumeFraction = adjustedVolumeFraction
        )
    }

    private fun CtDroneSpec.flightKey(): String? {
        val startMsec = startMsecTimestamp
        if (!isActive || startMsec <= 0L) return null
        return "$remoteId:$startMsec"
    }

    private fun effectiveIdleThresholdMs(
        learnedIntervalMs: Long,
        learnedSamples: Int,
        maxTrackDelayMs: Long
    ): Long {
        if (learnedSamples < 2 || learnedIntervalMs <= 0L) {
            return ALERT_BOOTSTRAP_IDLE_THRESHOLD_MS
                .coerceAtMost((maxTrackDelayMs - 1L).coerceAtLeast(ALERT_IDLE_THRESHOLD_MS))
        }
        val dynamicThresholdMs = learnedIntervalMs * 5L / 2L
        return dynamicThresholdMs
            .coerceAtLeast(ALERT_BOOTSTRAP_IDLE_THRESHOLD_MS)
            .coerceAtMost((maxTrackDelayMs - 1L).coerceAtLeast(ALERT_IDLE_THRESHOLD_MS))
    }

    private fun isReturnedToBridge(
        bridgeVerified: Boolean,
        distanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = bridgeVerified && distanceFt <= bridgeCheckDistanceFt

    private fun isReturnedToTakeoff(
        bridgeVerified: Boolean,
        takeoffDistanceFt: Double?,
        returnToTakeoffDistanceFt: Double
    ): Boolean = bridgeVerified &&
        takeoffDistanceFt != null &&
        takeoffDistanceFt <= returnToTakeoffDistanceFt

    private fun isStationaryNearBridge(
        bridgeVerified: Boolean,
        stationaryRidReports: Boolean,
        referenceDistanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = bridgeVerified && stationaryRidReports && referenceDistanceFt <= bridgeCheckDistanceFt

    internal fun isReturnedToBridgeForTests(
        bridgeVerified: Boolean,
        distanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = isReturnedToBridge(bridgeVerified, distanceFt, bridgeCheckDistanceFt)

    internal fun isReturnedToTakeoffForTests(
        bridgeVerified: Boolean,
        takeoffDistanceFt: Double?,
        returnToTakeoffDistanceFt: Double
    ): Boolean = isReturnedToTakeoff(
        bridgeVerified = bridgeVerified,
        takeoffDistanceFt = takeoffDistanceFt,
        returnToTakeoffDistanceFt = returnToTakeoffDistanceFt
    )

    internal fun isStationaryNearBridgeForTests(
        bridgeVerified: Boolean,
        stationaryRidReports: Boolean,
        referenceDistanceFt: Double,
        bridgeCheckDistanceFt: Double
    ): Boolean = isStationaryNearBridge(
        bridgeVerified = bridgeVerified,
        stationaryRidReports = stationaryRidReports,
        referenceDistanceFt = referenceDistanceFt,
        bridgeCheckDistanceFt = bridgeCheckDistanceFt
    )

    internal fun effectiveIdleThresholdMsForTests(
        learnedIntervalMs: Long,
        learnedSamples: Int,
        maxTrackDelayMs: Long
    ): Long = effectiveIdleThresholdMs(learnedIntervalMs, learnedSamples, maxTrackDelayMs)

    private fun distanceFeetFromTablet(
        spec: CtDroneSpec,
        tabletLocation: Location
    ): Double? {
        val result = FloatArray(1)
        return try {
            Location.distanceBetween(
                tabletLocation.latitude,
                tabletLocation.longitude,
                spec.lastLat,
                spec.lastLng,
                result
            )
            result[0] * 3.28084
        } catch (_: Exception) {
            null
        }
    }

    private fun distanceFeetFromTakeoff(spec: CtDroneSpec): Double? {
        if (!spec.hasTakeoffLocation() || spec.lastLat == 0.0 || spec.lastLng == 0.0) return null
        val result = FloatArray(1)
        return try {
            Location.distanceBetween(
                spec.takeoffLat,
                spec.takeoffLng,
                spec.lastLat,
                spec.lastLng,
                result
            )
            result[0] * 3.28084
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
fun DroneSignalLossAlertHost() {
    val context = LocalContext.current
    val alert by DroneSignalLossAlertCenter.uiState.collectAsState()
    val player = remember(context) {
        try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                context.resources.openRawResourceFd(R.raw.arrest_flatline_tail)?.use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                isLooping = true
                prepare()
            }
        } catch (e: Exception) {
            CaltopoClient.CTWarn(SIGNAL_LOSS_ALERT_TAG, "Unable to prepare signal-loss alert tone.", e)
            null
        }
    }

    LaunchedEffect(Unit) {
        DroneSignalLossAlertCenter.ensureRegistered()
    }

    DisposableEffect(player) {
        onDispose {
            try {
                player?.release()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(alert?.flightKey, alert?.volumeFraction) {
        val mediaPlayer = player ?: return@LaunchedEffect
        val currentAlert = alert
        if (currentAlert == null) {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                mediaPlayer.seekTo(0)
            }
            return@LaunchedEffect
        }

        val volume = currentAlert.volumeFraction.coerceIn(0f, 1f)
        mediaPlayer.setVolume(volume, volume)
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.seekTo(0)
            mediaPlayer.start()
        }
    }
}
