package org.ncssar.rid2caltopo.video

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
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
