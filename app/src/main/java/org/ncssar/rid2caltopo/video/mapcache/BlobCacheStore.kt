package org.ncssar.rid2caltopo.video.mapcache

internal interface BlobCacheStore {
    fun defaultExpiry(nowMs: Long = System.currentTimeMillis()): Long
    fun put(cacheKey: String, bytes: ByteArray, expiresAtMs: Long)
    fun get(cacheKey: String, countHitMiss: Boolean = true): CachedBlob?
    fun getExpiration(cacheKey: String): Long?
    fun exists(cacheKey: String): Boolean
    fun remove(cacheKey: String): Boolean
    fun clear()
    fun snapshot(): CacheStatsSnapshot
    fun markStaleServed()
    fun prewarm() {}
    fun runMaintenance(maxEntryAgeCutoffMs: Long, trimToBytes: Long): CacheMaintenanceResult =
        CacheMaintenanceResult()
}

internal data class CacheMaintenanceResult(
    val agedOutEntries: Int = 0,
    val trimEvictedEntries: Int = 0,
    val bytesFreed: Long = 0L,
    val bytesRemaining: Long = 0L
)
