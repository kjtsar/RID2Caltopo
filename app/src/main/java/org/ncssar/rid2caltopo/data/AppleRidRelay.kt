package org.ncssar.rid2caltopo.data

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class AppleRidRelayConfig(
    val enabled: Boolean = false,
    val host: String = DEFAULT_APPLE_RID_RELAY_HOST,
    val port: Int = DEFAULT_APPLE_RID_RELAY_PORT,
) {
    val normalizedHost: String
        get() = host.trim().removePrefix("udp://").substringBefore('/').substringBefore(':')

    val isReady: Boolean
        get() = enabled && normalizedHost.isNotBlank() && port in 1..65535
}

data class AppleRidRelayObservation(
    val remoteID: String,
    val source: String,
    val timestampMilliseconds: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val headingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val operatorLatitude: Double? = null,
    val operatorLongitude: Double? = null,
    val rssiDbm: Int? = null,
)

const val DEFAULT_APPLE_RID_RELAY_HOST = "255.255.255.255"
const val DEFAULT_APPLE_RID_RELAY_PORT = 7654

object AppleRidRelayPrefs {
    private const val PREFS = "apple-rid-relay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST = "host"

    fun load(context: Context): AppleRidRelayConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppleRidRelayConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            host = prefs.getString(KEY_HOST, DEFAULT_APPLE_RID_RELAY_HOST)
                ?: DEFAULT_APPLE_RID_RELAY_HOST,
        )
    }

    fun save(context: Context, config: AppleRidRelayConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_HOST, config.host.trim())
            .apply()
    }
}

object AppleRidRelay {
    private const val TAG = "AppleRidRelay"
    private const val QUEUE_CAPACITY = 256
    private val sentCount = AtomicLong()
    private val failureCount = AtomicLong()
    private val droppedCount = AtomicLong()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "Apple-RID-relay").apply { isDaemon = true } },
        { _, _ ->
            val dropped = droppedCount.incrementAndGet()
            if (dropped == 1L || dropped % 100L == 0L) {
                CaltopoClient.CTError(TAG, "Relay queue full; dropped=$dropped")
            }
        },
    )

    @Volatile
    private var config = AppleRidRelayConfig()
    private var socket: DatagramSocket? = null

    @JvmStatic
    fun refreshConfiguration(context: Context) {
        config = AppleRidRelayPrefs.load(context)
        CaltopoClient.CTInfo(TAG, "Configuration ${statusText()}")
    }

    @JvmStatic
    fun forwardAcceptedWifiObservation(
        remoteID: String,
        source: String,
        timestampMilliseconds: Long,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        headingDegrees: Double,
        speedMetersPerSecond: Double,
        operatorLatitude: Double,
        operatorLongitude: Double,
        rssiDbm: Int,
    ) {
        val destination = config
        if (!destination.isReady) return
        val observation = AppleRidRelayObservation(
            remoteID = remoteID,
            source = source,
            timestampMilliseconds = timestampMilliseconds,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters.takeIf(Double::isFinite),
            headingDegrees = headingDegrees.takeIf(Double::isFinite),
            speedMetersPerSecond = speedMetersPerSecond.takeIf(Double::isFinite),
            operatorLatitude = operatorLatitude.takeIf(Double::isFinite),
            operatorLongitude = operatorLongitude.takeIf(Double::isFinite),
            rssiDbm = rssiDbm.takeIf { it != 0 },
        )
        executor.execute { sendNow(destination, observation) }
    }

    @JvmStatic
    fun statusText(): String {
        val current = config
        return when {
            !current.enabled -> "Disabled"
            !current.isReady -> "Invalid destination"
            else -> "${current.normalizedHost}:${current.port} sent=${sentCount.get()} failed=${failureCount.get()} dropped=${droppedCount.get()}"
        }
    }

    internal fun buildPayload(observation: AppleRidRelayObservation): ByteArray {
        val payload = JSONObject()
            .put("aircraft_id", observation.remoteID)
            .put("source", observation.source)
            .put("timestamp_ms", observation.timestampMilliseconds)
            .put("latitude", observation.latitude)
            .put("longitude", observation.longitude)
        observation.altitudeMeters?.let { payload.put("altitude_m", it) }
        observation.headingDegrees?.let { payload.put("heading_deg", it) }
        observation.speedMetersPerSecond?.let { payload.put("speed_mps", it) }
        observation.operatorLatitude?.let { payload.put("operator_latitude", it) }
        observation.operatorLongitude?.let { payload.put("operator_longitude", it) }
        observation.rssiDbm?.let { payload.put("rssi_dbm", it) }
        return payload.toString().toByteArray(StandardCharsets.UTF_8)
    }

    internal fun sendNow(
        destination: AppleRidRelayConfig,
        observation: AppleRidRelayObservation,
    ): Boolean {
        try {
            val bytes = buildPayload(observation)
            val packet = DatagramPacket(
                bytes,
                bytes.size,
                InetAddress.getByName(destination.normalizedHost),
                destination.port,
            )
            val activeSocket = socket ?: DatagramSocket().also {
                it.broadcast = true
                socket = it
            }
            activeSocket.send(packet)
            sentCount.incrementAndGet()
            return true
        } catch (error: Exception) {
            socket?.close()
            socket = null
            val failed = failureCount.incrementAndGet()
            if (failed == 1L || failed % 50L == 0L) {
                CaltopoClient.CTError(TAG, "Relay send failed occurrence=$failed", error)
            }
            return false
        }
    }
}
