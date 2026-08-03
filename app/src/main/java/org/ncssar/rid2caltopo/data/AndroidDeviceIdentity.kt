package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Resolves the operator-assigned Android device name shown by Settings > About phone. */
object AndroidDeviceIdentity {
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
