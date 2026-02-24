package org.ncssar.rid2caltopo.video.mapcache

internal object MapCachePolicy {
    const val ICON_CACHE_VERSION = 1
    const val TILE_CACHE_VERSION = 1

    const val ICON_CACHE_DB = "icon_cache_v1.db"
    const val TILE_CACHE_DB = "tile_cache_v1.db"

    const val ICON_CACHE_MAX_BYTES: Long = 100L * 1024L * 1024L
    const val TILE_CACHE_MAX_BYTES: Long = 1536L * 1024L * 1024L

    const val ICON_TTL_MS: Long = 180L * 24L * 60L * 60L * 1000L
    const val TILE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L
}
