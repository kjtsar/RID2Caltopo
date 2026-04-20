package org.ncssar.rid2caltopo.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

object RidReplayManager {
    private const val TAG = "RidReplayMgr"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var activeReplay: ActiveReplay? = null

    private data class ReplayEvent(
        val drone: RidReplayScenario.DroneTrack,
        val point: RidReplayScenario.Point,
        val eventTimestampMs: Long
    )

    private data class ActiveReplay(
        val replayId: Long,
        val scenarioName: String,
        val futures: MutableList<ScheduledFuture<*>>,
        val clients: ConcurrentHashMap<String, CaltopoClient>
    )

    @JvmStatic
    fun isReplayRunning(): Boolean = activeReplay != null

    @JvmStatic
    fun getActiveReplayName(): String = activeReplay?.scenarioName.orEmpty()

    @JvmStatic
    fun stopReplay(): String {
        val replay = activeReplay ?: return "No RID replay is running."
        replay.futures.forEach { it.cancel(true) }
        activeReplay = null
        return "Stopped RID replay '${replay.scenarioName}'."
    }

    @JvmStatic
    fun startReplayFromUri(
        context: Context,
        srcUri: Uri,
        callback: (Boolean, String) -> Unit
    ) {
        val appContext = context.applicationContext
        ioExecutor.execute {
            val result = try {
                val json = appContext.contentResolver.openInputStream(srcUri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Could not read RID replay file.")
                val scenario = RidReplayScenarioParser.parse(json)
                startReplayInternal(scenario)
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "startReplayFromUri() failed.", e)
                false to (e.message ?: "Failed to start RID replay.")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    @JvmStatic
    fun startReplay(
        scenario: RidReplayScenario,
        callback: (Boolean, String) -> Unit
    ) {
        ioExecutor.execute {
            val result = try {
                startReplayInternal(scenario)
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "startReplay() failed.", e)
                false to (e.message ?: "Failed to start RID replay.")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    private fun startReplayInternal(scenario: RidReplayScenario): Pair<Boolean, String> {
        stopReplay()
        val replayId = System.currentTimeMillis()
        val events = flattenScenarioEvents(scenario)
        require(events.isNotEmpty()) { "RID replay scenario has no points to play." }
        val anchorTimestampMs = when (scenario.timeMode) {
            RidReplayScenario.TimeMode.RELATIVE_MS -> events.minOf { it.eventTimestampMs }
            RidReplayScenario.TimeMode.ABSOLUTE_EPOCH_MS -> events.minOf { it.eventTimestampMs }
        }
        val replayWallClockBaseMs = System.currentTimeMillis()
        val futures = ArrayList<ScheduledFuture<*>>(events.size + 1)
        val clients = ConcurrentHashMap<String, CaltopoClient>()
        val replay = ActiveReplay(replayId, scenario.scenarioName, futures, clients)
        activeReplay = replay

        prepareReplayMetadata(events.map { it.drone }.distinctBy { it.remoteId }, clients)

        val speed = max(scenario.speedMultiplier, 0.001)
        for (event in events) {
            val delayMs = ((event.eventTimestampMs - anchorTimestampMs) / speed).toLong().coerceAtLeast(0L)
            val droneTimestampMs = when (scenario.timeMode) {
                RidReplayScenario.TimeMode.RELATIVE_MS ->
                    replayWallClockBaseMs + (event.eventTimestampMs - anchorTimestampMs)
                RidReplayScenario.TimeMode.ABSOLUTE_EPOCH_MS ->
                    event.eventTimestampMs
            }
            futures += scheduler.schedule({
                if (activeReplay?.replayId != replayId) return@schedule
                injectEvent(event, droneTimestampMs, clients)
            }, delayMs, TimeUnit.MILLISECONDS)
        }
        val completionDelayMs = ((events.maxOf { it.eventTimestampMs } - anchorTimestampMs) / speed).toLong().coerceAtLeast(0L) + 250L
        futures += scheduler.schedule({
            if (activeReplay?.replayId != replayId) return@schedule
            activeReplay = null
            mainHandler.post {
                CaltopoClient.ShowToast("RID replay '${scenario.scenarioName}' completed.")
            }
        }, completionDelayMs, TimeUnit.MILLISECONDS)

        CaltopoClient.CTDebug(TAG, "startReplayInternal(): scheduled ${events.size} point(s) across ${clients.size} drone(s) for '${scenario.scenarioName}' at ${scenario.speedMultiplier}x")
        return true to "Started RID replay '${scenario.scenarioName}' (${events.size} points, ${scenario.drones.size} drones)."
    }

    private fun flattenScenarioEvents(scenario: RidReplayScenario): List<ReplayEvent> {
        val events = ArrayList<ReplayEvent>()
        scenario.drones.forEach { drone ->
            drone.points.forEach { point ->
                events += ReplayEvent(drone, point, point.tMs)
            }
        }
        return events.sortedBy { it.eventTimestampMs }
    }

    private fun prepareReplayMetadata(
        drones: List<RidReplayScenario.DroneTrack>,
        clients: ConcurrentHashMap<String, CaltopoClient>
    ) {
        for (drone in drones) {
            val client = clients.getOrPut(drone.remoteId) { CaltopoClient(drone.remoteId) }
            val mappedId = drone.mappedId.ifBlank { drone.remoteId }
            if (drone.org.isNotBlank() || drone.model.isNotBlank() || drone.mappedId.isNotBlank()) {
                CaltopoClient.SaveDroneSpecConfirmation(
                    drone.remoteId,
                    drone.org,
                    drone.model,
                    drone.owner,
                    mappedId
                )
            }
            val ds = CaltopoClient.GetDroneSpec(drone.remoteId) ?: continue
            if (drone.owner.isNotBlank()) ds.setOwner(drone.owner)
            ds.setLastPositionTelemetry(null)
            clients[drone.remoteId] = client
        }
    }

    private fun injectEvent(
        event: ReplayEvent,
        droneTimestampMs: Long,
        clients: ConcurrentHashMap<String, CaltopoClient>
    ) {
        val client = clients.getOrPut(event.drone.remoteId) { CaltopoClient(event.drone.remoteId) }
        val ds = CaltopoClient.GetDroneSpec(event.drone.remoteId) ?: return
        ds.setLastPositionTelemetry(
            if (event.point.gsKnots != null || event.point.trackDeg != null || event.point.altitudeRateFpm != null) {
                CtDroneSpec.PositionTelemetry(
                    event.point.altitudeRateFpm,
                    event.point.gsKnots,
                    event.point.trackDeg
                )
            } else null
        )
        if (event.drone.owner.isNotBlank()) ds.setOwner(event.drone.owner)
        client.newWaypoint(
            event.point.lat,
            event.point.lng,
            event.point.altM,
            droneTimestampMs,
            event.drone.transport,
            event.point.airborne ?: event.drone.airborne
        )
    }
}
