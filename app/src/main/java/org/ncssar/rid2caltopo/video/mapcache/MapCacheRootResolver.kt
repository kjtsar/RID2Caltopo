package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.net.Uri
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.io.File

internal object MapCacheRootResolver {
    private const val TAG = "SplitMapPane"
    @Volatile
    private var cached: CachedRoot? = null

    private data class CachedRoot(
        val archiveUri: String?,
        val root: MapCacheRoot
    )

    fun resolveRoot(context: Context): MapCacheRoot {
        val appContext = context.applicationContext
        val archiveUri = CaltopoClient.GetArchiveUri()?.toString()
        cached?.let { entry ->
            if (entry.archiveUri == archiveUri) {
                return entry.root
            }
        }

        synchronized(this) {
            cached?.let { entry ->
                if (entry.archiveUri == archiveUri) {
                    return entry.root
                }
            }

            val resolved = resolveRootUncached(appContext)
            cached = CachedRoot(archiveUri = archiveUri, root = resolved)
            return resolved
        }
    }

    fun invalidate() {
        synchronized(this) {
            cached = null
        }
    }

    private fun resolveRootUncached(context: Context): MapCacheRoot {
        val archiveDir = CaltopoClient.GetArchiveDir()
        if (archiveDir != null && archiveDir.isDirectory) {
            val archiveCacheDir = archiveDir.findFile("cache") ?: archiveDir.createDirectory("cache")
            if (archiveCacheDir != null && archiveCacheDir.isDirectory) {
                val uri = archiveCacheDir.uri
                if (uri.scheme.equals("file", ignoreCase = true)) {
                    val root = uri.path?.let { File(it) }
                    if (root != null && (root.exists() || root.mkdirs())) {
                        CTDebug(TAG, "Map cache root using archive file dir: ${root.absolutePath}")
                        return MapCacheRoot.FileBacked(root)
                    }
                }
                CTDebug(TAG, "Map cache root using archive SAF dir: $uri")
                return MapCacheRoot.SafBacked(archiveCacheDir)
            }
        }

        val fallback = context.cacheDir.resolve("map_cache_fallback")
        if (!fallback.exists()) {
            fallback.mkdirs()
        }
        CTDebug(TAG, "Map cache root using app cache fallback: ${fallback.absolutePath}")
        return MapCacheRoot.FileBacked(fallback)
    }
}
