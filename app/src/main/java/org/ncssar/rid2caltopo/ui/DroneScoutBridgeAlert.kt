package org.ncssar.rid2caltopo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import org.ncssar.rid2caltopo.app.ScanningService
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.video.StreamFlightActivityRegistry
import org.ncssar.rid2caltopo.video.ffmpeg.StreamCameraTelemetryRegistry
import org.opendroneid.android.bluetooth.DroneScoutBridgeLossAnnouncementGate
import org.opendroneid.android.bluetooth.DroneScoutBridgeMonitor

@Composable
fun DroneScoutBridgeAlertHost() {
    val signal by DroneScoutBridgeMonitor.signal.collectAsState()
    val muted by DroneScoutBridgeMonitor.audioMuted.collectAsState()
    val gate = remember { DroneScoutBridgeLossAnnouncementGate() }

    LaunchedEffect(signal, muted) {
        while (true) {
            val nowMs = System.nanoTime() / 1_000_000L
            val nowWallMs = System.currentTimeMillis()
            val activeRemoteIds = CaltopoClient.GetActiveRemoteIdsSnapshot()
            val allActiveFlightsCoveredByFreshPairedSei = activeRemoteIds.isNotEmpty() &&
                activeRemoteIds.all { remoteId ->
                    val activity = StreamFlightActivityRegistry.seiActivityForRemoteId(remoteId)
                    activity.paired && activity.lastSeiActivityAtMs > 0L &&
                        nowWallMs - activity.lastSeiActivityAtMs in
                        0..StreamCameraTelemetryRegistry.DEFAULT_MAX_AGE_MS
                }
            if (gate.shouldAnnounce(
                    monitoringActive = shouldMonitorDroneScoutBridgeAlerts(
                        scannerRunning = ScanningService.IsRunning(),
                        activeFlightCount = activeRemoteIds.size,
                        allActiveFlightsCoveredByFreshPairedSei =
                            allActiveFlightsCoveredByFreshPairedSei,
                    ),
                    lastPingAtMs = signal?.lastSeenMonotonicMs,
                    nowMs = nowMs,
                    muted = muted,
                )
            ) {
                val lastPingAgeMs = signal?.let { nowMs - it.lastSeenMonotonicMs }
                CaltopoClient.CTWarn(
                    "DroneScoutBridge",
                    "Bridge Not Detected alert: lastPingAgeMs=${lastPingAgeMs ?: "never"} " +
                        "thresholdMs=${DroneScoutBridgeMonitor.LOSS_ANNOUNCEMENT_AFTER_MS}"
                )
                SpokenWarningCenter.requestWarning(
                    kind = SpokenWarningKind.BridgeNotDetected,
                    sourceKey = "dronescout-bridge",
                )
            }
            delay(1_000L)
        }
    }
}

internal fun shouldMonitorDroneScoutBridgeAlerts(
    scannerRunning: Boolean,
    activeFlightCount: Int,
    allActiveFlightsCoveredByFreshPairedSei: Boolean = false,
): Boolean = scannerRunning && activeFlightCount > 0 && !allActiveFlightsCoveredByFreshPairedSei
