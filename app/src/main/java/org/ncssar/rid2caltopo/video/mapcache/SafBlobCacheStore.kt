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
import java.util.Locale
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
    private var namespaceFileIndex: MutableMap<String, DocumentFile>? = null

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
            MapCacheDebug.log("saf put-failed ns=$namespace key=$cacheKey uri=${target.uri}")
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
            namespaceFileIndex?.set(fileName, target)
        }
        MapCacheDebug.log("saf put ns=$namespace key=$cacheKey bytes=${bytes.size} uri=${target.uri}")
        evictToCap()
    }

    override fun get(cacheKey: String, countHitMiss: Boolean): CachedBlob? {
        val row = synchronized(dbLock) {
            queryRow(cacheKey) ?: recoverRowFromFileLocked(cacheKey)
        }
        if (row == null) {
            if (countHitMiss) missCount.incrementAndGet()
            if (countHitMiss) {
                MapCacheDebug.log("saf miss ns=$namespace key=$cacheKey")
            }
            return null
        }

        val bytes = readBytes(Uri.parse(row.fileUri))
        if (bytes == null) {
            synchronized(dbLock) {
                deleteRow(cacheKey)
            }
            if (countHitMiss) missCount.incrementAndGet()
            if (countHitMiss) {
                MapCacheDebug.log("saf miss(ns-row-blob-missing) ns=$namespace key=$cacheKey uri=${row.fileUri}")
            }
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
        if (countHitMiss) {
            MapCacheDebug.log(
                "saf hit ns=$namespace key=$cacheKey bytes=${bytes.size} stale=${row.expiresAt < now} uri=${row.fileUri}"
            )
        }
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
            try {
                DocumentFile.fromSingleUri(appContext, Uri.parse(row.fileUri))?.delete()
            } catch (e: Exception) {
                MapCacheDebug.log("saf remove delete-failed ns=$namespace key=$cacheKey uri=${row.fileUri} err=${e.javaClass.simpleName}")
            }
        }
        synchronized(dbLock) {
            namespaceFileIndex?.remove(keyToFileName(cacheKey))
        }
        return deletedRows > 0
    }

    override fun clear() {
        val nsDir = getNamespaceDir()
        synchronized(dbLock) {
            db.delete("saf_entries", "namespace = ?", arrayOf(namespace))
            namespaceFileIndex?.clear()
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

    override fun prewarm() {
        synchronized(dbLock) {
            val nsDir = getNamespaceDir()
            if (nsDir == null) {
                MapCacheDebug.log("saf prewarm ns=$namespace skipped(no namespace dir)")
                return
            }
            ensureNamespaceFileIndexLocked(nsDir)
            MapCacheDebug.log("saf prewarm ns=$namespace ready files=${namespaceFileIndex?.size ?: 0}")
        }
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
                try {
                    DocumentFile.fromSingleUri(appContext, Uri.parse(uri))?.delete()
                } catch (e: Exception) {
                    MapCacheDebug.log("saf evict delete-failed ns=$namespace key=$key uri=$uri err=${e.javaClass.simpleName}")
                }
                val rows = synchronized(dbLock) {
                    namespaceFileIndex?.remove(keyToFileName(key))
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

    private fun recoverRowFromFileLocked(cacheKey: String): Row? {
        val nsDir = getNamespaceDir() ?: return null
        val fileName = keyToFileName(cacheKey)
        val existing = ensureNamespaceFileIndexLocked(nsDir)[fileName] ?: return null
        if (!existing.isFile) return null
        val now = System.currentTimeMillis()
        val expiresAt = defaultExpiry(now)
        val size = existing.length().coerceAtLeast(0L)
        val uriString = existing.uri.toString()
        val cv = ContentValues().apply {
            put("namespace", namespace)
            put("cache_key", cacheKey)
            put("file_uri", uriString)
            put("file_name", fileName)
            put("size_bytes", size)
            put("created_at", now)
            put("accessed_at", now)
            put("expires_at", expiresAt)
        }
        db.replaceOrThrow("saf_entries", null, cv)
        MapCacheDebug.log(
            "saf reindex ns=$namespace key=$cacheKey file=$fileName size=$size uri=$uriString"
        )
        return Row(
            fileUri = uriString,
            fileName = fileName,
            expiresAt = expiresAt
        )
    }

    private fun ensureNamespaceFileIndexLocked(nsDir: DocumentFile): MutableMap<String, DocumentFile> {
        namespaceFileIndex?.let { return it }
        val startNs = System.nanoTime()
        val index = HashMap<String, DocumentFile>()
        try {
            nsDir.listFiles().forEach { file ->
                val name = file.name
                if (name != null && file.isFile) {
                    index[name] = file
                }
            }
        } catch (e: Exception) {
            MapCacheDebug.log("saf index-build list-failed ns=$namespace err=${e.javaClass.simpleName}")
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        MapCacheDebug.log(
            "saf index-build ns=$namespace files=${index.size} elapsedMs=${"%.1f".format(Locale.US, elapsedMs)}"
        )
        namespaceFileIndex = index
        return index
    }

    private fun getNamespaceDir(): DocumentFile? {
        return try {
            rootDir.findFile(namespace)
        } catch (e: Exception) {
            MapCacheDebug.log("saf getNamespaceDir failed ns=$namespace err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun getOrCreateNamespaceDir(): DocumentFile? {
        val existing = getNamespaceDir()
        if (existing != null && existing.isDirectory) return existing
        return try {
            rootDir.createDirectory(namespace)
        } catch (e: Exception) {
            MapCacheDebug.log("saf createNamespaceDir failed ns=$namespace err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun ensureWritableFile(nsDir: DocumentFile, fileName: String): DocumentFile? {
        val existing = try {
            nsDir.findFile(fileName)
        } catch (e: Exception) {
            MapCacheDebug.log("saf findFile failed ns=$namespace file=$fileName err=${e.javaClass.simpleName}")
            null
        }
        if (existing != null) {
            if (!existing.isFile) {
                existing.delete()
            } else {
                return existing
            }
        }
        return try {
            nsDir.createFile("application/octet-stream", fileName)
        } catch (e: Exception) {
            MapCacheDebug.log("saf createFile failed ns=$namespace file=$fileName err=${e.javaClass.simpleName}")
            null
        }
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray): Boolean {
        return try {
            appContext.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return false
            true
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
