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
    val distanceFromTabletFt: Double
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
    val lastSignalTimestampMs: Long = 0L,
    val learnedSignalIntervalMs: Long = 0L,
    val learnedSignalSamples: Int = 0
)

object DroneSignalLossAlertCenter : CtDroneSpec.DroneSpecsChangedListener {
    private const val ALERT_IDLE_THRESHOLD_MS = 2_000L
    private const val ALERT_DISTANCE_THRESHOLD_FT = 100.0
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
        val tabletLocation = CaltopoMap.GetMyLocation()
        val eligible = if (tabletLocation != null && newTrackDelayMs > ALERT_IDLE_THRESHOLD_MS) {
            activeFlights.values.mapNotNull { spec ->
                if (spec.lastLat == 0.0 || spec.lastLng == 0.0) return@mapNotNull null
                val signalIdleMs = spec.signalIdleTimeInMsec(nowMs)
                val distanceFt = distanceFeetFromTablet(spec, tabletLocation) ?: return@mapNotNull null
                val flightKey = spec.flightKey() ?: return@mapNotNull null
                val priorState = flightMonitorState[flightKey] ?: FlightMonitorState()
                val signalTimestampMs = spec.mostRecentSignalMsecTimestamp
                val (learnedIntervalMs, learnedSamples) = learnSignalInterval(
                    previousTimestampMs = priorState.lastSignalTimestampMs,
                    currentTimestampMs = signalTimestampMs,
                    previousIntervalMs = priorState.learnedSignalIntervalMs,
                    previousSamples = priorState.learnedSignalSamples,
                    maxTrackDelayMs = newTrackDelayMs
                )
                val effectiveIdleThresholdMs = effectiveIdleThresholdMs(
                    learnedIntervalMs = learnedIntervalMs,
                    learnedSamples = learnedSamples,
                    maxTrackDelayMs = newTrackDelayMs
                )
                val exceededThreshold = priorState.hasExceededDistanceThreshold ||
                    distanceFt > ALERT_DISTANCE_THRESHOLD_FT
                var lossWhileFar = priorState.lossObservedWhileFar ||
                    (exceededThreshold &&
                        signalIdleMs > effectiveIdleThresholdMs &&
                        distanceFt > ALERT_DISTANCE_THRESHOLD_FT)
                // Once the drone is back inside the near-tablet bubble and we are
                // receiving timely RID again, clear the "loss while far" latch so
                // normal low-motion 3-4 s broadcasts near the pilot do not keep
                // retriggering the flatline for the rest of the flight.
                if (distanceFt <= ALERT_DISTANCE_THRESHOLD_FT &&
                    signalIdleMs <= effectiveIdleThresholdMs) {
                    if (priorState.lossObservedWhileFar) {
                        CTDebug(
                            SIGNAL_LOSS_ALERT_TAG,
                            "Clearing far-loss latch for ${spec.mappedId} flightKey=$flightKey " +
                                "distanceFt=${"%.1f".format(distanceFt)} signalIdleMs=$signalIdleMs " +
                                "thresholdMs=$effectiveIdleThresholdMs"
                        )
                    }
                    lossWhileFar = false
                }
                val shouldEvaluateForAlertWindow =
                    signalIdleMs > effectiveIdleThresholdMs && signalIdleMs < newTrackDelayMs
                if (!shouldEvaluateForAlertWindow) {
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = exceededThreshold,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = null,
                        lastSignalTimestampMs = signalTimestampMs,
                        learnedSignalIntervalMs = learnedIntervalMs,
                        learnedSignalSamples = learnedSamples
                    )
                    return@mapNotNull null
                }
                val startedAtMs = priorState.alertStartedAtMs ?: nowMs
                val maxToneDurationMs = CaltopoClient.GetMaxFlatlineToneDurationInSeconds() * 1000L
                if (maxToneDurationMs > 0L && nowMs - startedAtMs >= maxToneDurationMs) {
                    mutedFlightKeys.add(flightKey)
                    CTDebug(
                        SIGNAL_LOSS_ALERT_TAG,
                        "Auto-muting ${spec.mappedId} flightKey=$flightKey after " +
                            "${nowMs - startedAtMs} ms flatline playback"
                    )
                    flightMonitorState[flightKey] = FlightMonitorState(
                        hasExceededDistanceThreshold = exceededThreshold,
                        lossObservedWhileFar = lossWhileFar,
                        alertStartedAtMs = startedAtMs,
                        lastSignalTimestampMs = signalTimestampMs,
                        learnedSignalIntervalMs = learnedIntervalMs,
                        learnedSignalSamples = learnedSamples
                    )
                    return@mapNotNull null
                }
                val nextState = FlightMonitorState(
                    hasExceededDistanceThreshold = exceededThreshold,
                    lossObservedWhileFar = lossWhileFar,
                    alertStartedAtMs = startedAtMs,
                    lastSignalTimestampMs = signalTimestampMs,
                    learnedSignalIntervalMs = learnedIntervalMs,
                    learnedSignalSamples = learnedSamples
                )
                flightMonitorState[flightKey] = nextState
                val shouldAlertNearTablet = !nextState.hasExceededDistanceThreshold || nextState.lossObservedWhileFar
                if (distanceFt <= ALERT_DISTANCE_THRESHOLD_FT && !shouldAlertNearTablet) {
                    return@mapNotNull null
                }
                DroneSignalLossCandidate(
                    flightKey = flightKey,
                    remoteId = spec.remoteId,
                    mappedId = spec.mappedId,
                    signalIdleMs = signalIdleMs,
                    distanceFromTabletFt = distanceFt
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
                        "signalIdleMs=${chosen.signalIdleMs} distanceFt=${"%.1f".format(chosen.distanceFromTabletFt)}"
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
        return DroneSignalLossAlertUiState(
            flightKey = flightKey,
            remoteId = remoteId,
            mappedId = mappedId,
            signalIdleMs = signalIdleMs,
            distanceFromTabletFt = distanceFromTabletFt,
            volumeFraction = volumeFraction
        )
    }

    private fun CtDroneSpec.flightKey(): String? {
        val startMsec = startMsecTimestamp
        if (!isActive || startMsec <= 0L) return null
        return "$remoteId:$startMsec"
    }

    private fun learnSignalInterval(
        previousTimestampMs: Long,
        currentTimestampMs: Long,
        previousIntervalMs: Long,
        previousSamples: Int,
        maxTrackDelayMs: Long
    ): Pair<Long, Int> {
        if (currentTimestampMs <= 0L || currentTimestampMs <= previousTimestampMs) {
            return previousIntervalMs to previousSamples
        }
        if (previousTimestampMs <= 0L) {
            return previousIntervalMs to previousSamples
        }
        val intervalMs = currentTimestampMs - previousTimestampMs
        if (intervalMs <= 0L || intervalMs >= maxTrackDelayMs) {
            return previousIntervalMs to previousSamples
        }
        val nextIntervalMs = if (previousIntervalMs <= 0L) {
            intervalMs
        } else {
            ((previousIntervalMs * 3L) + intervalMs) / 4L
        }
        val nextSamples = (previousSamples + 1).coerceAtMost(1000)
        return nextIntervalMs to nextSamples
    }

    private fun effectiveIdleThresholdMs(
        learnedIntervalMs: Long,
        learnedSamples: Int,
        maxTrackDelayMs: Long
    ): Long {
        if (learnedSamples < 2 || learnedIntervalMs <= 0L) {
            return ALERT_IDLE_THRESHOLD_MS
        }
        val dynamicThresholdMs = learnedIntervalMs * 5L / 2L
        return dynamicThresholdMs
            .coerceAtLeast(ALERT_IDLE_THRESHOLD_MS)
            .coerceAtMost((maxTrackDelayMs - 1L).coerceAtLeast(ALERT_IDLE_THRESHOLD_MS))
    }

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
