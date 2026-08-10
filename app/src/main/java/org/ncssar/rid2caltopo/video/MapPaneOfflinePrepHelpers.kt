package org.ncssar.rid2caltopo.video

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import org.ncssar.rid2caltopo.video.mapcache.BadTilePolicy
import org.ncssar.rid2caltopo.video.mapcache.DemElevationService
import org.ncssar.rid2caltopo.video.mapcache.MapCacheDebug
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRoot
import org.ncssar.rid2caltopo.video.mapcache.MapCacheRootResolver
import org.osmdroid.util.BoundingBox
import java.io.File
import java.util.Locale

internal enum class DemResolutionOption(val meters: Int, val label: String, val explanation: String) {
    STANDARD_30M(30, "Standard (30 m)", "Default; broad coverage and smallest download."),
    ENHANCED_10M(10, "Enhanced (10 m)", "About 9x as many terrain samples as 30 m."),
    MAXIMUM_1M(1, "Maximum available (1 m)", "Downloads available USGS lidar-derived project tiles; may be very large.")
}

internal data class DemDownload(
    val url: String,
    val fileName: String,
    val expectedBytes: Long? = null
)

internal fun estimateDemDownloadCount(bounds: BoundingBox, resolution: DemResolutionOption): Int {
    if (resolution != DemResolutionOption.MAXIMUM_1M) return demTileNamesForBounds(bounds).size
    val centerLat = (bounds.latNorth + bounds.latSouth) / 2.0
    val widthMeters = kotlin.math.abs(bounds.lonEast - bounds.lonWest) * 111_320.0 *
        kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val heightMeters = kotlin.math.abs(bounds.latNorth - bounds.latSouth) * 111_320.0
    val oneMeterTiles = maxOf(1, kotlin.math.ceil(widthMeters / 10_000.0).toInt() * kotlin.math.ceil(heightMeters / 10_000.0).toInt())
    return demTileNamesForBounds(bounds).size + oneMeterTiles
}

internal fun conservativeDemBytes(count: Int, resolution: DemResolutionOption): Long {
    val perTile = when (resolution) {
        DemResolutionOption.STANDARD_30M -> 54_000_000L
        DemResolutionOption.ENHANCED_10M -> 486_000_000L
        // Maximum detail also includes 10 m coverage beneath any unavailable/NoData 1 m areas.
        DemResolutionOption.MAXIMUM_1M -> 486_000_000L
    }
    return count.toLong() * perTile
}

internal fun conservativeDemBytes(bounds: BoundingBox, resolution: DemResolutionOption): Long {
    if (resolution != DemResolutionOption.MAXIMUM_1M) {
        return conservativeDemBytes(estimateDemDownloadCount(bounds, resolution), resolution)
    }
    val fallbackCount = demTileNamesForBounds(bounds).size
    val oneMeterCount = (estimateDemDownloadCount(bounds, resolution) - fallbackCount).coerceAtLeast(1)
    return fallbackCount * 486_000_000L + oneMeterCount * 400_000_000L
}

internal fun resolveDemDownloads(
    bounds: BoundingBox,
    resolution: DemResolutionOption,
    client: OkHttpClient
): List<DemDownload> {
    if (resolution != DemResolutionOption.MAXIMUM_1M) {
        val product = if (resolution == DemResolutionOption.ENHANCED_10M) "13" else "1"
        return demTileNamesForBounds(bounds).map { name ->
            val fileName = "USGS_${product}_$name.tif"
            DemDownload(
                url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/$product/TIFF/current/$name/$fileName",
                fileName = fileName
            )
        }
    }
    val out = linkedMapOf<String, DemDownload>()
    // Keep complete 10 m coverage below project-based 1 m tiles so gaps and NoData areas
    // remain useful offline. The sampler will choose the finest valid overlapping value.
    demTileNamesForBounds(bounds).forEach { name ->
        val fileName = "USGS_13_$name.tif"
        val url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/$name/$fileName"
        out[url] = DemDownload(url, fileName)
    }
    var offset = 0
    do {
        val url = HttpUrl.Builder()
            .scheme("https").host("tnmaccess.nationalmap.gov")
            .addPathSegments("api/v1/products")
            .addQueryParameter("bbox", "${bounds.lonWest},${bounds.latSouth},${bounds.lonEast},${bounds.latNorth}")
            .addQueryParameter("prodFormats", "GeoTIFF")
            .addQueryParameter("outputFormat", "JSON")
            .addQueryParameter("datasets", "Digital Elevation Model (DEM) 1 meter")
            .addQueryParameter("max", "100").addQueryParameter("offset", offset.toString())
            .build()
        val page = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "TNM catalog HTTP ${response.code}" }
            JSONObject(response.body?.string() ?: error("TNM catalog response was empty"))
        }
        val items = page.optJSONArray("items") ?: break
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val downloadUrl = item.optString("downloadURL").takeIf { it.startsWith("https://") } ?: continue
            val originalName = downloadUrl.substringAfterLast('/').takeIf { it.endsWith(".tif", ignoreCase = true) } ?: continue
            val boundingBox = item.optJSONObject("boundingBox")
            val fileName = if (boundingBox != null) {
                val minLat = kotlin.math.round(boundingBox.optDouble("minY") * 100_000.0).toLong()
                val maxLat = kotlin.math.round(boundingBox.optDouble("maxY") * 100_000.0).toLong()
                val minLon = kotlin.math.round(boundingBox.optDouble("minX") * 100_000.0).toLong()
                val maxLon = kotlin.math.round(boundingBox.optDouble("maxX") * 100_000.0).toLong()
                "R2C_1M_${minLat}_${maxLat}_${minLon}_${maxLon}_$originalName"
            } else originalName
            val expected = item.optLong("sizeInBytes", -1L).takeIf { it > 0L }
            out[downloadUrl] = DemDownload(downloadUrl, fileName, expected)
        }
        offset += items.length()
        val total = page.optInt("total", offset)
        if (items.length() == 0 || offset >= total) break
    } while (offset < 2_000)
    return out.values.sortedBy { it.fileName }
}

private fun estimateDemSamplesForBounds(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null
): Int {
    if (stepMeters <= 0.0) return 0
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val centerLat = (loLat + hiLat) / 2.0
    val latStepDeg = stepMeters / 111_320.0
    val lonMetersAtLat = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val lonStepDeg = stepMeters / lonMetersAtLat
    var total = 0
    var lat = loLat
    while (lat <= hiLat) {
        var lon = loLon
        while (lon <= hiLon) {
            if (clipBoundary == null || pointInPolygon(lat, lon, clipBoundary.ring)) {
                total++
            }
            lon += lonStepDeg
        }
        lat += latStepDeg
    }
    return total
}

internal fun estimateDemSamplesApproximate(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null
): Int {
    if (stepMeters <= 0.0) return 0
    val effectiveArea = if (clipBoundary != null) {
        polygonAreaMeters2(clipBoundary.ring).coerceAtLeast(0.0)
    } else {
        boundsAreaMeters2(bounds).coerceAtLeast(0.0)
    }
    if (effectiveArea <= 0.0) return 0
    val sampleArea = stepMeters * stepMeters
    return kotlin.math.max(1, kotlin.math.ceil(effectiveArea / sampleArea).toInt())
}

private suspend fun forEachDemSamplePointForBounds(
    bounds: BoundingBox,
    stepMeters: Double,
    clipBoundary: GeoBoundary? = null,
    block: suspend (Double, Double) -> Unit
) {
    if (stepMeters <= 0.0) return
    val north = bounds.latNorth
    val south = bounds.latSouth
    val west = bounds.lonWest
    val east = bounds.lonEast
    val loLat = minOf(north, south)
    val hiLat = maxOf(north, south)
    val loLon = minOf(west, east)
    val hiLon = maxOf(west, east)
    val centerLat = (loLat + hiLat) / 2.0
    val latStepDeg = stepMeters / 111_320.0
    val lonMetersAtLat = 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
    val lonStepDeg = stepMeters / lonMetersAtLat
    var lat = loLat
    while (lat <= hiLat) {
        var lon = loLon
        while (lon <= hiLon) {
            if (clipBoundary == null || pointInPolygon(lat, lon, clipBoundary.ring)) {
                block(lat, lon)
            }
            lon += lonStepDeg
        }
        lat += latStepDeg
    }
}

/** Returns the USGS 3DEP 1 degree tile name (e.g. "n40w122") that covers the given coordinate. */
internal fun tileNameForLocation(lat: Double, lng: Double): String {
    val tileNorth = kotlin.math.floor(lat).toInt() + 1
    val tileLonBlock = kotlin.math.floor(lng).toInt()
    val latPart = if (tileNorth >= 0) "n%02d".format(tileNorth) else "s%02d".format(-tileNorth)
    val lonPart = if (tileLonBlock < 0) "w%03d".format(-tileLonBlock) else "e%03d".format(tileLonBlock + 1)
    return "$latPart$lonPart"
}

internal suspend fun autoDownloadDemTile(
    tileName: String,
    context: Context,
    client: OkHttpClient,
    service: DemElevationService
) {
    val archiveRoot = CaltopoClient.GetArchiveDir() ?: run {
        MapCacheDebug.log("auto-dem: no archive dir, skipping tile=$tileName")
        return
    }
    val cacheDir = archiveRoot.findFile("cache") ?: archiveRoot.createDirectory("cache") ?: return
    val demDir = cacheDir.findFile("dem") ?: cacheDir.createDirectory("dem") ?: return
    val fileName = "USGS_1_$tileName.tif"
    val existing = demDir.findFile(fileName)
    if (existing != null && existing.isFile && existing.length() > 5_000_000L) {
        MapCacheDebug.log("auto-dem: already present tile=$tileName bytes=${existing.length()}")
        service.refreshGeoTiffCatalog()
        return
    }
    val url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1/TIFF/current/$tileName/USGS_1_$tileName.tif"
    CTDebug(MAP_PANE_TAG, "auto-dem: downloading tile=$tileName")
    try {
        val ok = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                CTError(MAP_PANE_TAG, "auto-dem http-fail code=${resp.code} tile=$tileName")
                return@use false
            }
            val body = resp.body ?: run { CTError(MAP_PANE_TAG, "auto-dem no-body tile=$tileName"); return@use false }
            val destFile = demDir.findFile(fileName) ?: demDir.createFile("image/tiff", fileName)
                ?: run { CTError(MAP_PANE_TAG, "auto-dem create-failed tile=$tileName"); return@use false }
            context.contentResolver.openOutputStream(destFile.uri, "wt")?.use { out ->
                body.byteStream().copyTo(out)
            } ?: run { CTError(MAP_PANE_TAG, "auto-dem stream-open-failed tile=$tileName"); return@use false }
            true
        }
        if (ok) {
            CTDebug(MAP_PANE_TAG, "auto-dem: complete tile=$tileName")
            service.refreshGeoTiffCatalog()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CTError(MAP_PANE_TAG, "auto-dem ex tile=$tileName: ${e.javaClass.simpleName}:${e.message}")
    }
}

internal fun demTileNamesForBounds(bounds: BoundingBox): List<String> {
    val latMin = minOf(bounds.latNorth, bounds.latSouth)
    val latMax = maxOf(bounds.latNorth, bounds.latSouth)
    val lonMin = minOf(bounds.lonWest, bounds.lonEast)
    val lonMax = maxOf(bounds.lonWest, bounds.lonEast)
    val latSouthBlock = kotlin.math.floor(latMin).toInt()
    val latNorthBlock = kotlin.math.ceil(latMax).toInt() - 1
    val lonWestBlock = kotlin.math.floor(lonMin).toInt()
    val lonEastBlock = kotlin.math.ceil(lonMax).toInt() - 1
    val names = mutableListOf<String>()
    for (latBlock in latSouthBlock..latNorthBlock) {
        val tileNorth = latBlock + 1
        val latPart = if (tileNorth >= 0) "n%02d".format(tileNorth) else "s%02d".format(-tileNorth)
        for (lonBlock in lonWestBlock..lonEastBlock) {
            val lonPart = if (lonBlock < 0) "w%03d".format(-lonBlock) else "e%03d".format(lonBlock + 1)
            names += "$latPart$lonPart"
        }
    }
    return names
}

internal fun formatDurationShort(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val h = safe / 3600L
    val m = (safe % 3600L) / 60L
    val s = safe % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

internal fun queryAvailableCacheBytes(context: Context): Long? {
    return try {
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> {
                val stat = StatFs(root.dir.absolutePath)
                stat.availableBytes
            }
            is MapCacheRoot.SafBacked -> {
                null
            }
        }
    } catch (_: Exception) {
        null
    }
}

internal fun mapCacheRootSignature(context: Context): String {
    return try {
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> "file:${root.dir.absolutePath}"
            is MapCacheRoot.SafBacked -> "saf:${root.dir.uri}"
        }
    } catch (_: Exception) {
        "unknown"
    }
}

internal fun exportBadTileHashes(context: Context): String? {
    return try {
        val hashes = BadTilePolicy.blockedHashesSorted(context)
        val header = "# RID2Caltopo bad tile hashes\n# count=${hashes.size}\n"
        val body = if (hashes.isEmpty()) "# (none)\n" else hashes.joinToString(separator = "\n", postfix = "\n")
        val payload = (header + body).toByteArray(Charsets.UTF_8)
        when (val root = MapCacheRootResolver.resolveRoot(context.applicationContext)) {
            is MapCacheRoot.FileBacked -> {
                val out = File(root.dir, "bad_tile_hashes.txt")
                out.writeBytes(payload)
                out.absolutePath
            }
            is MapCacheRoot.SafBacked -> {
                val existing = root.dir.findFile("bad_tile_hashes.txt")
                val file = existing ?: root.dir.createFile("text/plain", "bad_tile_hashes.txt")
                if (file == null) return null
                context.applicationContext.contentResolver.openOutputStream(file.uri, "w")?.use { out ->
                    out.write(payload)
                    out.flush()
                } ?: return null
                file.uri.toString()
            }
        }
    } catch (e: Exception) {
        MapCacheDebug.log("bad-hash export failed err=${e.javaClass.simpleName}:${e.message}")
        null
    }
}
