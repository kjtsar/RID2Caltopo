package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import mil.nga.tiff.compression.DeflateCompression
import mil.nga.tiff.compression.LZWCompression
import mil.nga.tiff.compression.PackbitsCompression
import mil.nga.tiff.compression.Predictor
import mil.nga.tiff.compression.RawCompression
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal class GeoTiffDemSource(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var catalogRefreshedAtMs: Long = 0L
    private var tiles: List<DemTile> = emptyList()
    private val metadataCache = object : LinkedHashMap<String, GeoTiffMetadata>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeoTiffMetadata>?): Boolean {
            return size > 12
        }
    }

    fun sampleElevationMeters(lat: Double, lng: Double): Double? {
        if (!lat.isFinite() || !lng.isFinite()) return null
        val candidates = synchronized(lock) {
            refreshCatalogLocked()
            tiles.filter { tile -> tile.bounds?.contains(lat, lng) != false }
        }
        if (candidates.isEmpty()) return null

        for (tile in candidates) {
            val metadata = getOrLoadMetadata(tile) ?: continue
            val value = trySampleFromTile(tile, metadata, lat, lng)
            if (value != null && value.isFinite()) {
                return value
            }
        }
        return null
    }

    private fun refreshCatalogLocked() {
        val now = System.currentTimeMillis()
        if (now - catalogRefreshedAtMs < 60_000L && tiles.isNotEmpty()) return
        catalogRefreshedAtMs = now

        val archive = CaltopoClient.GetArchiveDir()
        if (archive == null || !archive.isDirectory) {
            tiles = emptyList()
            return
        }

        val cacheDir = archive.findFile("cache")
        val demDir = cacheDir?.findFile("dem")
        val roots = mutableListOf<DocumentFile>()
        if (demDir != null && demDir.isDirectory) roots += demDir
        if (cacheDir != null && cacheDir.isDirectory) roots += cacheDir

        val out = mutableListOf<DemTile>()
        roots.forEach { root ->
            root.listFiles().forEach { doc ->
                if (!doc.isFile) return@forEach
                val name = doc.name ?: return@forEach
                if (!name.lowercase(Locale.US).endsWith(".tif") && !name.lowercase(Locale.US).endsWith(".tiff")) {
                    return@forEach
                }
                out += DemTile(
                    id = doc.uri.toString(),
                    uri = doc.uri,
                    displayName = name,
                    bounds = boundsFromName(name)
                )
            }
        }

        tiles = out
        if (out.isNotEmpty()) {
            CTDebug("SplitMapPane", "DEM GeoTIFF catalog loaded tiles=${out.size}")
        }
    }

    private fun getOrLoadMetadata(tile: DemTile): GeoTiffMetadata? {
        synchronized(lock) {
            metadataCache[tile.id]?.let { return it }
        }

        val loaded = loadMetadata(tile) ?: return null
        synchronized(lock) {
            metadataCache[tile.id] = loaded
        }
        return loaded
    }

    private fun loadMetadata(tile: DemTile): GeoTiffMetadata? {
        return openDataSource(tile.uri)?.use { source ->
            try {
                parseMetadata(source)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun trySampleFromTile(tile: DemTile, metadata: GeoTiffMetadata, lat: Double, lng: Double): Double? {
        if (metadata.scaleX == 0.0 || metadata.scaleY == 0.0) return null
        val colF = metadata.tieI + (lng - metadata.tieX) / metadata.scaleX
        val rowF = metadata.tieJ + (metadata.tieY - lat) / metadata.scaleY
        if (!colF.isFinite() || !rowF.isFinite()) return null
        if (colF < 0.0 || rowF < 0.0 || colF > (metadata.width - 1).toDouble() || rowF > (metadata.height - 1).toDouble()) {
            return null
        }
        if (metadata.planarConfiguration != 1) return null

        return openDataSource(tile.uri)?.use { source ->
            val blockCache = HashMap<Int, ByteArray>()
            val c0 = floor(colF).toInt().coerceIn(0, metadata.width - 1)
            val r0 = floor(rowF).toInt().coerceIn(0, metadata.height - 1)
            val c1 = (c0 + 1).coerceAtMost(metadata.width - 1)
            val r1 = (r0 + 1).coerceAtMost(metadata.height - 1)

            val s00 = sampleAtPixel(source, metadata, c0, r0, blockCache) ?: return@use null
            if (isNoData(s00, metadata.noDataValue)) return@use null
            if (c0 == c1 && r0 == r1) return@use s00

            val s10 = sampleAtPixel(source, metadata, c1, r0, blockCache) ?: return@use null
            val s01 = sampleAtPixel(source, metadata, c0, r1, blockCache) ?: return@use null
            val s11 = sampleAtPixel(source, metadata, c1, r1, blockCache) ?: return@use null
            if (isNoData(s10, metadata.noDataValue) ||
                isNoData(s01, metadata.noDataValue) ||
                isNoData(s11, metadata.noDataValue)
            ) {
                return@use null
            }

            val dx = (colF - c0.toDouble()).coerceIn(0.0, 1.0)
            val dy = (rowF - r0.toDouble()).coerceIn(0.0, 1.0)
            bilinearInterpolate(s00, s10, s01, s11, dx, dy)
        }
    }

    private fun sampleAtPixel(
        source: RandomAccessDataSource,
        metadata: GeoTiffMetadata,
        col: Int,
        row: Int,
        decodedBlockCache: MutableMap<Int, ByteArray>
    ): Double? {
        val decoded = readDecodedBlockBytes(source, metadata, col, row, decodedBlockCache) ?: return null
        return extractSample(decoded, metadata, col, row)
    }

    private fun readDecodedBlockBytes(
        source: RandomAccessDataSource,
        metadata: GeoTiffMetadata,
        col: Int,
        row: Int,
        decodedBlockCache: MutableMap<Int, ByteArray>
    ): ByteArray? {
        if (metadata.isTiled) {
            val tw = metadata.tileWidth ?: return null
            val th = metadata.tileHeight ?: return null
            val tileCols = ceil(metadata.width.toDouble() / tw.toDouble()).toInt()
            val tileCol = floor(col.toDouble() / tw.toDouble()).toInt()
            val tileRow = floor(row.toDouble() / th.toDouble()).toInt()
            val tileIndex = tileRow * tileCols + tileCol
            if (tileIndex !in metadata.blockOffsets.indices || tileIndex !in metadata.blockByteCounts.indices) return null
            decodedBlockCache[tileIndex]?.let { return it }
            val raw = source.readFully(metadata.blockOffsets[tileIndex], metadata.blockByteCounts[tileIndex].toInt())
            val decoded = decodeBlock(raw, metadata, tw, th) ?: return null
            decodedBlockCache[tileIndex] = decoded
            return decoded
        }

        val rowsPerStrip = metadata.rowsPerStrip ?: return null
        val strip = floor(row.toDouble() / rowsPerStrip.toDouble()).toInt()
        if (strip !in metadata.blockOffsets.indices || strip !in metadata.blockByteCounts.indices) return null
        decodedBlockCache[strip]?.let { return it }
        val stripHeight = minOf(rowsPerStrip, metadata.height - (strip * rowsPerStrip))
        val raw = source.readFully(metadata.blockOffsets[strip], metadata.blockByteCounts[strip].toInt())
        val decoded = decodeBlock(raw, metadata, metadata.width, stripHeight) ?: return null
        decodedBlockCache[strip] = decoded
        return decoded
    }

    private fun isNoData(value: Double, noDataValue: Double?): Boolean {
        return noDataValue != null && kotlin.math.abs(value - noDataValue) < 0.001
    }

    private fun bilinearInterpolate(
        s00: Double,
        s10: Double,
        s01: Double,
        s11: Double,
        dx: Double,
        dy: Double
    ): Double {
        val top = s00 + ((s10 - s00) * dx)
        val bottom = s01 + ((s11 - s01) * dx)
        return top + ((bottom - top) * dy)
    }

    private fun decodeBlock(
        raw: ByteArray,
        metadata: GeoTiffMetadata,
        blockWidth: Int,
        blockHeight: Int
    ): ByteArray? {
        val decoded = when (metadata.compression) {
            1 -> RawCompression().decode(raw, metadata.byteOrder)
            5 -> LZWCompression().decode(raw, metadata.byteOrder)
            8, 32946 -> DeflateCompression().decode(raw, metadata.byteOrder)
            32773 -> PackbitsCompression().decode(raw, metadata.byteOrder)
            else -> return null
        }
        val predictor = metadata.predictor
        return if (predictor != null && predictor > 1) {
            Predictor.decode(
                decoded,
                blockWidth,
                blockHeight,
                metadata.samplesPerPixel,
                metadata.bitsPerSample,
                predictor
            )
        } else {
            decoded
        }
    }

    private fun extractSample(
        decodedBlock: ByteArray,
        metadata: GeoTiffMetadata,
        col: Int,
        row: Int
    ): Double? {
        val blockWidth: Int
        val localCol: Int
        val localRow: Int

        if (metadata.isTiled) {
            val tw = metadata.tileWidth ?: return null
            val th = metadata.tileHeight ?: return null
            blockWidth = tw
            localCol = col % tw
            localRow = row % th
        } else {
            val rowsPerStrip = metadata.rowsPerStrip ?: return null
            blockWidth = metadata.width
            localCol = col
            localRow = row % rowsPerStrip
        }

        val bits = metadata.bitsPerSample.firstOrNull() ?: return null
        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return null
        val bytesPerPixel = bytesPerSample * metadata.samplesPerPixel
        val offset = ((localRow * blockWidth) + localCol) * bytesPerPixel
        if (offset + bytesPerSample > decodedBlock.size) return null

        return when (metadata.sampleFormat) {
            3 -> {
                if (bits == 32) {
                    val i = readInt(decodedBlock, offset, metadata.byteOrder)
                    Float.fromBits(i).toDouble()
                } else if (bits == 64) {
                    val l = readLong(decodedBlock, offset, metadata.byteOrder)
                    Double.fromBits(l)
                } else null
            }

            2 -> {
                if (bits == 16) readShort(decodedBlock, offset, metadata.byteOrder).toDouble()
                else if (bits == 32) readInt(decodedBlock, offset, metadata.byteOrder).toDouble()
                else null
            }

            else -> {
                if (bits == 16) readUShort(decodedBlock, offset, metadata.byteOrder).toDouble()
                else if (bits == 32) readUInt(decodedBlock, offset, metadata.byteOrder).toDouble()
                else null
            }
        }
    }

    private fun parseMetadata(source: RandomAccessDataSource): GeoTiffMetadata {
        val bo = readByteOrder(source)
        val firstIfd = readUInt(source, 4, bo)
        val entryCount = readUShort(source, firstIfd, bo)
        var ptr = firstIfd + 2L

        val values = HashMap<Int, Any>()
        repeat(entryCount) {
            val tag = readUShort(source, ptr, bo)
            val type = readUShort(source, ptr + 2, bo)
            val count = readUInt(source, ptr + 4, bo)
            val valueOrOffset = readUInt(source, ptr + 8, bo)
            ptr += 12L

            val size = typeSize(type)
            if (size <= 0 || count <= 0) return@repeat
            val totalBytes = count * size
            val bytes = if (totalBytes <= 4) {
                val inline = source.readFully(ptr - 4L, 4)
                inline.copyOf(totalBytes.toInt())
            } else {
                source.readFully(valueOrOffset, totalBytes.toInt())
            }

            values[tag] = decodeValues(type, count.toInt(), bytes, bo)
        }

        val width = firstLong(values[256])?.toInt() ?: 0
        val height = firstLong(values[257])?.toInt() ?: 0
        val compression = firstLong(values[259])?.toInt() ?: 1
        val samples = firstLong(values[277])?.toInt() ?: 1
        val planar = firstLong(values[284])?.toInt() ?: 1
        val predictor = firstLong(values[317])?.toInt()

        val bits = toIntList(values[258]).ifEmpty { listOf(16) }
        val sampleFormat = toIntList(values[339]).firstOrNull() ?: 1

        val tie = toDoubleList(values[33922])
        val scale = toDoubleList(values[33550])
        val tieI = tie.getOrElse(0) { 0.0 }
        val tieJ = tie.getOrElse(1) { 0.0 }
        val tieX = tie.getOrElse(3) { 0.0 }
        val tieY = tie.getOrElse(4) { 0.0 }
        val scaleX = scale.getOrElse(0) { 0.0 }
        val scaleY = scale.getOrElse(1) { 0.0 }

        val tileOffsets = toLongList(values[324])
        val tileCounts = toLongList(values[325])
        val stripOffsets = toLongList(values[273])
        val stripCounts = toLongList(values[279])

        val tileWidth = firstLong(values[322])?.toInt()
        val tileHeight = firstLong(values[323])?.toInt()
        val rowsPerStrip = firstLong(values[278])?.toInt()

        val isTiled = tileOffsets.isNotEmpty() && tileCounts.isNotEmpty() && tileWidth != null && tileHeight != null
        val offsets = if (isTiled) tileOffsets else stripOffsets
        val counts = if (isTiled) tileCounts else stripCounts

        val noDataValue = parseNoData(values[42113])

        return GeoTiffMetadata(
            width = width,
            height = height,
            byteOrder = bo,
            compression = compression,
            predictor = predictor,
            samplesPerPixel = samples,
            planarConfiguration = planar,
            bitsPerSample = bits,
            sampleFormat = sampleFormat,
            tieI = tieI,
            tieJ = tieJ,
            tieX = tieX,
            tieY = tieY,
            scaleX = scaleX,
            scaleY = scaleY,
            isTiled = isTiled,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            rowsPerStrip = rowsPerStrip,
            blockOffsets = offsets,
            blockByteCounts = counts,
            noDataValue = noDataValue
        )
    }

    private fun readByteOrder(source: RandomAccessDataSource): ByteOrder {
        val marker = source.readFully(0, 2)
        val m = String(marker, Charsets.US_ASCII)
        return when (m) {
            "II" -> ByteOrder.LITTLE_ENDIAN
            "MM" -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("Not TIFF")
        }
    }

    private fun typeSize(type: Int): Long {
        return when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11 -> 4
            5, 10, 12 -> 8
            else -> -1
        }
    }

    private fun decodeValues(type: Int, count: Int, bytes: ByteArray, bo: ByteOrder): Any {
        val bb = ByteBuffer.wrap(bytes).order(bo)
        return when (type) {
            2 -> {
                val raw = bytes.copyOf(count)
                val str = String(raw, Charsets.US_ASCII)
                str.trim('\u0000', ' ')
            }

            3 -> List(count) { bb.short.toInt() and 0xFFFF }
            4 -> List(count) { bb.int.toLong() and 0xFFFFFFFFL }
            5 -> List(count) {
                val n = bb.int.toLong() and 0xFFFFFFFFL
                val d = bb.int.toLong() and 0xFFFFFFFFL
                if (d == 0L) 0.0 else n.toDouble() / d.toDouble()
            }

            8 -> List(count) { bb.short.toInt() }
            9 -> List(count) { bb.int.toLong() }
            10 -> List(count) {
                val n = bb.int.toLong()
                val d = bb.int.toLong()
                if (d == 0L) 0.0 else n.toDouble() / d.toDouble()
            }

            11 -> List(count) { bb.float.toDouble() }
            12 -> List(count) { bb.double }
            else -> bytes
        }
    }

    private fun firstLong(v: Any?): Long? {
        val list = when (v) {
            is List<*> -> v
            else -> return null
        }
        return (list.firstOrNull() as? Number)?.toLong()
    }

    private fun toLongList(v: Any?): List<Long> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toLong() }
    }

    private fun toDoubleList(v: Any?): List<Double> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toDouble() }
    }

    private fun toIntList(v: Any?): List<Int> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { (it as? Number)?.toInt() }
    }

    private fun parseNoData(v: Any?): Double? {
        return when (v) {
            is String -> v.toDoubleOrNull()
            is List<*> -> (v.firstOrNull() as? Number)?.toDouble()
            else -> null
        }
    }

    private fun openDataSource(uri: Uri): RandomAccessDataSource? {
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let { File(it) } ?: return null
            if (!file.exists() || !file.isFile) return null
            FileRandomAccessDataSource(file)
        } else {
            val pfd = appContext.contentResolver.openFileDescriptor(uri, "r") ?: return null
            PfdRandomAccessDataSource(pfd)
        }
    }

    private fun readUShort(bytes: ByteArray, offset: Int, bo: ByteOrder): Int {
        return if (bo == ByteOrder.LITTLE_ENDIAN) {
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }
    }

    private fun readShort(bytes: ByteArray, offset: Int, bo: ByteOrder): Short {
        return readUShort(bytes, offset, bo).toShort()
    }

    private fun readUInt(bytes: ByteArray, offset: Int, bo: ByteOrder): Long {
        return readInt(bytes, offset, bo).toLong() and 0xFFFFFFFFL
    }

    private fun readInt(bytes: ByteArray, offset: Int, bo: ByteOrder): Int {
        return if (bo == ByteOrder.LITTLE_ENDIAN) {
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int, bo: ByteOrder): Long {
        val hi: Long
        val lo: Long
        if (bo == ByteOrder.LITTLE_ENDIAN) {
            lo = readUInt(bytes, offset, bo)
            hi = readUInt(bytes, offset + 4, bo)
        } else {
            hi = readUInt(bytes, offset, bo)
            lo = readUInt(bytes, offset + 4, bo)
        }
        return (hi shl 32) or lo
    }

    private fun readUShort(source: RandomAccessDataSource, position: Long, bo: ByteOrder): Int {
        val b = source.readFully(position, 2)
        return readUShort(b, 0, bo)
    }

    private fun readUInt(source: RandomAccessDataSource, position: Long, bo: ByteOrder): Long {
        val b = source.readFully(position, 4)
        return readUInt(b, 0, bo)
    }

    private fun boundsFromName(name: String): TileBounds? {
        val m = TILE_NAME_PATTERN.matcher(name)
        if (!m.matches()) return null
        val ns = m.group(1)?.lowercase(Locale.US) ?: return null
        val latDeg = m.group(2)?.toIntOrNull() ?: return null
        val ew = m.group(3)?.lowercase(Locale.US) ?: return null
        val lonDeg = m.group(4)?.toIntOrNull() ?: return null

        val minLat: Double
        val maxLat: Double
        if (ns == "n") {
            minLat = latDeg.toDouble()
            maxLat = minLat + 1.0
        } else {
            maxLat = -latDeg.toDouble()
            minLat = maxLat - 1.0
        }

        val minLon: Double
        val maxLon: Double
        if (ew == "e") {
            minLon = lonDeg.toDouble()
            maxLon = minLon + 1.0
        } else {
            minLon = -lonDeg.toDouble()
            maxLon = minLon + 1.0
        }

        return TileBounds(minLat, maxLat, minLon, maxLon)
    }

    private data class DemTile(
        val id: String,
        val uri: Uri,
        val displayName: String,
        val bounds: TileBounds?
    )

    private data class TileBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    ) {
        fun contains(lat: Double, lng: Double): Boolean {
            return lat >= minLat && lat < maxLat && lng >= minLon && lng < maxLon
        }
    }

    private data class GeoTiffMetadata(
        val width: Int,
        val height: Int,
        val byteOrder: ByteOrder,
        val compression: Int,
        val predictor: Int?,
        val samplesPerPixel: Int,
        val planarConfiguration: Int,
        val bitsPerSample: List<Int>,
        val sampleFormat: Int,
        val tieI: Double,
        val tieJ: Double,
        val tieX: Double,
        val tieY: Double,
        val scaleX: Double,
        val scaleY: Double,
        val isTiled: Boolean,
        val tileWidth: Int?,
        val tileHeight: Int?,
        val rowsPerStrip: Int?,
        val blockOffsets: List<Long>,
        val blockByteCounts: List<Long>,
        val noDataValue: Double?
    )

    private interface RandomAccessDataSource : Closeable {
        fun readFully(position: Long, length: Int): ByteArray
    }

    private class FileRandomAccessDataSource(file: File) : RandomAccessDataSource {
        private val raf = RandomAccessFile(file, "r")

        override fun readFully(position: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            raf.seek(position)
            raf.readFully(out)
            return out
        }

        override fun close() {
            raf.close()
        }
    }

    private class PfdRandomAccessDataSource(
        pfd: ParcelFileDescriptor
    ) : RandomAccessDataSource {
        private val parcel = pfd
        private val channel = ParcelFileDescriptor.AutoCloseInputStream(parcel).channel

        override fun readFully(position: Long, length: Int): ByteArray {
            val bb = ByteBuffer.allocate(length)
            var offset = 0
            while (offset < length) {
                val read = channel.read(bb, position + offset)
                if (read <= 0) throw EOFException("short read")
                offset += read
            }
            return bb.array()
        }

        override fun close() {
            channel.close()
        }
    }

    private companion object {
        private val TILE_NAME_PATTERN: Pattern =
            Pattern.compile(".*([nNsS])(\\d{2})([eEwW])(\\d{3}).*\\.tiff?$")
    }
}
