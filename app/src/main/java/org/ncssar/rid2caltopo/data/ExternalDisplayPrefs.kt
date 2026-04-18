package org.ncssar.rid2caltopo.data

import android.content.Context

enum class ExternalDisplayContentMode(
    val storageValue: String,
    val displayLabel: String
) {
    StreamsGrid("streams_grid", "Streams Grid"),
    MapOnly("map_only", "Map Only"),
    Split("split", "Split: Streams + Map"),
    ObserverMode("observer_mode", "Observer Mode");

    companion object {
        fun fromStorage(value: String?): ExternalDisplayContentMode {
            return entries.firstOrNull { it.storageValue == value } ?: StreamsGrid
        }
    }
}

enum class ExternalDisplayMode(
    val storageValue: String,
    val displayLabel: String
) {
    Off("off", "Off"),
    AppManaged("app_managed", "App-managed"),
    OsMirroring("os_mirroring", "Use OS mirroring");

    companion object {
        fun fromStorage(value: String?): ExternalDisplayMode {
            return entries.firstOrNull { it.storageValue == value } ?: OsMirroring
        }
    }
}

enum class ExternalDisplayAlertRouting(
    val storageValue: String,
    val displayLabel: String
) {
    PhoneOnly("phone_only", "Phone only"),
    ExternalOnly("external_only", "External display only"),
    Both("both", "Both");

    companion object {
        fun fromStorage(value: String?): ExternalDisplayAlertRouting {
            return entries.firstOrNull { it.storageValue == value } ?: Both
        }
    }
}

data class ExternalDisplayConfig(
    val mode: ExternalDisplayMode = ExternalDisplayMode.OsMirroring,
    val autoOpenOnConnect: Boolean = true,
    val returnToPhoneOnlyLayoutOnDisconnect: Boolean = true,
    val allowInteraction: Boolean = true,
    val contentMode: ExternalDisplayContentMode = ExternalDisplayContentMode.StreamsGrid,
    val alertRouting: ExternalDisplayAlertRouting = ExternalDisplayAlertRouting.Both
)

object ExternalDisplayPrefs {
    private const val PREFS_NAME = "external_display_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_AUTO_OPEN = "auto_open"
    private const val KEY_RETURN_TO_PHONE_ONLY = "return_to_phone_only"
    private const val KEY_ALLOW_INTERACTION = "allow_interaction"
    private const val KEY_CONTENT_MODE = "content_mode"
    private const val KEY_ALERT_ROUTING = "alert_routing"

    @JvmStatic
    fun load(context: Context): ExternalDisplayConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ExternalDisplayConfig(
            mode = ExternalDisplayMode.fromStorage(
                prefs.getString(KEY_MODE, ExternalDisplayMode.OsMirroring.storageValue)
            ),
            autoOpenOnConnect = prefs.getBoolean(KEY_AUTO_OPEN, true),
            returnToPhoneOnlyLayoutOnDisconnect = prefs.getBoolean(KEY_RETURN_TO_PHONE_ONLY, true),
            allowInteraction = prefs.getBoolean(KEY_ALLOW_INTERACTION, true),
            contentMode = ExternalDisplayContentMode.fromStorage(
                prefs.getString(KEY_CONTENT_MODE, ExternalDisplayContentMode.StreamsGrid.storageValue)
            ),
            alertRouting = ExternalDisplayAlertRouting.fromStorage(
                prefs.getString(KEY_ALERT_ROUTING, ExternalDisplayAlertRouting.Both.storageValue)
            )
        )
    }

    @JvmStatic
    fun save(context: Context, config: ExternalDisplayConfig) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, config.mode.storageValue)
            .putBoolean(KEY_AUTO_OPEN, config.autoOpenOnConnect)
            .putBoolean(KEY_RETURN_TO_PHONE_ONLY, config.returnToPhoneOnlyLayoutOnDisconnect)
            .putBoolean(KEY_ALLOW_INTERACTION, config.allowInteraction)
            .putString(KEY_CONTENT_MODE, config.contentMode.storageValue)
            .putString(KEY_ALERT_ROUTING, config.alertRouting.storageValue)
            .apply()
    }
}
