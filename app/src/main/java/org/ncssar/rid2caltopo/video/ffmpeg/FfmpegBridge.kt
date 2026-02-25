package org.ncssar.rid2caltopo.video.ffmpeg

import android.util.Log
import android.view.Surface

object FfmpegBridge {
    private const val TAG = "FfmpegBridge"
    private val listeners = linkedSetOf<(String, String, FfmpegTelemetry) -> Unit>()
    private val listenerLock = Any()

    private val nativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("ffmpeg_bridge")
            nativeInitBridge()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "ffmpeg_bridge unavailable: ${t.message}")
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

    fun addProbeListener(listener: (String, String, FfmpegTelemetry) -> Unit) {
        synchronized(listenerLock) {
            listeners += listener
        }
    }

    fun removeProbeListener(listener: (String, String, FfmpegTelemetry) -> Unit) {
        synchronized(listenerLock) {
            listeners -= listener
        }
    }

    private fun normalizeTelemetryFromNative(
        sourceTag: String,
        confidence: Double,
        remoteId: String,
        sourceTimestampUs: Long,
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
        sourceTag: String,
        confidence: Double,
        remoteId: String,
        sourceTimestampUs: Long,
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
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            gimbalPitchDeg = gimbalPitchDeg,
            cameraYawDeg = cameraYawDeg,
            headingDeg = headingDeg,
        )

        val snapshot = synchronized(listenerLock) { listeners.toList() }
        snapshot.forEach { callback ->
            try {
                callback(designator, eventType, telemetry)
            } catch (t: Throwable) {
                Log.w(TAG, "Probe listener failed for $designator: ${t.message}")
            }
        }
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeInitBridge()
    private external fun nativeDecoderBackend(): String
    private external fun nativeStartProbe(designator: String, rtspUrl: String): Long
    private external fun nativeStartRender(designator: String, rtspUrl: String): Long
    private external fun nativeAttachSurface(sessionId: Long, surface: Surface): Boolean
    private external fun nativeDetachSurface(sessionId: Long)
    private external fun nativeStop(sessionId: Long)
}
