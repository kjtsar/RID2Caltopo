package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context

internal object BlobCacheStoreFactory {
    fun create(
        context: Context,
        namespace: String,
        dbName: String,
        maxBytes: Long,
        defaultTtlMs: Long,
        forceFileBacked: Boolean = false
    ): BlobCacheStore {
        if (forceFileBacked) {
            val appCacheRoot = context.applicationContext.noBackupFilesDir.resolve("map_cache")
            if (!appCacheRoot.exists()) {
                appCacheRoot.mkdirs()
            }
            return BlobSqlDiskCache(
                cacheDir = appCacheRoot,
                dbName = dbName,
                maxBytes = maxBytes,
                defaultTtlMs = defaultTtlMs
            )
        }

        return when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> BlobSqlDiskCache(
                cacheDir = root.dir,
                dbName = dbName,
                maxBytes = maxBytes,
                defaultTtlMs = defaultTtlMs
            )

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
