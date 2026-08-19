package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Resolves the operator-assigned Android device name shown by Settings > About phone. */
object AndroidDeviceIdentity {
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
    fun displayName(context: Context): String = selectDisplayName(
        runCatching {
            Settings.Global.getString(
                context.contentResolver,
                Settings.Global.DEVICE_NAME
            )
        }.getOrNull(),
        runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        }.getOrNull(),
        "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    )

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
