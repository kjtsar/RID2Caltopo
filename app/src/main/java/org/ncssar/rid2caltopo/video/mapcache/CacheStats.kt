package org.ncssar.rid2caltopo.video.mapcache

data class CacheStatsSnapshot(
    val hits: Long,
    val misses: Long,
    val bytesUsed: Long,
    val evictions: Long,
    val staleServed: Long
)
