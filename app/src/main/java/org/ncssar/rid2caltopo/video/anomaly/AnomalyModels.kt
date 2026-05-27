package org.ncssar.rid2caltopo.video.anomaly

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val DEFAULT_FRAME_STRIDE = 1
private const val DEFAULT_ADAPTIVE_MIN_STRIDE_FRAMES = 2
private const val DEFAULT_ADAPTIVE_MAX_STRIDE_SECONDS = 1.0f
private const val COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES = 4

enum class AnomalyAlgorithm(
    val nativeMask: Int,
    val label: String,
) {
    ColorOutlier(nativeMask = 0x01, label = "Color Outlier"),
    ThermalHotspot(nativeMask = 0x02, label = "Thermal Hotspot"),
    Motion(nativeMask = 0x04, label = "Motion"),
    PersistentDarkPatch(nativeMask = 0x08, label = "Unified Saliency");

    companion object {
        fun fromNativeName(name: String): AnomalyAlgorithm? {
            if (name.isBlank()) return null
            return when (name.trim().lowercase()) {
                "color", "color_outlier", "color-outlier" -> ColorOutlier
                "thermal", "thermal_hotspot", "thermal-hotspot", "hotspot" -> ThermalHotspot
                "motion", "movement" -> Motion
                "persist", "persistent", "persistent_dark_patch", "persistent-dark-patch", "dark-patch",
                "saliency", "thermal_saliency", "thermal-saliency",
                "unified_saliency", "unified-saliency", "multi_cue_saliency", "multi-cue-saliency" -> PersistentDarkPatch
                else -> null
            }
        }
    }
}

enum class AppearanceAnomalyMode(
    val algorithm: AnomalyAlgorithm,
    val label: String,
) {
    Thermal(algorithm = AnomalyAlgorithm.ThermalHotspot, label = "Infrared"),
    Color(algorithm = AnomalyAlgorithm.ColorOutlier, label = "Color Outlier");
}

enum class AppearanceAnomalySelection(
    val label: String,
) {
    Auto(label = "Auto"),
    Thermal(label = "Infrared"),
    Color(label = "Color");

    fun resolved(fallback: AppearanceAnomalyMode = AppearanceAnomalyMode.Thermal): AppearanceAnomalyMode =
        when (this) {
            Auto -> fallback
            Thermal -> AppearanceAnomalyMode.Thermal
            Color -> AppearanceAnomalyMode.Color
        }
}

enum class ThermalPolarity(
    val nativeValue: Int,
    val label: String,
) {
    WhiteHot(nativeValue = 1, label = "White Hot"),
    BlackHot(nativeValue = 2, label = "Black Hot");

    fun next(): ThermalPolarity = when (this) {
        WhiteHot -> BlackHot
        BlackHot -> WhiteHot
    }
}

enum class MotionRegistrationMode(
    val nativeValue: Int,
    val label: String,
) {
    Gmv(nativeValue = 1, label = "GMV"),
    Affine(nativeValue = 2, label = "Affine");
}

enum class MovementEstimatorMode(
    val nativeValue: Int,
    val label: String,
) {
    LegacyAffine(nativeValue = 0, label = "Legacy"),
    LayeredShadow(nativeValue = 1, label = "Layered Shadow"),
    LayeredActive(nativeValue = 2, label = "Layered Active");

    fun next(): MovementEstimatorMode = when (this) {
        LegacyAffine -> LayeredShadow
        LayeredShadow -> LayeredActive
        LayeredActive -> LegacyAffine
    }
}

enum class AnomalyStrideMode(
    val nativeValue: Int,
    val label: String,
) {
    Fixed(nativeValue = 0, label = "Fixed"),
    Adaptive(nativeValue = 1, label = "Adaptive");
}

data class NativeAnomalyConfig(
    val enabled: Boolean,
    val showHotOverlay: Boolean,
    val showCandidateBlobs: Boolean,
    val troubleshootingDebug: Boolean,
    val algorithmMask: Int,
    val registrationMode: Int,
    val movementEstimatorMode: Int,
    val strideMode: Int,
    val frameStride: Int,
    val adaptiveMinStrideFrames: Int,
    val adaptiveMaxStrideFrames: Int,
    val adaptiveMaxStrideSeconds: Float,
    val pixelStep: Int,
    val scoreThreshold: Float,
    val motionEvidenceScale: Float,
    val minAreaFraction: Float,
    val thermalPolarity: Int,
    val scanZone: Float,
    val minHits: Int,
    val thermalMinDelta: Float,
    val smallTargetScreenFraction: Float,
    val colorFrontendMode: Int,
)

enum class ColorFrontendMode(val nativeValue: Int) {
    Legacy(0),
    FreshRgba(1),
    FreshYuv(2),
}

data class AnomalyConfig(
    val enabled: Boolean = false,
    val showGuideBoxes: Boolean = true,
    val showHotOverlay: Boolean = false,
    val showCandidateBlobs: Boolean = false,
    val troubleshootingDebug: Boolean = false,
    val algorithms: Set<AnomalyAlgorithm> = setOf(AnomalyAlgorithm.Motion),
    val saliencyEnabled: Boolean = false,
    val appearanceSelection: AppearanceAnomalySelection = AppearanceAnomalySelection.Auto,
    val strideMode: AnomalyStrideMode = AnomalyStrideMode.Fixed,
    val frameStride: Int = 1,
    val adaptiveMinStrideFrames: Int = 2,
    val adaptiveMaxStrideSeconds: Float = 1.0f,
    val pixelStep: Int = 0,
    val sensitivity: Float = 0.42f,
    val motionEvidenceSensitivity: Float = 0.60f,
    val minAreaFraction: Float = 0.0015f,
    val thermalPolarity: ThermalPolarity = ThermalPolarity.BlackHot,
    val registrationMode: MotionRegistrationMode = MotionRegistrationMode.Affine,
    val movementEstimatorMode: MovementEstimatorMode = MovementEstimatorMode.LegacyAffine,
    val scanZone: Float = 0.50f,
    val minHits: Int = 2,
    val thermalMinDelta: Float = 10.0f,
    val smallTargetScreenFraction: Float = 1.0f / 200.0f,
    val colorFrontendMode: ColorFrontendMode = ColorFrontendMode.Legacy,
) {
    val nonAppearanceAlgorithms: Set<AnomalyAlgorithm>
        get() = algorithms.filterNot {
            it == AnomalyAlgorithm.ThermalHotspot || it == AnomalyAlgorithm.ColorOutlier
        }.toSet()

    val sensitivityLabel: String
        get() = String.format(Locale.US, "%d%%", (sensitivity.coerceIn(0f, 1f) * 100f).toInt())

    val scanZoneLabel: String
        get() = String.format(Locale.US, "%d%%", (scanZone.coerceIn(0.5f, 1f) * 100f).toInt())

    val pixelStepLabel: String
        get() = if (pixelStep <= 0) "Auto" else "${pixelStep}px"

    val motionEvidenceSensitivityLabel: String
        get() = String.format(Locale.US, "%d%%", (motionEvidenceSensitivity.coerceIn(0f, 1f) * 100f).toInt())

    val smallTargetScaleLabel: String
        get() {
            val denom = (1.0f / smallTargetScreenFraction.coerceIn(0.0015f, 0.03f)).toInt()
            return "1/$denom"
        }

    fun scanZoneSize(frameWidth: Float, frameHeight: Float): Pair<Float, Float> {
        val normalizedZone = scanZone.coerceIn(0.5f, 1f)
        return Pair(
            frameWidth.coerceAtLeast(1f) * normalizedZone,
            frameHeight.coerceAtLeast(1f) * normalizedZone,
        )
    }

    fun effectiveSmallTargetSpanPx(frameWidth: Int, frameHeight: Int): Float {
        val fw = frameWidth.coerceAtLeast(1).toFloat()
        val fh = frameHeight.coerceAtLeast(1).toFloat()
        val diagonal = sqrt((fw * fw) + (fh * fh))
        return (diagonal * smallTargetScreenFraction.coerceIn(0.0015f, 0.03f)).coerceAtLeast(2.0f)
    }

    fun resolvedAppearanceMode(
        detectedMode: AppearanceAnomalyMode? = null,
    ): AppearanceAnomalyMode = appearanceSelection.resolved(detectedMode ?: AppearanceAnomalyMode.Thermal)

    fun resolvedAlgorithms(
        detectedMode: AppearanceAnomalyMode? = null,
    ): Set<AnomalyAlgorithm> {
        val resolved = nonAppearanceAlgorithms.toMutableSet()
        resolved += resolvedAppearanceMode(detectedMode).algorithm
        if (saliencyEnabled) {
            resolved += AnomalyAlgorithm.PersistentDarkPatch
        }
        return resolved
    }

    fun withAppearanceSelection(selection: AppearanceAnomalySelection): AnomalyConfig {
        val updated = copy(
            appearanceSelection = selection,
            algorithms = nonAppearanceAlgorithms
        )
        return if (selection == AppearanceAnomalySelection.Color) {
            updated.withColorRealtimeStrideDefaultsIfUnmodified()
        } else {
            updated
        }
    }

    fun withColorRealtimeStrideDefaultsIfUnmodified(): AnomalyConfig {
        if (!hasDefaultRealtimeStrideSettings()) return this
        return copy(
            strideMode = AnomalyStrideMode.Adaptive,
            frameStride = COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES,
            adaptiveMinStrideFrames = COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES,
            adaptiveMaxStrideSeconds = DEFAULT_ADAPTIVE_MAX_STRIDE_SECONDS,
        )
    }

    private fun hasDefaultRealtimeStrideSettings(): Boolean {
        return strideMode == AnomalyStrideMode.Fixed &&
            frameStride == DEFAULT_FRAME_STRIDE &&
            adaptiveMinStrideFrames == DEFAULT_ADAPTIVE_MIN_STRIDE_FRAMES &&
            abs(adaptiveMaxStrideSeconds - DEFAULT_ADAPTIVE_MAX_STRIDE_SECONDS) < 0.001f
    }

    fun toggledAlgorithm(algorithm: AnomalyAlgorithm): AnomalyConfig {
        val updated = nonAppearanceAlgorithms.toMutableSet()
        if (!updated.add(algorithm)) {
            updated.remove(algorithm)
        }
        return copy(algorithms = updated)
    }

    fun toNativeConfig(
        enabledOverride: Boolean? = null,
        detectedAppearanceMode: AppearanceAnomalyMode? = null,
        sourceFps: Float? = null,
    ): NativeAnomalyConfig {
        val resolvedAppearanceMode = resolvedAppearanceMode(detectedAppearanceMode)
        val mask = resolvedAlgorithms(resolvedAppearanceMode).fold(0) { acc, algo -> acc or algo.nativeMask }
        val sensitivityClamped = sensitivity.coerceIn(0f, 1f)
        val motionSensitivityClamped = motionEvidenceSensitivity.coerceIn(0f, 1f)
        // Logarithmic curve: 0% → ~15σ (essentially silent), 60% → ~2.8σ (default), 100% → 1.0σ.
        val scoreThreshold = 15.0.pow(1.0 - sensitivityClamped.toDouble()).toFloat().coerceIn(1.0f, 15.0f)
        val motionEvidenceScale =
            (0.25f + (1.75f * motionSensitivityClamped * motionSensitivityClamped)).coerceIn(0.25f, 2.0f)
        val areaScale = 0.10f + (4.90f * sensitivityClamped * sensitivityClamped)
        val effectiveMinAreaFraction = (minAreaFraction * areaScale).coerceIn(0.00005f, 0.03f)
        val nativeColorFrontendMode = when {
            resolvedAppearanceMode == AppearanceAnomalyMode.Color &&
                colorFrontendMode == ColorFrontendMode.Legacy -> ColorFrontendMode.FreshRgba.nativeValue
            else -> colorFrontendMode.nativeValue
        }
        val colorRealtimeStrideDefault =
            resolvedAppearanceMode == AppearanceAnomalyMode.Color && hasDefaultRealtimeStrideSettings()
        val fixedFrameStride = frameStride.coerceIn(1, 10)
        val nativeStrideMode = if (colorRealtimeStrideDefault) {
            AnomalyStrideMode.Adaptive
        } else {
            strideMode
        }
        val adaptiveHardCap = 33
        val adaptiveMinFrames = if (colorRealtimeStrideDefault) {
            COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES
        } else {
            adaptiveMinStrideFrames
        }.coerceIn(2, adaptiveHardCap)
        val adaptiveMaxSeconds = adaptiveMaxStrideSeconds.coerceIn(0.1f, 10.0f)
        val derivedAdaptiveMaxFrames = sourceFps
            ?.takeIf { it.isFinite() && it > 0.0f }
            ?.let { (it * adaptiveMaxSeconds).roundToInt() }
            ?: adaptiveHardCap
        val adaptiveMaxFrames = derivedAdaptiveMaxFrames.coerceIn(adaptiveMinFrames, adaptiveHardCap)
        return NativeAnomalyConfig(
            enabled = enabledOverride ?: enabled,
            showHotOverlay = showHotOverlay,
            showCandidateBlobs = showCandidateBlobs,
            troubleshootingDebug = troubleshootingDebug,
            algorithmMask = mask,
            registrationMode = registrationMode.nativeValue,
            movementEstimatorMode = movementEstimatorMode.nativeValue,
            strideMode = nativeStrideMode.nativeValue,
            frameStride = if (colorRealtimeStrideDefault) {
                fixedFrameStride.coerceAtLeast(COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES)
            } else {
                fixedFrameStride
            },
            adaptiveMinStrideFrames = adaptiveMinFrames,
            adaptiveMaxStrideFrames = adaptiveMaxFrames,
            adaptiveMaxStrideSeconds = adaptiveMaxSeconds,
            pixelStep = pixelStep.coerceIn(0, 8),
            scoreThreshold = scoreThreshold,
            motionEvidenceScale = motionEvidenceScale,
            minAreaFraction = effectiveMinAreaFraction,
            thermalPolarity = thermalPolarity.nativeValue,
            scanZone = scanZone.coerceIn(0.5f, 1.0f),
            minHits = minHits.coerceIn(1, 10),
            thermalMinDelta = thermalMinDelta.coerceIn(1.0f, 64.0f),
            smallTargetScreenFraction = smallTargetScreenFraction.coerceIn(0.0015f, 0.03f),
            colorFrontendMode = nativeColorFrontendMode,
        )
    }
}

data class AnomalyDetection(
    val algorithm: AnomalyAlgorithm,
    val score: Float,
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float,
    val sourceTimestampUs: Long? = null,
    val observedAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        fun sanitize(
            algorithm: AnomalyAlgorithm,
            score: Float,
            leftNorm: Float,
            topNorm: Float,
            rightNorm: Float,
            bottomNorm: Float,
            sourceTimestampUs: Long,
            observedAtMs: Long = System.currentTimeMillis(),
        ): AnomalyDetection? {
            if (!score.isFinite() || score < 0f) return null
            val l = leftNorm.coerceIn(0f, 1f)
            val t = topNorm.coerceIn(0f, 1f)
            val r = rightNorm.coerceIn(0f, 1f)
            val b = bottomNorm.coerceIn(0f, 1f)
            if (r <= l || b <= t) return null
            return AnomalyDetection(
                algorithm = algorithm,
                score = score,
                leftNorm = l,
                topNorm = t,
                rightNorm = r,
                bottomNorm = b,
                sourceTimestampUs = sourceTimestampUs.takeIf { it > 0L },
                observedAtMs = observedAtMs,
            )
        }
    }
}
