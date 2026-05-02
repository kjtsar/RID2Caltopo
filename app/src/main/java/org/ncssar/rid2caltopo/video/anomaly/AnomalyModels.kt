package org.ncssar.rid2caltopo.video.anomaly

import java.util.Locale
import kotlin.math.pow

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
    Thermal(algorithm = AnomalyAlgorithm.ThermalHotspot, label = "Thermal"),
    Color(algorithm = AnomalyAlgorithm.ColorOutlier, label = "Color Outlier");
}

enum class AppearanceAnomalySelection(
    val label: String,
) {
    Auto(label = "Auto"),
    Thermal(label = "Thermal"),
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

data class NativeAnomalyConfig(
    val enabled: Boolean,
    val showHotOverlay: Boolean,
    val algorithmMask: Int,
    val frameStride: Int,
    val pixelStep: Int,
    val scoreThreshold: Float,
    val motionEvidenceScale: Float,
    val minAreaFraction: Float,
    val thermalPolarity: Int,
    val scanZone: Float,
    val minHits: Int,
    val thermalMinDelta: Float,
)

data class AnomalyConfig(
    val enabled: Boolean = false,
    val showHotOverlay: Boolean = false,
    val algorithms: Set<AnomalyAlgorithm> = setOf(AnomalyAlgorithm.ThermalHotspot),
    val appearanceSelection: AppearanceAnomalySelection = AppearanceAnomalySelection.Auto,
    val frameStride: Int = 3,
    val pixelStep: Int = 0,
    val sensitivity: Float = 0.60f,
    val motionEvidenceSensitivity: Float = 0.60f,
    val minAreaFraction: Float = 0.0015f,
    val thermalPolarity: ThermalPolarity = ThermalPolarity.WhiteHot,
    val scanZone: Float = 0.60f,
    val minHits: Int = 2,
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

    fun resolvedAppearanceMode(
        detectedMode: AppearanceAnomalyMode? = null,
    ): AppearanceAnomalyMode = appearanceSelection.resolved(detectedMode ?: AppearanceAnomalyMode.Thermal)

    fun resolvedAlgorithms(
        detectedMode: AppearanceAnomalyMode? = null,
    ): Set<AnomalyAlgorithm> {
        val resolved = nonAppearanceAlgorithms.toMutableSet()
        resolved += resolvedAppearanceMode(detectedMode).algorithm
        return resolved
    }

    fun withAppearanceSelection(selection: AppearanceAnomalySelection): AnomalyConfig {
        return copy(
            appearanceSelection = selection,
            algorithms = nonAppearanceAlgorithms
        )
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
    ): NativeAnomalyConfig {
        val mask = resolvedAlgorithms(detectedAppearanceMode).fold(0) { acc, algo -> acc or algo.nativeMask }
        val sensitivityClamped = sensitivity.coerceIn(0f, 1f)
        val motionSensitivityClamped = motionEvidenceSensitivity.coerceIn(0f, 1f)
        // Logarithmic curve: 0% → ~15σ (essentially silent), 60% → ~2.8σ (default), 100% → 1.0σ.
        val scoreThreshold = 15.0.pow(1.0 - sensitivityClamped.toDouble()).toFloat().coerceIn(1.0f, 15.0f)
        val motionEvidenceScale =
            (0.25f + (1.75f * motionSensitivityClamped * motionSensitivityClamped)).coerceIn(0.25f, 2.0f)
        val areaScale = 0.10f + (4.90f * sensitivityClamped * sensitivityClamped)
        val effectiveMinAreaFraction = (minAreaFraction * areaScale).coerceIn(0.00005f, 0.03f)
        return NativeAnomalyConfig(
            enabled = enabledOverride ?: enabled,
            showHotOverlay = showHotOverlay,
            algorithmMask = mask,
            frameStride = frameStride.coerceIn(1, 8),
            pixelStep = pixelStep.coerceIn(0, 8),
            scoreThreshold = scoreThreshold,
            motionEvidenceScale = motionEvidenceScale,
            minAreaFraction = effectiveMinAreaFraction,
            thermalPolarity = thermalPolarity.nativeValue,
            scanZone = scanZone.coerceIn(0.5f, 1.0f),
            minHits = minHits.coerceIn(1, 10),
            thermalMinDelta = 10.0f,
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
