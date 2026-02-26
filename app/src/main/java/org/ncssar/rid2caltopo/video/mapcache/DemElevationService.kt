package org.ncssar.rid2caltopo.video.mapcache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTError
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class DemElevationSample(
    val elevationMeters: Double,
    val stale: Boolean,
    val source: String
)

internal class DemElevationService(context: Context) {
    companion object {
        private const val EPQS_MIN_INTERVAL_MS = 350L
        private const val EPQS_MAX_ATTEMPTS = 4
        private const val EPQS_BASE_BACKOFF_MS = 500L
        private const val EPQS_MAX_BACKOFF_MS = 8_000L

        private val epqsRateGateLock = Any()
        @Volatile
        private var nextEpqsRequestAtMs = 0L
    }

    private val demHttpCodeRegex = Regex("""dem http-fail code=(\d{3})""")
    private val networkFailCount = AtomicInteger(0)
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
        logDemWarn("dem network-fail key=$key lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}")

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

    fun hasCachedSample(lat: Double, lng: Double): Boolean {
        if (!lat.isFinite() || !lng.isFinite()) return false
        val key = cacheKey(lat, lng)
        synchronized(mem) {
            if (mem.containsKey(key)) return true
        }
        return cache.exists(key)
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

    private suspend fun fetchSample(lat: Double, lng: Double): DemElevationSample? {
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

        var attempt = 1
        while (attempt <= EPQS_MAX_ATTEMPTS) {
            waitForEpqsRateSlot()
            try {
                val resp = client.newCall(req).execute()
                try {
                    if (!resp.isSuccessful) {
                        val retryAfterMs = retryAfterMs(resp)
                        logDemWarn(
                            "dem http-fail code=${resp.code} msg=${resp.message} attempt=$attempt/$EPQS_MAX_ATTEMPTS " +
                                "retryAfterMs=${retryAfterMs ?: -1} " +
                                "lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}"
                        )
                        if (resp.code == 429 || resp.code == 503 || resp.code == 502) {
                            if (attempt < EPQS_MAX_ATTEMPTS) {
                                delay(retryAfterMs ?: backoffMs(attempt))
                                attempt += 1
                                continue
                            }
                        }
                        return null
                    }
                    val body = resp.body?.string()
                    if (body.isNullOrBlank()) {
                        logDemWarn(
                            "dem body-empty lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}"
                        )
                        return null
                    }
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
                    if (value.isNaN()) {
                        logDemWarn(
                            "dem parse-miss keys=${jo.keys().asSequence().toList().joinToString("|")} " +
                                "lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}"
                        )
                        return null
                    }
                    if (!value.isFinite() || value < -500.0 || value > 10000.0) {
                        logDemWarn(
                            "dem value-invalid v=$value lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}"
                        )
                        return null
                    }
                    return DemElevationSample(
                        elevationMeters = value,
                        stale = false,
                        source = "usgs-epqs"
                    )
                } finally {
                    resp.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logDemWarn(
                    "dem fetch-ex ${e.javaClass.simpleName}:${e.message} attempt=$attempt/$EPQS_MAX_ATTEMPTS " +
                        "lat=${"%.5f".format(Locale.US, lat)} lng=${"%.5f".format(Locale.US, lng)}"
                )
                if (attempt < EPQS_MAX_ATTEMPTS) {
                    delay(backoffMs(attempt))
                    attempt += 1
                    continue
                }
                return null
            }
        }
        return null
    }

    private suspend fun waitForEpqsRateSlot() {
        while (true) {
            val waitMs = synchronized(epqsRateGateLock) {
                val now = System.currentTimeMillis()
                if (now >= nextEpqsRequestAtMs) {
                    nextEpqsRequestAtMs = now + EPQS_MIN_INTERVAL_MS
                    0L
                } else {
                    nextEpqsRequestAtMs - now
                }
            }
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val expo = EPQS_BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(5))
        return expo.coerceAtMost(EPQS_MAX_BACKOFF_MS)
    }

    private fun retryAfterMs(resp: Response): Long? {
        val header = resp.header("Retry-After")?.trim().orEmpty()
        if (header.isEmpty()) return null
        val secs = header.toLongOrNull() ?: return null
        return (secs.coerceIn(1L, 120L)) * 1_000L
    }

    private fun logDemWarn(message: String) {
        val n = networkFailCount.incrementAndGet()
        if (n <= 12 || (n % 50) == 0) {
            MapCacheDebug.warn(MapCacheDebug.TAG_DEM, "$message failCount=$n")
            val code = demHttpCodeRegex.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (code == 403 || code == 429 || code == 503) {
                CTError(
                    "SplitMapPane",
                    "DEM upstream status=$code (possible policy/rate-limit/service pressure) $message failCount=$n"
                )
            }
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
