package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.os.StatFs

internal object MapCachePolicy {
    const val ICON_CACHE_VERSION = 1
    const val TILE_CACHE_VERSION = 1

    const val ICON_CACHE_DB = "icon_cache_v1.db"
    const val TILE_CACHE_DB = "tile_cache_v1.db"

    const val ICON_CACHE_MAX_BYTES: Long = 100L * 1024L * 1024L
    const val TILE_CACHE_MIN_BYTES: Long = 1536L * 1024L * 1024L
    private const val TILE_CACHE_MAX_BYTES_HARD_LIMIT: Long = 64L * 1024L * 1024L * 1024L
    private const val TILE_CACHE_SAF_DEFAULT_BYTES: Long = 8L * 1024L * 1024L * 1024L

    const val ICON_TTL_MS: Long = 180L * 24L * 60L * 60L * 1000L
    const val TILE_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

    fun tileCacheMaxBytes(context: Context): Long {
        return try {
            when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
                is MapCacheRoot.FileBacked -> {
                    val stat = StatFs(root.dir.absolutePath)
                    // Cap cache around 30% of currently available space, with sane bounds.
                    val dynamic = (stat.availableBytes * 30L) / 100L
                    dynamic.coerceIn(TILE_CACHE_MIN_BYTES, TILE_CACHE_MAX_BYTES_HARD_LIMIT)
                }
                is MapCacheRoot.SafBacked -> TILE_CACHE_SAF_DEFAULT_BYTES
            }
        } catch (_: Exception) {
            TILE_CACHE_MIN_BYTES
        }
    }

    fun tileCacheTrimBytes(context: Context): Long {
        return (tileCacheMaxBytes(context) * 9L) / 10L
    }
}
