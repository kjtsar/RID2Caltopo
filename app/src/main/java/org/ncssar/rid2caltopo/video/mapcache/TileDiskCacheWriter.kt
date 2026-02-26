package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.ExpirableBitmapDrawable
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class TileDiskCacheWriter(context: Context) : IFilesystemCache {
    private val diskCache: BlobCacheStore = BlobCacheStoreFactory.create(
        context = context.applicationContext,
        namespace = "tile_cache_v${MapCachePolicy.TILE_CACHE_VERSION}",
        dbName = MapCachePolicy.TILE_CACHE_DB,
        maxBytes = MapCachePolicy.TILE_CACHE_MAX_BYTES,
        defaultTtlMs = MapCachePolicy.TILE_TTL_MS
    )

    override fun saveFile(
        pTileSourceInfo: ITileSource,
        pMapTileIndex: Long,
        pStream: InputStream,
        pExpirationTime: Long?
    ): Boolean {
        return try {
            val bytes = pStream.readAllBytesCompat()
            val key = tileKey(pTileSourceInfo, pMapTileIndex)
            val expiresAt = diskCache.defaultExpiry()
            diskCache.put(key, bytes, expiresAt)
            MapCacheDebug.log(
                "tile put source=${pTileSourceInfo.name()} key=$key bytes=${bytes.size} expiresAt=$expiresAt"
            )
            true
        } catch (e: Exception) {
            MapCacheDebug.log(
                "tile put failed source=${pTileSourceInfo.name()} idx=$pMapTileIndex err=${e.javaClass.simpleName}:${e.message}"
            )
            false
        }
    }

    override fun exists(pTileSourceInfo: ITileSource, pMapTileIndex: Long): Boolean {
        return diskCache.exists(tileKey(pTileSourceInfo, pMapTileIndex))
    }

    override fun onDetach() {
        // no-op
    }

    override fun remove(tileSource: ITileSource, pMapTileIndex: Long): Boolean {
        return diskCache.remove(tileKey(tileSource, pMapTileIndex))
    }

    override fun getExpirationTimestamp(pTileSource: ITileSource, pMapTileIndex: Long): Long? {
        return diskCache.getExpiration(tileKey(pTileSource, pMapTileIndex))
    }

    override fun loadTile(pTileSource: ITileSource, pMapTileIndex: Long): Drawable? {
        val key = tileKey(pTileSource, pMapTileIndex)
        val cached = diskCache.get(key, countHitMiss = true) ?: run {
            MapCacheDebug.log("tile miss source=${pTileSource.name()} key=$key")
            return null
        }
        val drawable = pTileSource.getDrawable(ByteArrayInputStream(cached.bytes)) ?: return null
        if (cached.stale) {
            diskCache.markStaleServed()
            ExpirableBitmapDrawable.setState(drawable, ExpirableBitmapDrawable.EXPIRED)
            MapCacheDebug.log("tile stale-hit source=${pTileSource.name()} key=$key bytes=${cached.bytes.size}")
        } else {
            MapCacheDebug.log("tile hit source=${pTileSource.name()} key=$key bytes=${cached.bytes.size}")
        }
        return drawable
    }

    fun clear() {
        diskCache.clear()
    }

    fun prewarm() {
        diskCache.prewarm()
    }

    fun statsSnapshot(): CacheStatsSnapshot = diskCache.snapshot()

    private fun tileKey(tileSource: ITileSource, mapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(mapTileIndex)
        val x = MapTileIndex.getX(mapTileIndex)
        val y = MapTileIndex.getY(mapTileIndex)
        return "v${MapCachePolicy.TILE_CACHE_VERSION}|${tileSource.name()}|$z|$x|$y"
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
