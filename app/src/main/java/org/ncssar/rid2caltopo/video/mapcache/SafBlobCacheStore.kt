package org.ncssar.rid2caltopo.video.mapcache

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal class SafBlobCacheStore(
    context: Context,
    private val rootDir: DocumentFile,
    private val namespace: String,
    private val maxBytes: Long,
    private val defaultTtlMs: Long
) : BlobCacheStore {
    private val appContext = context.applicationContext
    private val dbLock = Any()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val evictionCount = AtomicLong(0)
    private val staleServedCount = AtomicLong(0)

    private val db: SQLiteDatabase by lazy {
        val dbDir = appContext.noBackupFilesDir.resolve("map_cache")
        if (!dbDir.exists()) dbDir.mkdirs()
        val dbFile = File(dbDir, "saf_cache_index.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS saf_entries (
                    namespace TEXT NOT NULL,
                    cache_key TEXT NOT NULL,
                    file_uri TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    accessed_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL,
                    PRIMARY KEY (namespace, cache_key)
                )
                """.trimIndent()
            )
            execSQL("CREATE INDEX IF NOT EXISTS idx_saf_entries_ns_accessed ON saf_entries(namespace, accessed_at)")
            execSQL("CREATE INDEX IF NOT EXISTS idx_saf_entries_ns_expires ON saf_entries(namespace, expires_at)")
        }
    }

    override fun defaultExpiry(nowMs: Long): Long = nowMs + defaultTtlMs

    override fun put(cacheKey: String, bytes: ByteArray, expiresAtMs: Long) {
        val nsDir = getOrCreateNamespaceDir() ?: return
        val fileName = keyToFileName(cacheKey)
        val target = ensureWritableFile(nsDir, fileName) ?: return
        if (!writeBytes(Uri.parse(target.uri.toString()), bytes)) {
            return
        }

        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("namespace", namespace)
            put("cache_key", cacheKey)
            put("file_uri", target.uri.toString())
            put("file_name", fileName)
            put("size_bytes", bytes.size)
            put("created_at", now)
            put("accessed_at", now)
            put("expires_at", expiresAtMs)
        }
        synchronized(dbLock) {
            db.replaceOrThrow("saf_entries", null, cv)
        }
        evictToCap()
    }

    override fun get(cacheKey: String, countHitMiss: Boolean): CachedBlob? {
        val row = synchronized(dbLock) {
            queryRow(cacheKey)
        }
        if (row == null) {
            if (countHitMiss) missCount.incrementAndGet()
            return null
        }

        val bytes = readBytes(Uri.parse(row.fileUri))
        if (bytes == null) {
            synchronized(dbLock) {
                deleteRow(cacheKey)
            }
            if (countHitMiss) missCount.incrementAndGet()
            return null
        }

        val now = System.currentTimeMillis()
        synchronized(dbLock) {
            db.execSQL(
                "UPDATE saf_entries SET accessed_at = ? WHERE namespace = ? AND cache_key = ?",
                arrayOf<Any>(now, namespace, cacheKey)
            )
        }
        if (countHitMiss) hitCount.incrementAndGet()
        return CachedBlob(bytes = bytes, expiresAt = row.expiresAt, stale = row.expiresAt < now)
    }

    override fun getExpiration(cacheKey: String): Long? {
        synchronized(dbLock) {
            val c = db.query(
                "saf_entries",
                arrayOf("expires_at"),
                "namespace = ? AND cache_key = ?",
                arrayOf(namespace, cacheKey),
                null,
                null,
                null
            )
            c.use {
                if (!it.moveToFirst()) return null
                return it.getLong(0)
            }
        }
    }

    override fun exists(cacheKey: String): Boolean = getExpiration(cacheKey) != null

    override fun remove(cacheKey: String): Boolean {
        val (row, deletedRows) = synchronized(dbLock) {
            val existing = queryRow(cacheKey)
            val deleted = db.delete(
                "saf_entries",
                "namespace = ? AND cache_key = ?",
                arrayOf(namespace, cacheKey)
            )
            existing to deleted
        }
        if (row != null) {
            DocumentFile.fromSingleUri(appContext, Uri.parse(row.fileUri))?.delete()
        }
        return deletedRows > 0
    }

    override fun clear() {
        val nsDir = getNamespaceDir()
        synchronized(dbLock) {
            db.delete("saf_entries", "namespace = ?", arrayOf(namespace))
        }
        nsDir?.listFiles()?.forEach { it.delete() }
    }

    override fun snapshot(): CacheStatsSnapshot {
        synchronized(dbLock) {
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

    private fun evictToCap() {
        while (true) {
            val victims: List<Triple<String, String, Long>> = synchronized(dbLock) {
                val current = bytesUsedLocked()
                if (current <= maxBytes) return
                val cursor: Cursor = db.query(
                    "saf_entries",
                    arrayOf("cache_key", "file_uri", "size_bytes"),
                    "namespace = ?",
                    arrayOf(namespace),
                    null,
                    null,
                    "accessed_at ASC",
                    "64"
                )
                cursor.use {
                    val out = ArrayList<Triple<String, String, Long>>(64)
                    while (it.moveToNext()) {
                        out += Triple(it.getString(0), it.getString(1), it.getLong(2))
                    }
                    out
                }
            }
            if (victims.isEmpty()) return

            for ((key, uri, _) in victims) {
                DocumentFile.fromSingleUri(appContext, Uri.parse(uri))?.delete()
                val rows = synchronized(dbLock) {
                    db.delete(
                        "saf_entries",
                        "namespace = ? AND cache_key = ?",
                        arrayOf(namespace, key)
                    )
                }
                if (rows > 0) {
                    evictionCount.incrementAndGet()
                }
            }
        }
    }

    private fun bytesUsedLocked(): Long {
        val cursor = db.rawQuery(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM saf_entries WHERE namespace = ?",
            arrayOf(namespace)
        )
        cursor.use {
            if (!it.moveToFirst()) return 0L
            return it.getLong(0)
        }
    }

    private fun queryRow(cacheKey: String): Row? {
        val c = db.query(
            "saf_entries",
            arrayOf("file_uri", "file_name", "expires_at"),
            "namespace = ? AND cache_key = ?",
            arrayOf(namespace, cacheKey),
            null,
            null,
            null
        )
        c.use {
            if (!it.moveToFirst()) return null
            return Row(
                fileUri = it.getString(0),
                fileName = it.getString(1),
                expiresAt = it.getLong(2)
            )
        }
    }

    private fun deleteRow(cacheKey: String) {
        db.delete("saf_entries", "namespace = ? AND cache_key = ?", arrayOf(namespace, cacheKey))
    }

    private fun getNamespaceDir(): DocumentFile? = rootDir.findFile(namespace)

    private fun getOrCreateNamespaceDir(): DocumentFile? {
        val existing = getNamespaceDir()
        if (existing != null && existing.isDirectory) return existing
        return rootDir.createDirectory(namespace)
    }

    private fun ensureWritableFile(nsDir: DocumentFile, fileName: String): DocumentFile? {
        val existing = nsDir.findFile(fileName)
        if (existing != null) {
            if (!existing.isFile) {
                existing.delete()
            } else {
                return existing
            }
        }
        return nsDir.createFile("application/octet-stream", fileName)
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray): Boolean {
        return try {
            appContext.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return false
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8 * 1024)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun keyToFileName(cacheKey: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
        return "$hex.bin"
    }

    private data class Row(
        val fileUri: String,
        val fileName: String,
        val expiresAt: Long
    )
}
