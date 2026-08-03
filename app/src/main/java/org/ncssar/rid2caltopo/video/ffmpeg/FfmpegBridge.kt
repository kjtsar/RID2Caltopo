package org.ncssar.rid2caltopo.video.ffmpeg

import android.view.Surface
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn
import org.ncssar.rid2caltopo.data.CaltopoClient.RegisterDebugTags
import org.ncssar.rid2caltopo.video.anomaly.NativeAnomalyConfig

object FfmpegBridge {
    data class VideoSourceInfo(
        val width: Int,
        val height: Int,
        val fps: Double,
        val bitrateBps: Long,
        val codec: String,
    )
    fun interface RemoteVideoFrameListener {
        fun onFrame(
            sessionId: Long,
            width: Int,
            height: Int,
            timestampUs: Long,
            i420: ByteArray,
        )
    }
    enum class PersonRelevanceMode(val nativeValue: Int) {
        OFF(0),
        SHADOW(1),
        POSITIVE_ONLY(2),
    }
    const val TAG = "FfmpegBridge"
    const val NATIVE_TAG = "ffmpeg_bridge"
    private val probeListeners = linkedSetOf<(String, Long, String, FfmpegTelemetry) -> Unit>()
    private val remoteVideoFrameListeners = linkedMapOf<Long, RemoteVideoFrameListener>()
    private val listenerLock = Any()

    init {
        RegisterDebugTags(listOf(TAG, NATIVE_TAG))
    }

    private val nativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("ffmpeg_bridge")
            nativeInitBridge()
            true
        } catch (t: Throwable) {
            CTWarn(TAG, "ffmpeg_bridge unavailable: ${t.message}")
            false
        }
    }

    fun isAvailable(): Boolean {
        return nativeLoaded && nativeIsAvailable()
    }

    fun decoderBackend(): String {
        if (!nativeLoaded) return "missing-native"
        return nativeDecoderBackend()
    }

    fun isRealDecoderBackend(): Boolean {
        return decoderBackend() == "ffmpeg-linked"
    }

    fun startProbe(designator: String, rtspUrl: String): Long {
        if (!isAvailable()) return 0L
        return nativeStartProbe(designator, rtspUrl)
    }

    fun stop(sessionId: Long) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeStop(sessionId)
    }

    fun startRender(designator: String, rtspUrl: String): Long {
        if (!isAvailable()) return 0L
        return nativeStartRender(designator, rtspUrl)
    }

    fun attachSurface(sessionId: Long, surface: Surface): Boolean {
        if (!nativeLoaded || sessionId <= 0L) return false
        return nativeAttachSurface(sessionId, surface)
    }

    fun detachSurface(sessionId: Long) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeDetachSurface(sessionId)
    }

    fun addProbeListener(listener: (String, Long, String, FfmpegTelemetry) -> Unit) {
        synchronized(listenerLock) {
            probeListeners += listener
        }
    }

    fun removeProbeListener(listener: (String, Long, String, FfmpegTelemetry) -> Unit) {
        synchronized(listenerLock) {
            probeListeners -= listener
        }
    }

    fun setRenderStride(sessionId: Long, stride: Int) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeSetRenderStride(sessionId, stride)
    }

    fun startRemoteVideoFrames(
        sessionId: Long,
        width: Int,
        height: Int,
        fps: Double,
        listener: RemoteVideoFrameListener,
    ): Boolean {
        if (!nativeLoaded || sessionId <= 0L) return false
        synchronized(listenerLock) {
            remoteVideoFrameListeners[sessionId] = listener
        }
        val configured = nativeConfigureRemoteVideoFrames(
            sessionId,
            width.coerceAtLeast(2),
            height.coerceAtLeast(2),
            fps.coerceAtLeast(1.0),
            true,
        )
        if (!configured) {
            synchronized(listenerLock) { remoteVideoFrameListeners.remove(sessionId) }
        }
        return configured
    }

    fun stopRemoteVideoFrames(sessionId: Long) {
        synchronized(listenerLock) { remoteVideoFrameListeners.remove(sessionId) }
        if (nativeLoaded && sessionId > 0L) {
            nativeConfigureRemoteVideoFrames(sessionId, 0, 0, 0.0, false)
        }
    }

    fun updateAnomalyConfig(sessionId: Long, config: NativeAnomalyConfig) {
        if (!nativeLoaded || sessionId <= 0L) return
        val nativeTroubleshootingDebug = config.enabled && config.troubleshootingDebug
        nativeUpdateAnomalyConfig(
            sessionId = sessionId,
            enabled = config.enabled,
            showHotOverlay = config.showHotOverlay,
            showCandidateBlobs = config.showCandidateBlobs,
            troubleshootingDebug = nativeTroubleshootingDebug,
            algorithmMask = config.algorithmMask,
            registrationMode = config.registrationMode,
            movementEstimatorMode = config.movementEstimatorMode,
            strideMode = config.strideMode,
            frameStride = config.frameStride,
            adaptiveMinStrideFrames = config.adaptiveMinStrideFrames,
            adaptiveMaxStrideFrames = config.adaptiveMaxStrideFrames,
            adaptiveMaxStrideSeconds = config.adaptiveMaxStrideSeconds,
            pixelStep = config.pixelStep,
            scoreThreshold = config.scoreThreshold,
            motionEvidenceScale = config.motionEvidenceScale,
            minAreaFraction = config.minAreaFraction,
            thermalPolarity = config.thermalPolarity,
            scanZone = config.scanZone,
            minHits = config.minHits,
            thermalMinDelta = config.thermalMinDelta,
            smallTargetScreenFraction = config.smallTargetScreenFraction,
            colorFrontendMode = config.colorFrontendMode,
            colorTargetCandidateLimit = config.colorTargetCandidateLimit,
            targetColorFamilyMask = config.targetColorFamilyMask,
        )
    }

    fun setAnomalyThermalPaused(sessionId: Long, paused: Boolean) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeSetAnomalyThermalPaused(sessionId, paused)
    }

    fun setPersonRelevanceMode(sessionId: Long, mode: PersonRelevanceMode): Boolean {
        if (!nativeLoaded || sessionId <= 0L) return false
        return nativeSetPersonRelevanceMode(sessionId, mode.nativeValue)
    }

    fun sessionPerfStats(sessionId: Long): LongArray? {
        if (!nativeLoaded || sessionId <= 0L) return null
        return nativeGetSessionPerfStats(sessionId)
    }

    fun sessionDebugSummary(sessionId: Long): String? {
        if (!nativeLoaded || sessionId <= 0L) return null
        return nativeGetSessionDebugSummary(sessionId)
    }

    fun videoSourceInfo(sessionId: Long): VideoSourceInfo? {
        if (!nativeLoaded || sessionId <= 0L) return null
        val values = nativeGetVideoSourceInfo(sessionId) ?: return null
        if (values.size < 5 || values[0] <= 0L || values[1] <= 0L) return null
        return VideoSourceInfo(
            width = values[0].toInt(),
            height = values[1].toInt(),
            fps = values[2] / 1_000.0,
            bitrateBps = values[3].coerceAtLeast(0L),
            codec = when (values[4].toInt()) {
                27 -> "H264"
                173 -> "H265"
                else -> ""
            },
        )
    }

    fun setLocalPlaybackPaused(sessionId: Long, paused: Boolean) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeSetLocalPlaybackPaused(sessionId, paused)
    }

    fun stepLocalPlayback(sessionId: Long, frameCount: Int = 1) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeStepLocalPlayback(sessionId, frameCount.coerceAtLeast(1))
    }

    fun stepLocalPlaybackBack(sessionId: Long) {
        if (!nativeLoaded || sessionId <= 0L) return
        nativeStepLocalPlaybackBack(sessionId)
    }

    private fun normalizeTelemetryFromNative(
        sourceTag: String,
        confidence: Double,
        remoteId: String,
        sourceTimestampUs: Long,
        renderLatencyMs: Long,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        gimbalPitchDeg: Double,
        cameraYawDeg: Double,
        headingDeg: Double,
    ): FfmpegTelemetry {
        return FfmpegTelemetry(
            sourceTag = sourceTag.ifBlank { null },
            confidence = confidence.takeIf { !it.isNaN() && it >= 0.0 },
            remoteId = remoteId.ifBlank { null },
            sourceTimestampUs = sourceTimestampUs.takeIf { it > 0L },
            renderLatencyMs = renderLatencyMs.takeIf { it >= 0L },
            latitude = latitude.takeUnless { it.isNaN() },
            longitude = longitude.takeUnless { it.isNaN() },
            altitudeMeters = altitudeMeters.takeUnless { it.isNaN() },
            gimbalPitchDeg = gimbalPitchDeg.takeUnless { it.isNaN() },
            cameraYawDeg = cameraYawDeg.takeUnless { it.isNaN() },
            headingDeg = headingDeg.takeUnless { it.isNaN() },
        )
    }

    @JvmStatic
    fun dispatchNativeProbeEvent(
        designator: String,
        eventType: String,
        sessionId: Long,
        sourceTag: String,
        confidence: Double,
        remoteId: String,
        sourceTimestampUs: Long,
        renderLatencyMs: Long,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        gimbalPitchDeg: Double,
        cameraYawDeg: Double,
        headingDeg: Double,
    ) {
        if (designator.isBlank() || eventType.isBlank()) return
        val telemetry = normalizeTelemetryFromNative(
            sourceTag = sourceTag,
            confidence = confidence,
            remoteId = remoteId,
            sourceTimestampUs = sourceTimestampUs,
            renderLatencyMs = renderLatencyMs,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            gimbalPitchDeg = gimbalPitchDeg,
            cameraYawDeg = cameraYawDeg,
            headingDeg = headingDeg,
        )

        val snapshot = synchronized(listenerLock) { probeListeners.toList() }
        snapshot.forEach { callback ->
            try {
                callback(designator, sessionId, eventType, telemetry)
            } catch (t: Throwable) {
                CTWarn(TAG, "Probe listener failed for $designator: ${t.message}")
            }
        }
    }

    @JvmStatic
    fun dispatchNativeRemoteVideoFrame(
        sessionId: Long,
        width: Int,
        height: Int,
        timestampUs: Long,
        i420: ByteArray,
    ) {
        val listener = synchronized(listenerLock) {
            remoteVideoFrameListeners[sessionId]
        } ?: return
        try {
            listener.onFrame(sessionId, width, height, timestampUs, i420)
        } catch (t: Throwable) {
            CTWarn(TAG, "Remote-video frame listener failed for sessionId=$sessionId: ${t.message}")
        }
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeInitBridge()
    private external fun nativeDecoderBackend(): String
    private external fun nativeStartProbe(designator: String, rtspUrl: String): Long
    private external fun nativeStartRender(designator: String, rtspUrl: String): Long
    private external fun nativeAttachSurface(sessionId: Long, surface: Surface): Boolean
    private external fun nativeDetachSurface(sessionId: Long)
    private external fun nativeSetRenderStride(sessionId: Long, stride: Int)
    private external fun nativeConfigureRemoteVideoFrames(
        sessionId: Long,
        width: Int,
        height: Int,
        fps: Double,
        enabled: Boolean,
    ): Boolean
    private external fun nativeGetVideoSourceInfo(sessionId: Long): LongArray?
    private external fun nativeUpdateAnomalyConfig(
        sessionId: Long,
        enabled: Boolean,
        showHotOverlay: Boolean,
        showCandidateBlobs: Boolean,
        troubleshootingDebug: Boolean,
        algorithmMask: Int,
        registrationMode: Int,
        movementEstimatorMode: Int,
        strideMode: Int,
        frameStride: Int,
        adaptiveMinStrideFrames: Int,
        adaptiveMaxStrideFrames: Int,
        adaptiveMaxStrideSeconds: Float,
        pixelStep: Int,
        scoreThreshold: Float,
        motionEvidenceScale: Float,
        minAreaFraction: Float,
        thermalPolarity: Int,
        scanZone: Float,
        minHits: Int,
        thermalMinDelta: Float,
        smallTargetScreenFraction: Float,
        colorFrontendMode: Int,
        colorTargetCandidateLimit: Int,
        targetColorFamilyMask: Int,
    )
    private external fun nativeSetAnomalyThermalPaused(sessionId: Long, paused: Boolean)
    private external fun nativeSetPersonRelevanceMode(sessionId: Long, mode: Int): Boolean
    private external fun nativeGetSessionPerfStats(sessionId: Long): LongArray?
    private external fun nativeGetSessionDebugSummary(sessionId: Long): String?
    private external fun nativeSetLocalPlaybackPaused(sessionId: Long, paused: Boolean)
    private external fun nativeStepLocalPlayback(sessionId: Long, frameCount: Int)
    private external fun nativeStepLocalPlaybackBack(sessionId: Long)
    private external fun nativeStop(sessionId: Long)
}
