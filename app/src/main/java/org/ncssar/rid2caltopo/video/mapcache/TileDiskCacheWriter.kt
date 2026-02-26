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
import java.security.MessageDigest

class TileDiskCacheWriter(context: Context) : IFilesystemCache {
    private val appContext = context.applicationContext
    private companion object {
        private const val OSM_STANDARD_SOURCE = "OSM-Standard"
        private val LEGACY_OSM_SOURCE_NAMES = listOf("Mapnik", "MAPNIK")
        private const val OSM_UNIFORM_WINDOW_MS = 120_000L
        private const val OSM_UNIFORM_TILE_THRESHOLD = 8
        private val osmHashTracker = LinkedHashMap<String, OsmHashWindow>()

        private data class OsmHashWindow(
            var firstSeenMs: Long,
            val tileIds: MutableSet<String>
        )
    }

    private val diskCache: BlobCacheStore = BlobCacheStoreFactory.create(
        context = context.applicationContext,
        namespace = "tile_cache_v${MapCachePolicy.TILE_CACHE_VERSION}",
        dbName = MapCachePolicy.TILE_CACHE_DB,
        maxBytes = MapCachePolicy.tileCacheMaxBytes(context.applicationContext),
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
            val hash = sha256Hex(bytes)
            if (BadTilePolicy.isHashBlocked(appContext, hash)) {
                MapCacheDebug.log(
                    "tile put skipped source=${pTileSourceInfo.name()} key=$key reason=blocked-hash hash=${hash.take(12)}"
                )
                return false
            }
            if (pTileSourceInfo.name() == OSM_STANDARD_SOURCE && isLikelyUniformOsmBlock(bytes, pMapTileIndex)) {
                MapCacheDebug.log(
                    "tile put skipped source=${pTileSourceInfo.name()} key=$key reason=suspect-uniform-block"
                )
                return false
            }
            val expiresAt = pExpirationTime?.takeIf { it > System.currentTimeMillis() } ?: diskCache.defaultExpiry()
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
        return tileKeysForLookup(pTileSourceInfo, pMapTileIndex).any { diskCache.exists(it) }
    }

    override fun onDetach() {
        // no-op
    }

    override fun remove(tileSource: ITileSource, pMapTileIndex: Long): Boolean {
        var removed = false
        for (key in tileKeysForLookup(tileSource, pMapTileIndex)) {
            removed = diskCache.remove(key) || removed
        }
        return removed
    }

    override fun getExpirationTimestamp(pTileSource: ITileSource, pMapTileIndex: Long): Long? {
        for (key in tileKeysForLookup(pTileSource, pMapTileIndex)) {
            val expiration = diskCache.getExpiration(key)
            if (expiration != null) return expiration
        }
        return null
    }

    override fun loadTile(pTileSource: ITileSource, pMapTileIndex: Long): Drawable? {
        val keys = tileKeysForLookup(pTileSource, pMapTileIndex)
        var usedKey: String? = null
        var cached: CachedBlob? = null
        for (idx in keys.indices) {
            val candidate = keys[idx]
            val hit = diskCache.get(candidate, countHitMiss = idx == 0)
            if (hit != null) {
                cached = hit
                usedKey = candidate
                break
            }
        }
        if (cached == null || usedKey == null) {
            MapCacheDebug.log("tile miss source=${pTileSource.name()} key=${keys.first()}")
            return null
        }
        val hash = sha256Hex(cached.bytes)
        if (BadTilePolicy.isHashBlocked(appContext, hash)) {
            if (BadTilePolicy.isAutoRemoveEnabled(appContext)) {
                diskCache.remove(usedKey)
            }
            MapCacheDebug.log(
                "tile blocked-hit source=${pTileSource.name()} key=$usedKey hash=${hash.take(12)} autoRemove=${BadTilePolicy.isAutoRemoveEnabled(appContext)}"
            )
            return null
        }
        val drawable = pTileSource.getDrawable(ByteArrayInputStream(cached.bytes)) ?: return null
        if (usedKey != keys.first()) {
            // Opportunistically migrate legacy source-name keys to current key format.
            diskCache.put(keys.first(), cached.bytes, diskCache.defaultExpiry())
            MapCacheDebug.log("tile migrate source=${pTileSource.name()} from=$usedKey to=${keys.first()}")
        }
        if (cached.stale) {
            diskCache.markStaleServed()
            ExpirableBitmapDrawable.setState(drawable, ExpirableBitmapDrawable.EXPIRED)
            MapCacheDebug.log("tile stale-hit source=${pTileSource.name()} key=$usedKey bytes=${cached.bytes.size}")
        } else {
            MapCacheDebug.log("tile hit source=${pTileSource.name()} key=$usedKey bytes=${cached.bytes.size}")
        }
        return drawable
    }

    fun clear() {
        diskCache.clear()
    }

    fun tileHash(tileSource: ITileSource, mapTileIndex: Long): String? {
        for (key in tileKeysForLookup(tileSource, mapTileIndex)) {
            val cached = diskCache.get(key, countHitMiss = false) ?: continue
            return sha256Hex(cached.bytes)
        }
        return null
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

    private fun tileKeyForName(sourceName: String, mapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(mapTileIndex)
        val x = MapTileIndex.getX(mapTileIndex)
        val y = MapTileIndex.getY(mapTileIndex)
        return "v${MapCachePolicy.TILE_CACHE_VERSION}|$sourceName|$z|$x|$y"
    }

    private fun tileKeysForLookup(tileSource: ITileSource, mapTileIndex: Long): List<String> {
        val primary = tileKey(tileSource, mapTileIndex)
        if (tileSource.name() != OSM_STANDARD_SOURCE) return listOf(primary)
        val keys = ArrayList<String>(1 + LEGACY_OSM_SOURCE_NAMES.size)
        keys.add(primary)
        for (legacyName in LEGACY_OSM_SOURCE_NAMES) {
            keys.add(tileKeyForName(legacyName, mapTileIndex))
        }
        return keys
    }

    private fun isLikelyUniformOsmBlock(bytes: ByteArray, mapTileIndex: Long): Boolean {
        if (bytes.isEmpty()) return false
        val now = System.currentTimeMillis()
        val hash = sha256Hex(bytes)
        val tileId = "${MapTileIndex.getZoom(mapTileIndex)}/${MapTileIndex.getX(mapTileIndex)}/${MapTileIndex.getY(mapTileIndex)}"
        synchronized(osmHashTracker) {
            val it = osmHashTracker.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if ((now - entry.value.firstSeenMs) > OSM_UNIFORM_WINDOW_MS) {
                    it.remove()
                }
            }
            val window = osmHashTracker.getOrPut(hash) { OsmHashWindow(firstSeenMs = now, tileIds = LinkedHashSet()) }
            if ((now - window.firstSeenMs) > OSM_UNIFORM_WINDOW_MS) {
                window.firstSeenMs = now
                window.tileIds.clear()
            }
            window.tileIds.add(tileId)
            val count = window.tileIds.size
            if (count >= OSM_UNIFORM_TILE_THRESHOLD) {
                MapCacheDebug.log("tile uniform-signature hash=${hash.take(12)} count=$count windowMs=$OSM_UNIFORM_WINDOW_MS")
                return true
            }
        }
        return false
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
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
