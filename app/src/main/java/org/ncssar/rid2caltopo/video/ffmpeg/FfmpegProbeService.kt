package org.ncssar.rid2caltopo.video.ffmpeg

import android.util.Log
import android.view.Surface
import org.ncssar.rid2caltopo.BuildConfig
import org.ncssar.rid2caltopo.data.DelayedExec

data class StreamTelemetrySnapshot(
    val sourceTag: String? = null,
    val confidence: Double? = null,
    val sourceTimestampUs: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gimbalPitchDeg: Double? = null,
    val cameraYawDeg: Double? = null,
    val headingDeg: Double? = null,
    val latestRemoteId: String? = null,
    val remoteIdCandidates: List<String> = emptyList(),
)

data class TelemetryMergeResult(
    val mergedTelemetry: FfmpegTelemetry,
    val updatedCandidates: LinkedHashSet<String>,
    val addedRemoteId: String? = null,
)

object FfmpegTelemetryReducer {
    fun merge(
        existing: FfmpegTelemetry?,
        incoming: FfmpegTelemetry,
        existingCandidates: Set<String>,
    ): TelemetryMergeResult {
        val merged = FfmpegTelemetry(
            sourceTag = incoming.sourceTag ?: existing?.sourceTag,
            confidence = incoming.confidence ?: existing?.confidence,
            remoteId = incoming.remoteId ?: existing?.remoteId,
            sourceTimestampUs = incoming.sourceTimestampUs ?: existing?.sourceTimestampUs,
            latitude = incoming.latitude ?: existing?.latitude,
            longitude = incoming.longitude ?: existing?.longitude,
            altitudeMeters = incoming.altitudeMeters ?: existing?.altitudeMeters,
            gimbalPitchDeg = incoming.gimbalPitchDeg ?: existing?.gimbalPitchDeg,
            cameraYawDeg = incoming.cameraYawDeg ?: existing?.cameraYawDeg,
            headingDeg = incoming.headingDeg ?: existing?.headingDeg,
        )
        val candidates = LinkedHashSet(existingCandidates)
        val addedRemoteId = incoming.remoteId?.takeIf { candidates.add(it) }
        return TelemetryMergeResult(
            mergedTelemetry = merged,
            updatedCandidates = candidates,
            addedRemoteId = addedRemoteId,
        )
    }
}

data class NoFrameRestartDecision(
    val shouldRestart: Boolean,
    val reason: String,
)

object NoFrameRestartPolicy {
    fun evaluate(
        nowMs: Long,
        liveAtMs: Long?,
        lastFrameAtMs: Long?,
        lastRestartAtMs: Long?,
        startupGraceMs: Long,
        noFrameTimeoutMs: Long,
        restartCooldownMs: Long,
    ): NoFrameRestartDecision {
        val liveAt = liveAtMs ?: return NoFrameRestartDecision(false, "not-live")
        if (nowMs - liveAt < startupGraceMs) return NoFrameRestartDecision(false, "startup-grace")
        val frameAt = lastFrameAtMs ?: liveAt
        if (nowMs - frameAt <= noFrameTimeoutMs) return NoFrameRestartDecision(false, "frame-recent")
        val lastRestart = lastRestartAtMs ?: 0L
        if (nowMs - lastRestart < restartCooldownMs) {
            return NoFrameRestartDecision(false, "restart-cooldown")
        }
        return NoFrameRestartDecision(true, "restart-needed")
    }
}

class FfmpegProbeService {
    private val tag = "FfmpegProbeService"
    private val startupGraceMs = 4_000L
    private val noFrameTimeoutMs = 8_000L
    private val noFrameCheckMs = 2_000L
    private val restartCooldownMs = 3_000L
    private val probeSessions = mutableMapOf<String, Long>()
    private val renderSessions = mutableMapOf<String, Long>()
    private val lastFrameLogAtMs = mutableMapOf<String, Long>()
    private val lastFrameAtMs = mutableMapOf<String, Long>()
    private val streamLiveAtMs = mutableMapOf<String, Long>()
    private val lastRestartAtMs = mutableMapOf<String, Long>()
    private val boundRenderSurfaces = mutableMapOf<String, Surface>()
    private val telemetryByDesignator = mutableMapOf<String, FfmpegTelemetry>()
    private val remoteIdCandidatesByDesignator = mutableMapOf<String, LinkedHashSet<String>>()
    private val noFramePoll = DelayedExec()
    private val stateLock = Any()
    private val probeListener: (String, String, FfmpegTelemetry) -> Unit = { designator, eventType, telemetry ->
        if (eventType == "telemetry") {
            mergeTelemetry(designator, telemetry)
        }

        val now = System.currentTimeMillis()
        if (eventType.startsWith("frame_")) {
            synchronized(stateLock) {
                lastFrameAtMs[designator] = now
            }
        }

        var shouldLog = true
        if (eventType.startsWith("frame_")) {
            synchronized(stateLock) {
                val last = lastFrameLogAtMs[designator] ?: 0L
                shouldLog = (now - last >= 2_000L)
                if (shouldLog) {
                    lastFrameLogAtMs[designator] = now
                }
            }
        }
        if (shouldLog) {
            Log.d(tag, "Probe event designator=$designator type=$eventType telemetry=$telemetry")
        }
    }

    init {
        FfmpegBridge.addProbeListener(probeListener)
        Log.i(tag, "FFmpeg bridge backend=${FfmpegBridge.decoderBackend()}")
        if (!FfmpegBridge.isRealDecoderBackend()) {
            Log.w(tag, "FFmpeg decoder backend is stub. Real video decode is not active yet.")
        }
        noFramePoll.start(this::pollForNoFrameSessions, noFrameCheckMs, noFrameCheckMs)
    }

    fun onStreamBecameLive(designator: String) {
        synchronized(stateLock) {
            streamLiveAtMs.putIfAbsent(designator, System.currentTimeMillis())
        }
        val renderEnabled = isRenderEnabled(designator)
        if (renderEnabled) {
            ensureRenderSession(designator)
        } else {
            ensureProbeSession(designator)
        }
    }

    fun onStreamStopped(designator: String) {
        val renderSessionId: Long?
        val probeSessionId: Long?
        synchronized(stateLock) {
            renderSessionId = renderSessions.remove(designator)
            probeSessionId = probeSessions.remove(designator)
            lastFrameLogAtMs.remove(designator)
            lastFrameAtMs.remove(designator)
            streamLiveAtMs.remove(designator)
            lastRestartAtMs.remove(designator)
            boundRenderSurfaces.remove(designator)
            telemetryByDesignator.remove(designator)
            remoteIdCandidatesByDesignator.remove(designator)
        }
        renderSessionId?.let { sessionId ->
            FfmpegBridge.stop(sessionId)
            Log.d(tag, "Stopped FFmpeg render for $designator sessionId=$sessionId")
        }
        probeSessionId?.let { sessionId ->
            FfmpegBridge.stop(sessionId)
            Log.d(tag, "Stopped FFmpeg probe for $designator sessionId=$sessionId")
        }
    }

    fun stopAll() {
        val active = synchronized(stateLock) {
            val snapshot = (probeSessions.values + renderSessions.values).toList()
            probeSessions.clear()
            renderSessions.clear()
            lastFrameAtMs.clear()
            streamLiveAtMs.clear()
            lastRestartAtMs.clear()
            boundRenderSurfaces.clear()
            telemetryByDesignator.clear()
            remoteIdCandidatesByDesignator.clear()
            snapshot
        }
        noFramePoll.stop()
        active.forEach { sessionId -> FfmpegBridge.stop(sessionId) }
    }

    fun close() {
        stopAll()
        FfmpegBridge.removeProbeListener(probeListener)
    }

    fun isRenderEnabled(designator: String): Boolean {
        if (!BuildConfig.ENABLE_FFMPEG_RENDER) return false
        val configured = BuildConfig.FFMPEG_RENDER_DESIGNATOR.trim()
        return configured.isEmpty() || configured.equals(designator, ignoreCase = true)
    }

    fun bindRenderSurface(designator: String, surface: Surface): Boolean {
        if (!isRenderEnabled(designator)) return false
        synchronized(stateLock) {
            boundRenderSurfaces[designator] = surface
        }
        val sessionId = ensureRenderSession(designator) ?: return false
        return FfmpegBridge.attachSurface(sessionId, surface)
    }

    fun unbindRenderSurface(designator: String) {
        val sessionId = synchronized(stateLock) {
            boundRenderSurfaces.remove(designator)
            renderSessions[designator]
        } ?: return
        FfmpegBridge.detachSurface(sessionId)
    }

    fun telemetrySnapshot(designator: String): StreamTelemetrySnapshot? {
        val mergedAndCandidates = synchronized(stateLock) {
            val merged = telemetryByDesignator[designator] ?: return null
            merged to remoteIdCandidatesByDesignator[designator]?.toList().orEmpty()
        }
        val merged = mergedAndCandidates.first
        val candidates = mergedAndCandidates.second
        return StreamTelemetrySnapshot(
            sourceTag = merged.sourceTag,
            confidence = merged.confidence,
            sourceTimestampUs = merged.sourceTimestampUs,
            latitude = merged.latitude,
            longitude = merged.longitude,
            altitudeMeters = merged.altitudeMeters,
            gimbalPitchDeg = merged.gimbalPitchDeg,
            cameraYawDeg = merged.cameraYawDeg,
            headingDeg = merged.headingDeg,
            latestRemoteId = merged.remoteId,
            remoteIdCandidates = candidates,
        )
    }

    private fun ensureProbeSession(designator: String): Long? {
        if (!BuildConfig.ENABLE_FFMPEG_PROBE) return null
        synchronized(stateLock) {
            probeSessions[designator]?.let { return it }
        }
        val renderSessionId = synchronized(stateLock) {
            renderSessions.remove(designator)
        }
        renderSessionId?.let {
            FfmpegBridge.stop(renderSessionId)
            Log.d(tag, "Stopped FFmpeg render for $designator before starting probe sessionId=$renderSessionId")
        }

        val rtspUrl = "rtsp://127.0.0.1:8554/$designator"
        val sessionId = FfmpegBridge.startProbe(designator, rtspUrl)
        if (sessionId > 0L) {
            synchronized(stateLock) {
                val existing = probeSessions[designator]
                if (existing != null && existing != sessionId) {
                    FfmpegBridge.stop(sessionId)
                    return existing
                }
                probeSessions[designator] = sessionId
            }
            Log.d(tag, "Started FFmpeg probe for $designator sessionId=$sessionId")
            return sessionId
        }
        Log.w(tag, "Unable to start FFmpeg probe for $designator")
        return null
    }

    private fun ensureRenderSession(designator: String): Long? {
        synchronized(stateLock) {
            renderSessions[designator]?.let { return it }
        }
        val probeSessionId = synchronized(stateLock) {
            probeSessions.remove(designator)
        }
        probeSessionId?.let {
            FfmpegBridge.stop(probeSessionId)
            Log.d(tag, "Stopped FFmpeg probe for $designator before starting render sessionId=$probeSessionId")
        }

        val rtspUrl = "rtsp://127.0.0.1:8554/$designator"
        val sessionId = FfmpegBridge.startRender(designator, rtspUrl)
        if (sessionId > 0L) {
            val reboundSurface = synchronized(stateLock) {
                val existing = renderSessions[designator]
                if (existing != null && existing != sessionId) {
                    FfmpegBridge.stop(sessionId)
                    return existing
                }
                renderSessions[designator] = sessionId
                boundRenderSurfaces[designator]
            }
            Log.d(tag, "Started FFmpeg render for $designator sessionId=$sessionId")
            reboundSurface?.let { surface ->
                if (FfmpegBridge.attachSurface(sessionId, surface)) {
                    Log.d(tag, "Reattached render surface for $designator sessionId=$sessionId")
                }
            }
            return sessionId
        }
        Log.w(tag, "Unable to start FFmpeg render for $designator")
        return null
    }

    private fun pollForNoFrameSessions() {
        val now = System.currentTimeMillis()
        val restartPlan = mutableListOf<Pair<String, Boolean>>()
        synchronized(stateLock) {
            val activeDesignators = (renderSessions.keys + probeSessions.keys).toSet()
            activeDesignators.forEach { designator ->
                val decision = NoFrameRestartPolicy.evaluate(
                    nowMs = now,
                    liveAtMs = streamLiveAtMs[designator],
                    lastFrameAtMs = lastFrameAtMs[designator],
                    lastRestartAtMs = lastRestartAtMs[designator],
                    startupGraceMs = startupGraceMs,
                    noFrameTimeoutMs = noFrameTimeoutMs,
                    restartCooldownMs = restartCooldownMs,
                )
                if (!decision.shouldRestart) return@forEach
                lastRestartAtMs[designator] = now
                when {
                    renderSessions.containsKey(designator) -> restartPlan += designator to true
                    probeSessions.containsKey(designator) -> restartPlan += designator to false
                }
            }
        }
        restartPlan.forEach { (designator, isRender) ->
            if (isRender) restartRenderSession(designator) else restartProbeSession(designator)
        }
    }

    private fun restartProbeSession(designator: String) {
        val sessionId = synchronized(stateLock) {
            probeSessions.remove(designator)
        }
        sessionId?.let {
            FfmpegBridge.stop(sessionId)
            Log.w(tag, "No frames for $designator probe -> restarting sessionId=$sessionId")
        }
        ensureProbeSession(designator)
    }

    private fun restartRenderSession(designator: String) {
        val sessionId = synchronized(stateLock) {
            renderSessions.remove(designator)
        }
        sessionId?.let {
            FfmpegBridge.stop(sessionId)
            Log.w(tag, "No frames for $designator render -> restarting sessionId=$sessionId")
        }
        ensureRenderSession(designator)
    }

    private fun mergeTelemetry(designator: String, incoming: FfmpegTelemetry) {
        val result = synchronized(stateLock) {
            val mergedResult = FfmpegTelemetryReducer.merge(
                existing = telemetryByDesignator[designator],
                incoming = incoming,
                existingCandidates = remoteIdCandidatesByDesignator[designator].orEmpty(),
            )
            telemetryByDesignator[designator] = mergedResult.mergedTelemetry
            remoteIdCandidatesByDesignator[designator] = mergedResult.updatedCandidates
            mergedResult
        }
        result.addedRemoteId?.let { rid ->
            Log.i(tag, "Telemetry remoteId candidate for $designator: $rid")
        }
    }
}
