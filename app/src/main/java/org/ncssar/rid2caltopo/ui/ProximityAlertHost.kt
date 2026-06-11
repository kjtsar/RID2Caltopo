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
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
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

data class ComplianceAlertCandidate(
    val remoteId: String,
    val mappedId: String,
    val aglFt: Double,
    val thresholdFt: Double,
    val staleDem: Boolean
)

data class ComplianceAlertUiState(
    val alertInstanceId: Long,
    val mappedId: String,
    val aglFt: Double,
    val thresholdFt: Double,
    val highSeverity: Boolean,
    val staleDem: Boolean
)

private enum class ComplianceAlertSeverity {
    None,
    Near,
    Over
}

object ComplianceAlertCenter {
    private const val NEAR_LIMIT_RATIO = 0.90
    private const val NEAR_ALERT_COOLDOWN_MS = 30_000L
    private const val OVER_ALERT_COOLDOWN_MS = 15_000L

    private val _uiState = MutableStateFlow<ComplianceAlertUiState?>(null)
    val uiState: StateFlow<ComplianceAlertUiState?> = _uiState.asStateFlow()

    private var lastSeverity = ComplianceAlertSeverity.None
    private var lastAlertToneAtMs = 0L
    private var nextAlertInstanceId = 1L

    fun updateCandidates(candidates: List<ComplianceAlertCandidate>) {
        val bestCandidate = candidates
            .filter {
                isLocalAlertEligible(it.remoteId) &&
                    it.aglFt.isFinite() &&
                    it.thresholdFt > 0.0
            }
            .mapNotNull { candidate ->
                val severity = when {
                    candidate.aglFt >= candidate.thresholdFt -> ComplianceAlertSeverity.Over
                    candidate.aglFt >= candidate.thresholdFt * NEAR_LIMIT_RATIO -> ComplianceAlertSeverity.Near
                    else -> ComplianceAlertSeverity.None
                }
                if (severity == ComplianceAlertSeverity.None) null else candidate to severity
            }
            .maxWithOrNull(
                compareBy<Pair<ComplianceAlertCandidate, ComplianceAlertSeverity>> { it.second.ordinal }
                    .thenBy { it.first.aglFt }
            )

        if (bestCandidate == null) {
            lastSeverity = ComplianceAlertSeverity.None
            lastAlertToneAtMs = 0L
            _uiState.value = null
            return
        }

        val (candidate, severity) = bestCandidate
        val nowMs = System.currentTimeMillis()
        val cooldownMs =
            if (severity == ComplianceAlertSeverity.Over) OVER_ALERT_COOLDOWN_MS else NEAR_ALERT_COOLDOWN_MS
        val shouldNotify = severity != lastSeverity || nowMs - lastAlertToneAtMs >= cooldownMs

        lastSeverity = severity

        val currentState = _uiState.value
        val highSeverity = severity == ComplianceAlertSeverity.Over
        if (shouldNotify || currentState == null) {
            _uiState.value = ComplianceAlertUiState(
                alertInstanceId = nextAlertInstanceId++,
                mappedId = candidate.mappedId,
                aglFt = candidate.aglFt,
                thresholdFt = candidate.thresholdFt,
                highSeverity = highSeverity,
                staleDem = candidate.staleDem
            )
            lastAlertToneAtMs = nowMs
        } else if (currentState.mappedId == candidate.mappedId) {
            _uiState.value = currentState.copy(
                aglFt = candidate.aglFt,
                thresholdFt = candidate.thresholdFt,
                highSeverity = highSeverity,
                staleDem = candidate.staleDem
            )
        }
    }

    fun dismissCurrentAlert() {
        _uiState.value = null
    }
}

object ProximityAlertCenter {
    private const val MIN_DRONE_MOVE_FT = 1.0
    private const val MAX_PROJECTION_MS = 2_000L
    private const val MIN_STALE_MULTIPLIER = 1_000L
    private const val CLEAR_DELAY_MS = 3_000L
    private const val FT_PER_METER = 3.28084
    private const val METERS_PER_FOOT = 0.3048
    private const val PROXIMITY_UPDATE_SLOW_MS = 250L

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
        val teamDrone: Boolean,
        val localAlertEligible: Boolean,
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

    internal data class PairThresholdDecision(
        val insideThreshold: Boolean,
        val crossedIntoThreshold: Boolean,
        val isGettingFartherApart: Boolean,
        val predictedCloser: Boolean,
        val actuallyApproaching: Boolean,
        val highSeverity: Boolean,
        val shouldAlert: Boolean,
        val severityScore: Double
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
        val altitudeSensitive: Boolean,
        val shouldAlert: Boolean,
        val isGettingFartherApart: Boolean,
        val highSeverity: Boolean,
        val severityScore: Double
    )

    private val _uiState = MutableStateFlow<ProximityAlertUiState?>(null)
    val uiState: StateFlow<ProximityAlertUiState?> = _uiState.asStateFlow()
    private val _suspendedAlert = MutableStateFlow<ProximityAlertUiState?>(null)
    val suspendedAlert: StateFlow<ProximityAlertUiState?> = _suspendedAlert.asStateFlow()
    private val _canResumeAlert = MutableStateFlow(false)
    val canResumeAlert: StateFlow<Boolean> = _canResumeAlert.asStateFlow()
    private val _debugPairs = MutableStateFlow<List<ProximityDebugPair>>(emptyList())
    val debugPairs: StateFlow<List<ProximityDebugPair>> = _debugPairs.asStateFlow()

    private val sampleHistoryByRemoteId = linkedMapOf<String, ArrayDeque<DroneSample>>()
    private val previousPairSnapshots = linkedMapOf<String, PairSnapshot>()
    private var latestEvaluationsByKey = emptyMap<String, PairEvaluation>()
    private var alertsSuspended = false
    private var clearEligibleSinceMs: Long? = null

    private fun logUpdateIfSlow(
        elapsedMs: Long,
        inputCount: Int,
        activeCount: Int,
        evaluationCount: Int
    ) {
        if (elapsedMs < PROXIMITY_UPDATE_SLOW_MS) return
        CaltopoClient.CTWarn(
            "ProximityAlertCenter",
            String.format(
                Locale.US,
                "updateDrones slow elapsedMs=%d input=%d active=%d pairs=%d thread=%s",
                elapsedMs,
                inputCount,
                activeCount,
                evaluationCount,
                Thread.currentThread().name
            )
        )
    }

    fun updateDrones(drones: List<CtDroneSpec>) {
        val startedAtMs = System.currentTimeMillis()
        val nowMs = System.currentTimeMillis()
        val thresholdFt = CaltopoClient.GetProximityAlertSpacingFeet().toDouble()
        if (thresholdFt <= 0.0) {
            alertsSuspended = false
            clearEligibleSinceMs = null
            _uiState.value = null
            _suspendedAlert.value = null
            _canResumeAlert.value = false
            _debugPairs.value = emptyList()
            latestEvaluationsByKey = emptyMap()
            logUpdateIfSlow(
                elapsedMs = System.currentTimeMillis() - startedAtMs,
                inputCount = drones.size,
                activeCount = 0,
                evaluationCount = 0
            )
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
                val clearCondition = currentEval == null || !currentEval.isInsideThreshold(thresholdFt)
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

        refreshResumeVisibility()

        previousPairSnapshots.clear()
        evaluations.forEach { evaluation ->
            previousPairSnapshots[evaluation.pairKey] = PairSnapshot(
                effectiveHorizontalFt = evaluation.effectiveHorizontalFt,
                effectiveVerticalFt = if (evaluation.altitudeSensitive) evaluation.effectiveVerticalFt else 0.0,
                effectiveThreeDFt = if (evaluation.altitudeSensitive) {
                    evaluation.effectiveThreeDFt
                } else {
                    evaluation.effectiveHorizontalFt
                }
            )
        }
        logUpdateIfSlow(
            elapsedMs = System.currentTimeMillis() - startedAtMs,
            inputCount = drones.size,
            activeCount = activeDrones.size,
            evaluationCount = evaluations.size
        )
    }

    fun suspendCurrentAlert() {
        alertsSuspended = true
        clearEligibleSinceMs = null
        _suspendedAlert.value = _uiState.value
        _uiState.value = null
        refreshResumeVisibility()
    }

    fun resumeSuspendedAlert() {
        val suspended = _suspendedAlert.value ?: return
        val currentEval = latestEvaluationsByKey[suspended.pairKey]
        val stillWithinThreshold = currentEval != null && currentEval.isInsideThreshold(suspended.thresholdFt)
        alertsSuspended = false
        if (stillWithinThreshold) {
            _uiState.value = currentEval.toUiState(suspended.alertInstanceId, suspended.thresholdFt)
            _suspendedAlert.value = null
        } else {
            _uiState.value = null
            _suspendedAlert.value = null
        }
        clearEligibleSinceMs = null
        refreshResumeVisibility()
    }

    private fun refreshResumeVisibility() {
        val suspended = _suspendedAlert.value
        _canResumeAlert.value = suspended != null && latestEvaluationsByKey[suspended.pairKey]?.let { evaluation ->
            evaluation.isInsideThreshold(suspended.thresholdFt)
        } == true
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
            teamDrone = !spec.isLocalArchiveOnly,
            localAlertEligible = isLocalAlertEligible(spec.remoteId),
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

        val altitudeSensitive = first.teamDrone && second.teamDrone
        val decisionCurrentVerticalFt = if (altitudeSensitive) currentVerticalFt else 0.0
        val decisionEffectiveVerticalFt = if (altitudeSensitive) effectiveVerticalFt else 0.0
        val decisionCurrentThreeDFt = threeDistanceFt(currentHorizontalFt, decisionCurrentVerticalFt)
        val decisionEffectiveThreeDFt = threeDistanceFt(effectiveHorizontalFt, decisionEffectiveVerticalFt)
        val pairKey = pairKey(first.remoteId, second.remoteId)
        val previous = previousPairSnapshots[pairKey]
        val decision = evaluateThresholdDecision(
            effectiveHorizontalFt = effectiveHorizontalFt,
            effectiveVerticalFt = decisionEffectiveVerticalFt,
            effectiveThreeDFt = decisionEffectiveThreeDFt,
            currentThreeDFt = decisionCurrentThreeDFt,
            thresholdFt = thresholdFt,
            predictionEnabled = predictionEnabled,
            previous = previous
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
            altitudeSensitive = altitudeSensitive,
            shouldAlert = decision.shouldAlert && shouldAlertForPair(first, second),
            isGettingFartherApart = decision.isGettingFartherApart,
            highSeverity = decision.highSeverity,
            severityScore = decision.severityScore
        )
    }

    private fun shouldAlertForPair(first: EvaluatedDrone, second: EvaluatedDrone): Boolean =
        (first.teamDrone || second.teamDrone) &&
            (first.localAlertEligible || second.localAlertEligible)

    internal fun shouldAlertForPairForTests(
        firstTeamDrone: Boolean,
        firstLocalAlertEligible: Boolean,
        secondTeamDrone: Boolean,
        secondLocalAlertEligible: Boolean
    ): Boolean =
        (firstTeamDrone || secondTeamDrone) &&
            (firstLocalAlertEligible || secondLocalAlertEligible)

    internal fun resetForTests() {
        _uiState.value = null
        _suspendedAlert.value = null
        _canResumeAlert.value = false
        _debugPairs.value = emptyList()
        sampleHistoryByRemoteId.clear()
        previousPairSnapshots.clear()
        latestEvaluationsByKey = emptyMap()
        alertsSuspended = false
        clearEligibleSinceMs = null
    }

    private fun evaluateThresholdDecision(
        effectiveHorizontalFt: Double,
        effectiveVerticalFt: Double,
        effectiveThreeDFt: Double,
        currentThreeDFt: Double,
        thresholdFt: Double,
        predictionEnabled: Boolean,
        previous: PairSnapshot?
    ): PairThresholdDecision {
        val insideThreshold =
            effectiveHorizontalFt <= thresholdFt && effectiveVerticalFt <= thresholdFt
        val crossedIntoThreshold = previous == null ||
            previous.effectiveHorizontalFt > thresholdFt ||
            previous.effectiveVerticalFt > thresholdFt
        val isGettingFartherApart = previous != null &&
            effectiveThreeDFt > previous.effectiveThreeDFt + MIN_DRONE_MOVE_FT
        val predictedCloser = effectiveThreeDFt + MIN_DRONE_MOVE_FT < currentThreeDFt
        val actuallyApproaching = previous == null ||
            effectiveThreeDFt + MIN_DRONE_MOVE_FT < previous.effectiveThreeDFt
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
        return PairThresholdDecision(
            insideThreshold = insideThreshold,
            crossedIntoThreshold = crossedIntoThreshold,
            isGettingFartherApart = isGettingFartherApart,
            predictedCloser = predictedCloser,
            actuallyApproaching = actuallyApproaching,
            highSeverity = highSeverity,
            shouldAlert = shouldAlert,
            severityScore = severityScore
        )
    }

    internal fun evaluateThresholdDecisionForTests(
        effectiveHorizontalFt: Double,
        effectiveVerticalFt: Double,
        effectiveThreeDFt: Double,
        currentThreeDFt: Double,
        thresholdFt: Double,
        predictionEnabled: Boolean,
        altitudeSensitive: Boolean = true,
        previousHorizontalFt: Double? = null,
        previousVerticalFt: Double? = null,
        previousThreeDFt: Double? = null
    ): PairThresholdDecision {
        val decisionEffectiveVerticalFt = if (altitudeSensitive) effectiveVerticalFt else 0.0
        val decisionEffectiveThreeDFt = if (altitudeSensitive) {
            effectiveThreeDFt
        } else {
            threeDistanceFt(effectiveHorizontalFt, decisionEffectiveVerticalFt)
        }
        val decisionCurrentThreeDFt = if (altitudeSensitive) {
            currentThreeDFt
        } else {
            threeDistanceFt(effectiveHorizontalFt, decisionEffectiveVerticalFt)
        }
        val previous = if (
            previousHorizontalFt != null &&
            previousVerticalFt != null &&
            previousThreeDFt != null
        ) {
            PairSnapshot(
                effectiveHorizontalFt = previousHorizontalFt,
                effectiveVerticalFt = previousVerticalFt,
                effectiveThreeDFt = previousThreeDFt
            )
        } else {
            null
        }
        return evaluateThresholdDecision(
            effectiveHorizontalFt = effectiveHorizontalFt,
            effectiveVerticalFt = decisionEffectiveVerticalFt,
            effectiveThreeDFt = decisionEffectiveThreeDFt,
            currentThreeDFt = decisionCurrentThreeDFt,
            thresholdFt = thresholdFt,
            predictionEnabled = predictionEnabled,
            previous = previous
        )
    }

    private fun PairEvaluation.isInsideThreshold(thresholdFt: Double): Boolean =
        effectiveHorizontalFt <= thresholdFt &&
            (!altitudeSensitive || effectiveVerticalFt <= thresholdFt)

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

private fun isLocalAlertEligible(remoteId: String): Boolean =
    R2cRuntimeRegistry.getDefaultRuntime().peerCoordinator.isLocalAlertEligible(remoteId)

@Composable
fun ProximityAlertHost(
    onSuspend: () -> Unit,
    onMap: (ProximityAlertUiState) -> Unit
) {
    val context = LocalContext.current
    val alert by ProximityAlertCenter.uiState.collectAsState()
    val toneGenerator = remember(alert?.alertInstanceId) {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, CaltopoClient.GetToneGeneratorAlarmVolumePercent())
        } catch (_: Exception) {
            null
        }
    }

    DisposableEffect(toneGenerator) {
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
fun ComplianceAlertHost() {
    val context = LocalContext.current
    val alert by ComplianceAlertCenter.uiState.collectAsState()
    val toneGenerator = remember(alert?.alertInstanceId) {
        try {
            ToneGenerator(AudioManager.STREAM_ALARM, CaltopoClient.GetToneGeneratorAlarmVolumePercent())
        } catch (_: Exception) {
            null
        }
    }

    DisposableEffect(toneGenerator) {
        onDispose { toneGenerator?.release() }
    }

    LaunchedEffect(alert?.alertInstanceId) {
        alert?.let { uiState ->
            val tone = if (uiState.highSeverity) {
                ToneGenerator.TONE_CDMA_HIGH_SS
            } else {
                ToneGenerator.TONE_PROP_BEEP
            }
            toneGenerator?.startTone(tone, if (uiState.highSeverity) 350 else 220)
            vibrateBriefly(context)
            val staleSuffix = if (uiState.staleDem) " (DEM AGL may be stale)" else ""
            val toastMessage = if (uiState.highSeverity) {
                "${uiState.mappedId} above ${formatFeet(uiState.thresholdFt)} AGL at ${formatFeet(uiState.aglFt)}$staleSuffix"
            } else {
                "${uiState.mappedId} near ${formatFeet(uiState.thresholdFt)} AGL at ${formatFeet(uiState.aglFt)}$staleSuffix"
            }
            CaltopoClient.ShowToast(toastMessage)
        }
    }
}

@Composable
fun ResumeProximityAlertButton() {
    val canResume by ProximityAlertCenter.canResumeAlert.collectAsState()
    if (canResume) {
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
    val effect = VibrationEffect.createOneShot(
        180L,
        VibrationEffect.DEFAULT_AMPLITUDE
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
