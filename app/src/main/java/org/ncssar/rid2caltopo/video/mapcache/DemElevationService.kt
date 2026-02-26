package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class DemElevationSample(
    val elevationMeters: Double,
    val stale: Boolean,
    val source: String
)

internal class DemElevationService(context: Context) {
    private val localGeoTiff = GeoTiffDemSource(context.applicationContext)
    private val cache: BlobCacheStore = BlobCacheStoreFactory.create(
        context = context.applicationContext,
        namespace = "dem_point_v1",
        dbName = "dem_point_v1.db",
        maxBytes = 50L * 1024L * 1024L,
        defaultTtlMs = 365L * 24L * 60L * 60L * 1000L
    )
    private val mem = object : LinkedHashMap<String, DemElevationSample>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DemElevationSample>?): Boolean {
            return size > 2048
        }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun sampleElevationMeters(lat: Double, lng: Double): DemElevationSample? = withContext(Dispatchers.IO) {
        if (!lat.isFinite() || !lng.isFinite()) return@withContext null
        val key = cacheKey(lat, lng)

        synchronized(mem) {
            val cached = mem[key]
            if (cached != null) {
                MapCacheDebug.log("dem mem-hit key=$key source=${cached.source} stale=${cached.stale}")
                return@withContext cached
            }
        }

        val local = cache.get(key)
        val cachedSample = local?.let { bytesToSample(it.bytes, stale = it.stale) }
        if (cachedSample != null && !cachedSample.stale) {
            synchronized(mem) { mem[key] = cachedSample }
            MapCacheDebug.log("dem disk-hit key=$key source=${cachedSample.source}")
            return@withContext cachedSample
        }
        if (cachedSample == null) {
            MapCacheDebug.log("dem disk-miss key=$key")
        } else {
            MapCacheDebug.log("dem disk-stale key=$key source=${cachedSample.source}")
        }

        val localTiff = localGeoTiff.sampleElevationMeters(lat, lng)
        if (localTiff != null && localTiff.isFinite()) {
            val sample = DemElevationSample(
                elevationMeters = localTiff,
                stale = false,
                source = "usgs-geotiff-local"
            )
            cache.put(key, sampleToBytes(sample), cache.defaultExpiry())
            synchronized(mem) { mem[key] = sample }
            MapCacheDebug.log("dem local-geotiff key=$key elevM=${"%.2f".format(Locale.US, sample.elevationMeters)}")
            return@withContext sample
        }
        MapCacheDebug.log("dem local-geotiff-miss key=$key")

        val network = fetchSample(lat, lng)
        if (network != null) {
            cache.put(key, sampleToBytes(network), cache.defaultExpiry())
            synchronized(mem) { mem[key] = network }
            MapCacheDebug.log("dem network-fetch key=$key elevM=${"%.2f".format(Locale.US, network.elevationMeters)}")
            return@withContext network
        }
        MapCacheDebug.log("dem network-fail key=$key")

        if (cachedSample != null) {
            cache.markStaleServed()
            synchronized(mem) { mem[key] = cachedSample }
            CTDebug("SplitMapPane", "DEM stale fallback lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}")
            MapCacheDebug.log("dem stale-fallback key=$key source=${cachedSample.source}")
            return@withContext cachedSample
        }

        null
    }

    fun clear() {
        synchronized(mem) { mem.clear() }
        cache.clear()
    }

    fun prewarm() {
        cache.prewarm()
    }

    fun statsSnapshot(): CacheStatsSnapshot = cache.snapshot()

    fun cacheKey(lat: Double, lng: Double): String {
        val qLat = quantize(lat)
        val qLng = quantize(lng)
        return "v1|$qLat|$qLng|m"
    }

    private fun quantize(value: Double): Int {
        return kotlin.math.round(value * 100_000.0).toInt()
    }

    private fun fetchSample(lat: Double, lng: Double): DemElevationSample? {
        return try {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("epqs.nationalmap.gov")
                .addPathSegment("v1")
                .addPathSegment("json")
                .addQueryParameter("x", lng.toString())
                .addQueryParameter("y", lat.toString())
                .addQueryParameter("units", "Meters")
                .addQueryParameter("wkid", "4326")
                .build()
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val jo = JSONObject(body)
                val value = when {
                    jo.has("value") -> jo.optDouble("value", Double.NaN)
                    jo.has("elevation") -> jo.optDouble("elevation", Double.NaN)
                    jo.has("USGS_Elevation_Point_Query_Service") -> {
                        val nested = jo.optJSONObject("USGS_Elevation_Point_Query_Service")
                            ?.optJSONObject("Elevation_Query")
                        nested?.optDouble("Elevation", Double.NaN) ?: Double.NaN
                    }
                    else -> Double.NaN
                }
                if (!value.isFinite() || value < -500.0 || value > 10000.0) return null
                DemElevationSample(
                    elevationMeters = value,
                    stale = false,
                    source = "usgs-epqs"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleToBytes(sample: DemElevationSample): ByteArray {
        val jo = JSONObject()
        jo.put("elevationMeters", sample.elevationMeters)
        jo.put("source", sample.source)
        return jo.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun bytesToSample(bytes: ByteArray, stale: Boolean): DemElevationSample? {
        return try {
            val jo = JSONObject(String(bytes, StandardCharsets.UTF_8))
            val value = jo.optDouble("elevationMeters", Double.NaN)
            if (!value.isFinite()) return null
            DemElevationSample(
                elevationMeters = value,
                stale = stale,
                source = jo.optString("source", "cache")
            )
        } catch (_: Exception) {
            null
        }
    }
}
