package org.ncssar.rid2caltopo.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.ncssar.rid2caltopo.data.CtDroneSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ProximityAlertUiState(
    val alertInstanceId: Long,
    val pairKey: String,
    val thresholdFt: Double,
    val highSeverity: Boolean,
    val nearestDroneMappedId: String,
    val farthestDroneMappedId: String,
    val highestDroneMappedId: String,
    val lowestDroneMappedId: String,
    val horizontalSeparationFt: Double,
    val verticalSeparationFt: Double,
    val firstLat: Double,
    val firstLng: Double,
    val secondLat: Double,
    val secondLng: Double
)

data class ProximityDebugPair(
    val firstMappedId: String,
    val secondMappedId: String,
    val horizontalSeparationFt: Double,
    val verticalSeparationFt: Double,
    val threeDSeparationFt: Double,
    val alerting: Boolean
)

object ProximityAlertCenter {
    private const val MIN_DRONE_MOVE_FT = 1.0
    private const val MAX_PROJECTION_MS = 2_000L
    private const val MIN_STALE_MULTIPLIER = 1_000L
    private const val CLEAR_DELAY_MS = 3_000L
    private const val FT_PER_METER = 3.28084
    private const val METERS_PER_FOOT = 0.3048

    private data class DroneSample(
        val remoteId: String,
        val mappedId: String,
        val lat: Double,
        val lng: Double,
        val altFt: Double,
        val wallTimeMs: Long
    )

    private data class EvaluatedDrone(
        val remoteId: String,
        val mappedId: String,
        val currentLat: Double,
        val currentLng: Double,
        val currentAltFt: Double,
        val effectiveLat: Double,
        val effectiveLng: Double,
        val effectiveAltFt: Double,
        val distanceToDeviceFt: Double?
    )

    private data class PairSnapshot(
        val effectiveHorizontalFt: Double,
        val effectiveVerticalFt: Double,
        val effectiveThreeDFt: Double
    )

    private data class PairEvaluation(
        val pairKey: String,
        val first: EvaluatedDrone,
        val second: EvaluatedDrone,
        val effectiveHorizontalFt: Double,
        val effectiveVerticalFt: Double,
        val effectiveThreeDFt: Double,
        val currentHorizontalFt: Double,
        val currentVerticalFt: Double,
        val currentThreeDFt: Double,
        val shouldAlert: Boolean,
        val isGettingFartherApart: Boolean,
        val highSeverity: Boolean,
        val severityScore: Double
    )

    private val _uiState = MutableStateFlow<ProximityAlertUiState?>(null)
    val uiState: StateFlow<ProximityAlertUiState?> = _uiState.asStateFlow()
    private val _suspendedAlert = MutableStateFlow<ProximityAlertUiState?>(null)
    val suspendedAlert: StateFlow<ProximityAlertUiState?> = _suspendedAlert.asStateFlow()
    private val _debugPairs = MutableStateFlow<List<ProximityDebugPair>>(emptyList())
    val debugPairs: StateFlow<List<ProximityDebugPair>> = _debugPairs.asStateFlow()

    private val sampleHistoryByRemoteId = linkedMapOf<String, ArrayDeque<DroneSample>>()
    private val previousPairSnapshots = linkedMapOf<String, PairSnapshot>()
    private var latestEvaluationsByKey = emptyMap<String, PairEvaluation>()
    private var alertsSuspended = false
    private var clearEligibleSinceMs: Long? = null

    fun updateDrones(drones: List<CtDroneSpec>) {
        val nowMs = System.currentTimeMillis()
        val thresholdFt = CaltopoClient.GetProximityAlertSpacingFeet().toDouble()
        if (thresholdFt <= 0.0) {
            alertsSuspended = false
            clearEligibleSinceMs = null
            _uiState.value = null
            _suspendedAlert.value = null
            _debugPairs.value = emptyList()
            latestEvaluationsByKey = emptyMap()
            return
        }

        val staleCutoffMs = nowMs - (CaltopoClient.GetNewTrackDelayInSeconds() * MIN_STALE_MULTIPLIER)
        val activeDrones = drones.filter { spec ->
            spec.mostRecentMsecTimestamp >= staleCutoffMs &&
                spec.lastLat.isFinite() &&
                spec.lastLng.isFinite() &&
                !(spec.lastLat == 0.0 && spec.lastLng == 0.0)
        }

        val activeRemoteIds = activeDrones.mapTo(linkedSetOf()) { it.remoteId }
        sampleHistoryByRemoteId.keys.toList().forEach { remoteId ->
            if (remoteId !in activeRemoteIds) sampleHistoryByRemoteId.remove(remoteId)
        }

        activeDrones.forEach { spec ->
            val history = sampleHistoryByRemoteId.getOrPut(spec.remoteId) { ArrayDeque() }
            val latest = history.lastOrNull()
            if (latest == null || latest.wallTimeMs != spec.mostRecentMsecTimestamp) {
                history.addLast(
                    DroneSample(
                        remoteId = spec.remoteId,
                        mappedId = spec.mappedId,
                        lat = spec.lastLat,
                        lng = spec.lastLng,
                        altFt = spec.lastAlt * FT_PER_METER,
                        wallTimeMs = spec.mostRecentMsecTimestamp
                    )
                )
                while (history.size > 2) history.removeFirst()
            }
        }

        val predictiveEnabled = CaltopoClient.GetPredictiveHeadEnabled()
        val myLocation = CaltopoMap.GetMyLocation()
        val evaluated = activeDrones.map { spec ->
            evaluateDrone(spec, predictiveEnabled, myLocation)
        }

        val evaluations = buildList {
            for (i in 0 until evaluated.size) {
                for (j in i + 1 until evaluated.size) {
                    evaluatePair(
                        first = evaluated[i],
                        second = evaluated[j],
                        thresholdFt = thresholdFt,
                        predictionEnabled = predictiveEnabled
                    )?.let(::add)
                }
            }
        }

        val currentEvaluationsByKey = evaluations.associateBy { it.pairKey }
        latestEvaluationsByKey = currentEvaluationsByKey
        _debugPairs.value = evaluations
            .sortedWith(compareBy<PairEvaluation> { it.effectiveThreeDFt }.thenBy { it.pairKey })
            .map { evaluation ->
                ProximityDebugPair(
                    firstMappedId = evaluation.first.mappedId,
                    secondMappedId = evaluation.second.mappedId,
                    horizontalSeparationFt = evaluation.effectiveHorizontalFt,
                    verticalSeparationFt = evaluation.effectiveVerticalFt,
                    threeDSeparationFt = evaluation.effectiveThreeDFt,
                    alerting = evaluation.shouldAlert
                )
            }
        val bestCandidate = evaluations
            .asSequence()
            .filter { it.shouldAlert }
            .minWithOrNull(
                compareBy<PairEvaluation>({ it.severityScore }, { it.effectiveThreeDFt })
            )

        val activeAlertState = _uiState.value ?: _suspendedAlert.value
        when {
            bestCandidate != null -> {
                val instanceId = if (activeAlertState?.pairKey == bestCandidate.pairKey) {
                    activeAlertState.alertInstanceId
                } else {
                    nowMs
                }
                val alertState = bestCandidate.toUiState(instanceId, thresholdFt)
                if (alertsSuspended) {
                    _uiState.value = null
                    _suspendedAlert.value = alertState
                } else {
                    _uiState.value = alertState
                    _suspendedAlert.value = null
                }
                clearEligibleSinceMs = null
            }

            activeAlertState != null -> {
                val currentEval = currentEvaluationsByKey[activeAlertState.pairKey]
                val clearCondition = currentEval == null ||
                    currentEval.effectiveHorizontalFt > thresholdFt ||
                    currentEval.effectiveVerticalFt > thresholdFt
                if (clearCondition) {
                    if (clearEligibleSinceMs == null) clearEligibleSinceMs = nowMs
                    if (nowMs - (clearEligibleSinceMs ?: nowMs) >= CLEAR_DELAY_MS) {
                        _uiState.value = null
                        _suspendedAlert.value = null
                        clearEligibleSinceMs = null
                    }
                } else {
                    currentEval?.let { evaluation ->
                        val refreshedState = evaluation.toUiState(activeAlertState.alertInstanceId, thresholdFt)
                        if (alertsSuspended) {
                            _uiState.value = null
                            _suspendedAlert.value = refreshedState
                        } else {
                            _uiState.value = refreshedState
                            _suspendedAlert.value = null
                        }
                    }
                    clearEligibleSinceMs = null
                }
            }

            else -> {
                _suspendedAlert.value = null
            }
        }

        previousPairSnapshots.clear()
        evaluations.forEach { evaluation ->
            previousPairSnapshots[evaluation.pairKey] = PairSnapshot(
                effectiveHorizontalFt = evaluation.effectiveHorizontalFt,
                effectiveVerticalFt = evaluation.effectiveVerticalFt,
                effectiveThreeDFt = evaluation.effectiveThreeDFt
            )
        }
    }

    fun suspendCurrentAlert() {
        alertsSuspended = true
        clearEligibleSinceMs = null
        _suspendedAlert.value = _uiState.value
        _uiState.value = null
    }

    fun resumeSuspendedAlert() {
        val suspended = _suspendedAlert.value ?: return
        val currentEval = latestEvaluationsByKey[suspended.pairKey]
        val stillWithinThreshold = currentEval != null &&
            currentEval.effectiveHorizontalFt <= suspended.thresholdFt &&
            currentEval.effectiveVerticalFt <= suspended.thresholdFt
        alertsSuspended = false
        if (stillWithinThreshold) {
            _uiState.value = currentEval.toUiState(suspended.alertInstanceId, suspended.thresholdFt)
            _suspendedAlert.value = null
        } else {
            _uiState.value = null
            _suspendedAlert.value = null
        }
        clearEligibleSinceMs = null
    }

    private fun evaluateDrone(
        spec: CtDroneSpec,
        predictiveEnabled: Boolean,
        myLocation: android.location.Location?
    ): EvaluatedDrone {
        val currentAltFt = spec.lastAlt * FT_PER_METER
        val history = sampleHistoryByRemoteId[spec.remoteId]
        val projected = if (predictiveEnabled) projectedSample(history) else null
        val effectiveLat = projected?.lat ?: spec.lastLat
        val effectiveLng = projected?.lng ?: spec.lastLng
        val effectiveAltFt = projected?.altFt ?: currentAltFt
        val distanceToDeviceFt = myLocation?.let { location ->
            val result = FloatArray(1)
            android.location.Location.distanceBetween(
                effectiveLat,
                effectiveLng,
                location.latitude,
                location.longitude,
                result
            )
            result[0] * FT_PER_METER
        }
        return EvaluatedDrone(
            remoteId = spec.remoteId,
            mappedId = spec.mappedId,
            currentLat = spec.lastLat,
            currentLng = spec.lastLng,
            currentAltFt = currentAltFt,
            effectiveLat = effectiveLat,
            effectiveLng = effectiveLng,
            effectiveAltFt = effectiveAltFt,
            distanceToDeviceFt = distanceToDeviceFt
        )
    }

    private data class ProjectedSample(
        val lat: Double,
        val lng: Double,
        val altFt: Double
    )

    private fun projectedSample(history: ArrayDeque<DroneSample>?): ProjectedSample? {
        val p2 = history?.lastOrNull() ?: return null
        val p1 = history.firstOrNull() ?: return null
        if (p1.wallTimeMs == p2.wallTimeMs) return null

        val deltaMs = (p2.wallTimeMs - p1.wallTimeMs).coerceAtMost(MAX_PROJECTION_MS)
        if (deltaMs <= 0L) return null

        val distanceAndBearing = FloatArray(2)
        android.location.Location.distanceBetween(
            p1.lat, p1.lng,
            p2.lat, p2.lng,
            distanceAndBearing
        )
        val horizontalDistanceFt = distanceAndBearing[0] * FT_PER_METER
        val projectionMs = min(deltaMs, MAX_PROJECTION_MS)
        val projectionDistanceFt = horizontalDistanceFt
        val projectedPoint = if (projectionDistanceFt >= MIN_DRONE_MOVE_FT) {
            destinationPoint(
                startLat = p2.lat,
                startLng = p2.lng,
                bearingDeg = distanceAndBearing[1].toDouble(),
                distanceM = projectionDistanceFt * METERS_PER_FOOT
            )
        } else {
            org.osmdroid.util.GeoPoint(p2.lat, p2.lng)
        }
        val verticalRateFtPerMs = (p2.altFt - p1.altFt) / deltaMs.toDouble()
        return ProjectedSample(
            lat = projectedPoint.latitude,
            lng = projectedPoint.longitude,
            altFt = p2.altFt + (verticalRateFtPerMs * projectionMs.toDouble())
        )
    }

    private fun evaluatePair(
        first: EvaluatedDrone,
        second: EvaluatedDrone,
        thresholdFt: Double,
        predictionEnabled: Boolean
    ): PairEvaluation? {
        val currentHorizontalFt = horizontalDistanceFt(
            first.currentLat,
            first.currentLng,
            second.currentLat,
            second.currentLng
        )
        val currentVerticalFt = abs(first.currentAltFt - second.currentAltFt)
        val currentThreeDFt = threeDistanceFt(currentHorizontalFt, currentVerticalFt)

        val effectiveHorizontalFt = horizontalDistanceFt(
            first.effectiveLat,
            first.effectiveLng,
            second.effectiveLat,
            second.effectiveLng
        )
        val effectiveVerticalFt = abs(first.effectiveAltFt - second.effectiveAltFt)
        val effectiveThreeDFt = threeDistanceFt(effectiveHorizontalFt, effectiveVerticalFt)

        if (!effectiveHorizontalFt.isFinite() || !effectiveVerticalFt.isFinite()) return null

        val pairKey = pairKey(first.remoteId, second.remoteId)
        val previous = previousPairSnapshots[pairKey]
        val insideThreshold =
            effectiveHorizontalFt <= thresholdFt && effectiveVerticalFt <= thresholdFt
        val crossedIntoThreshold = previous == null ||
            previous.effectiveHorizontalFt > thresholdFt ||
            previous.effectiveVerticalFt > thresholdFt
        val isGettingFartherApart = previous != null && effectiveThreeDFt > previous.effectiveThreeDFt + MIN_DRONE_MOVE_FT
        val predictedCloser = effectiveThreeDFt + MIN_DRONE_MOVE_FT < currentThreeDFt
        val actuallyApproaching = previous == null || effectiveThreeDFt + MIN_DRONE_MOVE_FT < previous.effectiveThreeDFt
        val highSeverity =
            effectiveHorizontalFt < thresholdFt * 0.75 || effectiveVerticalFt < thresholdFt * 0.75
        val shouldAlert = if (predictionEnabled) {
            insideThreshold && (predictedCloser || crossedIntoThreshold)
        } else {
            insideThreshold && (actuallyApproaching || crossedIntoThreshold)
        }
        val severityScore = maxOf(
            effectiveHorizontalFt / thresholdFt,
            effectiveVerticalFt / thresholdFt
        )
        return PairEvaluation(
            pairKey = pairKey,
            first = first,
            second = second,
            effectiveHorizontalFt = effectiveHorizontalFt,
            effectiveVerticalFt = effectiveVerticalFt,
            effectiveThreeDFt = effectiveThreeDFt,
            currentHorizontalFt = currentHorizontalFt,
            currentVerticalFt = currentVerticalFt,
            currentThreeDFt = currentThreeDFt,
            shouldAlert = shouldAlert,
            isGettingFartherApart = isGettingFartherApart,
            highSeverity = highSeverity,
            severityScore = severityScore
        )
    }

    private fun PairEvaluation.toUiState(alertInstanceId: Long, thresholdFt: Double): ProximityAlertUiState {
        val nearest = listOf(first, second).sortedWith(
            compareBy<EvaluatedDrone>({ it.distanceToDeviceFt ?: Double.MAX_VALUE }, { it.mappedId })
        ).first()
        val farthest = if (nearest.remoteId == first.remoteId) second else first
        val highest = if (first.effectiveAltFt >= second.effectiveAltFt) first else second
        val lowest = if (highest.remoteId == first.remoteId) second else first
        return ProximityAlertUiState(
            alertInstanceId = alertInstanceId,
            pairKey = pairKey,
            thresholdFt = thresholdFt,
            highSeverity = highSeverity,
            nearestDroneMappedId = nearest.mappedId,
            farthestDroneMappedId = farthest.mappedId,
            highestDroneMappedId = highest.mappedId,
            lowestDroneMappedId = lowest.mappedId,
            horizontalSeparationFt = effectiveHorizontalFt,
            verticalSeparationFt = effectiveVerticalFt,
            firstLat = first.currentLat,
            firstLng = first.currentLng,
            secondLat = second.currentLat,
            secondLng = second.currentLng
        )
    }

    private fun pairKey(firstRemoteId: String, secondRemoteId: String): String {
        return if (firstRemoteId <= secondRemoteId) {
            "$firstRemoteId|$secondRemoteId"
        } else {
            "$secondRemoteId|$firstRemoteId"
        }
    }

    private fun horizontalDistanceFt(
        firstLat: Double,
        firstLng: Double,
        secondLat: Double,
        secondLng: Double
    ): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(firstLat, firstLng, secondLat, secondLng, result)
        return result[0] * FT_PER_METER
    }

    private fun threeDistanceFt(horizontalFt: Double, verticalFt: Double): Double =
        sqrt(horizontalFt * horizontalFt + verticalFt * verticalFt)

    private fun destinationPoint(
        startLat: Double,
        startLng: Double,
        bearingDeg: Double,
        distanceM: Double
    ): org.osmdroid.util.GeoPoint {
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
        return org.osmdroid.util.GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}

@Composable
fun ProximityAlertHost(
    onSuspend: () -> Unit,
    onMap: (ProximityAlertUiState) -> Unit
) {
    val context = LocalContext.current
    val alert by ProximityAlertCenter.uiState.collectAsState()
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (_: Exception) {
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose { toneGenerator?.release() }
    }

    LaunchedEffect(alert?.alertInstanceId) {
        if (alert != null) {
            val tone = if (alert?.highSeverity == true) {
                ToneGenerator.TONE_CDMA_HIGH_SS
            } else {
                ToneGenerator.TONE_PROP_BEEP
            }
            toneGenerator?.startTone(tone, 700)
            vibrateBriefly(context)
        }
    }

    alert?.let { uiState ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Proximity Alert") },
            text = { ProximityAlertBody(uiState) },
            confirmButton = {
                TextButton(onClick = { onMap(uiState) }) {
                    Text("Map")
                }
            },
            dismissButton = {
                TextButton(onClick = onSuspend) {
                    Text("Suspend")
                }
            }
        )
    }
}

@Composable
fun ResumeProximityAlertButton() {
    val suspendedAlert by ProximityAlertCenter.suspendedAlert.collectAsState()
    suspendedAlert?.let {
        TextButton(onClick = { ProximityAlertCenter.resumeSuspendedAlert() }) {
            Text("Resume Proximity Alert")
        }
    }
}

@Composable
private fun ProximityAlertBody(alert: ProximityAlertUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Both horizontal and vertical spacing crossed the ${formatFeet(alert.thresholdFt)} threshold.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        ProximityAlertGrid(alert)
    }
}

@Composable
private fun ProximityAlertGrid(alert: ProximityAlertUiState) {
    val orange = Color(0xFFF57C00)
    val red = MaterialTheme.colorScheme.error
    val horizontalColor = if (alert.horizontalSeparationFt < alert.thresholdFt * 0.75) red else orange
    val verticalColor = if (alert.verticalSeparationFt < alert.thresholdFt * 0.75) red else orange
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            GridCell(alert.highestDroneMappedId, emphasis = true)
            Spacer(modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GridCell(alert.nearestDroneMappedId, emphasis = true)
            GridCell(
                primary = "H: ${formatFeet(alert.horizontalSeparationFt)}",
                secondary = "V: ${formatFeet(alert.verticalSeparationFt)}",
                primaryColor = horizontalColor,
                secondaryColor = verticalColor
            )
            GridCell(alert.farthestDroneMappedId, emphasis = true)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            GridCell(alert.lowestDroneMappedId, emphasis = true)
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RowScope.GridCell(
    text: String = "",
    emphasis: Boolean = false,
    primary: String? = null,
    secondary: String? = null,
    primaryColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .sizeIn(minHeight = 58.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            primary != null || secondary != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    primary?.let {
                        Text(it, color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                    secondary?.let {
                        Text(it, color = secondaryColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
            text.isNotBlank() -> {
                Text(
                    text = text,
                    fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private fun formatFeet(value: Double): String =
    String.format(Locale.US, "%.0f ft", value)

private fun vibrateBriefly(context: Context) {
    val effect = VibrationEffect.createWaveform(
        longArrayOf(0L, 180L, 90L, 220L, 110L, 260L),
        intArrayOf(0, 180, 0, 220, 0, 255),
        -1
    )
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    vibrator?.takeIf { it.hasVibrator() }?.vibrate(effect)
}
