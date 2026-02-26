package org.ncssar.rid2caltopo.video.mapcache

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal data class CachedBlob(
    val bytes: ByteArray,
    val expiresAt: Long,
    val stale: Boolean
)

internal class BlobSqlDiskCache(
    private val cacheDir: File,
    private val dbName: String,
    private val maxBytes: Long,
    private val defaultTtlMs: Long
) : BlobCacheStore {
    private val lock = Any()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val staleServedCount = AtomicLong(0)

    private val db: SQLiteDatabase by lazy {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val dbFile = File(cacheDir, dbName)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS entries (
                    cache_key TEXT PRIMARY KEY,
                    blob_data BLOB NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    accessed_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS idx_entries_accessed_at ON entries(accessed_at)")
            execSQL("CREATE INDEX IF NOT EXISTS idx_entries_expires_at ON entries(expires_at)")
        }
    }

    init {
        // Fail fast so callers can choose a fallback cache root if opening this DB path is invalid.
        db
    }

    override fun defaultExpiry(nowMs: Long): Long = nowMs + defaultTtlMs

    override fun put(cacheKey: String, bytes: ByteArray, expiresAtMs: Long) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("cache_key", cacheKey)
                put("blob_data", bytes)
                put("size_bytes", bytes.size)
                put("created_at", now)
                put("accessed_at", now)
                put("expires_at", expiresAtMs)
            }
            db.replaceOrThrow("entries", null, cv)
            MapCacheDebug.log("sql put db=$dbName key=$cacheKey bytes=${bytes.size} expiresAt=$expiresAtMs dir=${cacheDir.absolutePath}")
            evictToCapLocked()
        }
    }

    override fun get(cacheKey: String, countHitMiss: Boolean): CachedBlob? {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cursor = db.query(
                "entries",
                arrayOf("blob_data", "expires_at"),
                "cache_key = ?",
                arrayOf(cacheKey),
                null,
                null,
                null
            )
            cursor.use {
                if (!it.moveToFirst()) {
                    if (countHitMiss) missCount.incrementAndGet()
                    if (countHitMiss) {
                        MapCacheDebug.log("sql miss db=$dbName key=$cacheKey dir=${cacheDir.absolutePath}")
                    }
                    return null
                }
                val bytes = it.getBlob(0)
                val expiresAt = it.getLong(1)
                val stale = expiresAt < now
                val touch = ContentValues().apply { put("accessed_at", now) }
                db.update("entries", touch, "cache_key = ?", arrayOf(cacheKey))
                if (countHitMiss) hitCount.incrementAndGet()
                if (countHitMiss) {
                    MapCacheDebug.log(
                        "sql hit db=$dbName key=$cacheKey bytes=${bytes.size} stale=$stale dir=${cacheDir.absolutePath}"
                    )
                }
                return CachedBlob(bytes = bytes, expiresAt = expiresAt, stale = stale)
            }
        }
    }

    override fun getExpiration(cacheKey: String): Long? {
        synchronized(lock) {
            val cursor = db.query(
                "entries",
                arrayOf("expires_at"),
                "cache_key = ?",
                arrayOf(cacheKey),
                null,
                null,
                null
            )
            cursor.use {
                if (!it.moveToFirst()) return null
                return it.getLong(0)
            }
        }
    }

    override fun exists(cacheKey: String): Boolean = getExpiration(cacheKey) != null

    override fun remove(cacheKey: String): Boolean {
        synchronized(lock) {
            return db.delete("entries", "cache_key = ?", arrayOf(cacheKey)) > 0
        }
    }

    override fun clear() {
        synchronized(lock) {
            db.delete("entries", null, null)
        }
    }

    override fun snapshot(): CacheStatsSnapshot {
        synchronized(lock) {
            return CacheStatsSnapshot(
                hits = hitCount.get(),
                misses = missCount.get(),
                bytesUsed = bytesUsedLocked(),
                evictions = evictionCount.get(),
                staleServed = staleServedCount.get()
            )
        }
    }

    override fun markStaleServed() {
        staleServedCount.incrementAndGet()
    }

    private fun evictToCapLocked() {
        var current = bytesUsedLocked()
        if (current <= maxBytes) return

        while (current > maxBytes) {
            val cursor: Cursor = db.query(
                "entries",
                arrayOf("cache_key", "size_bytes"),
                null,
                null,
                null,
                null,
                "accessed_at ASC",
                "64"
            )
            val victims = ArrayList<Pair<String, Long>>()
            cursor.use {
                while (it.moveToNext()) {
                    victims += it.getString(0) to it.getLong(1)
                }
            }
            if (victims.isEmpty()) break

            for ((key, size) in victims) {
                val deleted = db.delete("entries", "cache_key = ?", arrayOf(key))
                if (deleted > 0) {
                    current -= size
                    evictionCount.incrementAndGet()
                }
                if (current <= maxBytes) break
            }
        }
    }

    private fun bytesUsedLocked(): Long {
        val cursor = db.rawQuery("SELECT COALESCE(SUM(size_bytes), 0) FROM entries", null)
        cursor.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }
}
