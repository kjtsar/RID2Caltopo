/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import android.content.Context

/** Operator preference for Android Wi-Fi Beacon and Wi-Fi NAN Remote ID discovery. */
object WifiRidScanPrefs {
    private const val PREFS = "wifi_rid_scanning"
    private const val ENABLED = "enabled"

    /** Existing installations retain the historical behavior: Wi-Fi RID scanning is enabled. */
    internal fun resolveEnabled(hasStoredValue: Boolean, storedValue: Boolean): Boolean =
        if (hasStoredValue) storedValue else true

    @JvmStatic
    fun isEnabled(context: Context?): Boolean {
        val prefs = context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?: return true
        return resolveEnabled(prefs.contains(ENABLED), prefs.getBoolean(ENABLED, true))
    }

    @JvmStatic
    fun setEnabled(context: Context?, enabled: Boolean) {
        context?.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(ENABLED, enabled)
            ?.apply()
    }
}
