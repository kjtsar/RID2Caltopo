package org.ncssar.rid2caltopo.video.anomaly

import android.content.Context

object AnomalyPrefs {
    private const val PREFS_NAME = "anomaly_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALGORITHMS = "algorithms"
    private const val KEY_FRAME_STRIDE = "frame_stride"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_MIN_AREA_FRACTION = "min_area_fraction"
    private const val KEY_THERMAL_POLARITY = "thermal_polarity"
    private const val KEY_SCAN_ZONE = "scan_zone"
    private const val KEY_MIN_HITS = "min_hits"

    @JvmStatic
    fun load(context: Context): AnomalyConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = AnomalyConfig()
        val algorithms = prefs.getStringSet(KEY_ALGORITHMS, null)
            ?.mapNotNull { name -> runCatching { AnomalyAlgorithm.valueOf(name) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: defaults.algorithms
        val polarity = prefs.getString(KEY_THERMAL_POLARITY, defaults.thermalPolarity.name)
            ?.let { name -> runCatching { ThermalPolarity.valueOf(name) }.getOrNull() }
            ?: defaults.thermalPolarity

        return AnomalyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            algorithms = algorithms,
            frameStride = prefs.getInt(KEY_FRAME_STRIDE, defaults.frameStride).coerceIn(1, 8),
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity).coerceIn(0f, 1f),
            minAreaFraction = prefs.getFloat(KEY_MIN_AREA_FRACTION, defaults.minAreaFraction)
                .coerceIn(0.00005f, 0.03f),
            thermalPolarity = polarity,
            scanZone = prefs.getFloat(KEY_SCAN_ZONE, defaults.scanZone).coerceIn(0.5f, 1.0f),
            minHits = prefs.getInt(KEY_MIN_HITS, defaults.minHits).coerceIn(1, 10),
        )
    }

    @JvmStatic
    fun save(context: Context, config: AnomalyConfig) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putStringSet(KEY_ALGORITHMS, config.algorithms.map { it.name }.toSet())
            .putInt(KEY_FRAME_STRIDE, config.frameStride.coerceIn(1, 8))
            .putFloat(KEY_SENSITIVITY, config.sensitivity.coerceIn(0f, 1f))
            .putFloat(KEY_MIN_AREA_FRACTION, config.minAreaFraction.coerceIn(0.00005f, 0.03f))
            .putString(KEY_THERMAL_POLARITY, config.thermalPolarity.name)
            .putFloat(KEY_SCAN_ZONE, config.scanZone.coerceIn(0.5f, 1.0f))
            .putInt(KEY_MIN_HITS, config.minHits.coerceIn(1, 10))
            .apply()
    }
}
