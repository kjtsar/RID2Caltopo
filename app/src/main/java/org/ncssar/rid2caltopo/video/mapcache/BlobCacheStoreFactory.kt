package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn

internal object BlobCacheStoreFactory {
    fun create(
        context: Context,
        namespace: String,
        dbName: String,
        maxBytes: Long,
        defaultTtlMs: Long,
        forceFileBacked: Boolean = false
    ): BlobCacheStore {
        val internalFallbackRoot = context.applicationContext.noBackupFilesDir.resolve("map_cache")
        if (!internalFallbackRoot.exists()) {
            internalFallbackRoot.mkdirs()
        }
        if (forceFileBacked) {
            return BlobSqlDiskCache(
                cacheDir = internalFallbackRoot,
                dbName = dbName,
                maxBytes = maxBytes,
                defaultTtlMs = defaultTtlMs
            )
        }

        return when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> try {
                BlobSqlDiskCache(
                    cacheDir = root.dir,
                    dbName = dbName,
                    maxBytes = maxBytes,
                    defaultTtlMs = defaultTtlMs
                )
            } catch (t: Throwable) {
                CTWarn(
                    "SplitMapPane",
                    "Map cache SQL open failed for ${root.dir.absolutePath}; using internal fallback.",
                    Exception(t)
                )
                BlobSqlDiskCache(
                    cacheDir = internalFallbackRoot,
                    dbName = dbName,
                    maxBytes = maxBytes,
                    defaultTtlMs = defaultTtlMs
                )
            }

            is MapCacheRoot.SafBacked -> SafBlobCacheStore(
                context = context.applicationContext,
                rootDir = root.dir,
                namespace = namespace,
                maxBytes = maxBytes,
                defaultTtlMs = defaultTtlMs
            )
        }
    }
}
