package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Resolves the operator-assigned Android device name shown by Settings > About phone. */
object AndroidDeviceIdentity {
    private const val PREFS = "device_identity"
    private const val MANAGED_NAME = "managed_display_name"

    @JvmStatic
    fun installationId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return UUID.nameUUIDFromBytes(
            androidId.toByteArray(StandardCharsets.UTF_8)
        ).toString().lowercase()
    }

    @JvmStatic
    fun displayName(context: Context): String {
        val managed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MANAGED_NAME, "")
            .orEmpty()
            .trim()
        if (CaltopoClient.GetHomeTrackerApiKey().isNotBlank() && managed.isNotBlank()) {
            return managed
        }
        return localDisplayName(context)
    }

    @JvmStatic
    fun localDisplayName(context: Context): String = selectDisplayName(
        runCatching {
            Settings.Global.getString(
                context.contentResolver,
                Settings.Global.DEVICE_NAME
            )
        }.getOrNull(),
        runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        }.getOrNull(),
        modelName()
    )

    @JvmStatic
    @JvmOverloads
    fun modelName(
        manufacturer: String? = Build.MANUFACTURER,
        model: String? = Build.MODEL,
    ): String {
        val cleanManufacturer = manufacturer.orEmpty().trim()
        val cleanModel = model.orEmpty().trim()
        if (cleanModel.isEmpty()) return cleanManufacturer.ifEmpty { "Android device" }
        if (cleanManufacturer.isEmpty() || cleanModel.startsWith(cleanManufacturer, ignoreCase = true)) {
            return cleanModel
        }
        return "${cleanManufacturer.replaceFirstChar { it.uppercase() }} $cleanModel"
    }

    @JvmStatic
    fun applyManagedDisplayName(context: Context, value: String): String {
        val clean = value.trim()
        if (clean.isNotEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(MANAGED_NAME, clean)
                .apply()
        }
        return clean
    }

    @JvmStatic
    fun selectDisplayName(vararg candidates: String?): String =
        candidates.firstNotNullOfOrNull { candidate ->
            candidate
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty() &&
                        !it.equals("null", ignoreCase = true) &&
                        !it.equals("<unknown>", ignoreCase = true)
                }
        } ?: "Android device"
}
