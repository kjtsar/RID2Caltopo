package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context

internal object BadTilePolicy {
    private const val PREFS = "map_cache_bad_tiles"
    private const val KEY_HASHES = "blocked_hashes_v1"
    private const val KEY_AUTO_REMOVE = "auto_remove_bad_tiles"

    fun blockedHashes(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        return prefs.getStringSet(KEY_HASHES, emptySet())?.toSet() ?: emptySet()
    }

    fun blockedHashCount(context: Context): Int = blockedHashes(context).size

    fun blockedHashesSorted(context: Context): List<String> =
        blockedHashes(context).filter { it.isNotBlank() }.sorted()

    fun isHashBlocked(context: Context, hash: String): Boolean {
        if (hash.isBlank()) return false
        return blockedHashes(context).contains(hash)
    }

    fun addBlockedHash(context: Context, hash: String) {
        if (hash.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        val updated = blockedHashes(context).toMutableSet().apply { add(hash) }
        prefs.edit().putStringSet(KEY_HASHES, updated).apply()
    }

    fun clearBlockedHashes(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        prefs.edit().putStringSet(KEY_HASHES, emptySet()).apply()
    }

    fun isAutoRemoveEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        return prefs.getBoolean(KEY_AUTO_REMOVE, true)
    }

    fun setAutoRemoveEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, 0)
        prefs.edit().putBoolean(KEY_AUTO_REMOVE, enabled).apply()
    }
}
