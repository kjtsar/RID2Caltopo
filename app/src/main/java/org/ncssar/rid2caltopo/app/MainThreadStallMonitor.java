/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.CaltopoMap;

import java.util.Locale;

public final class MainThreadStallMonitor {
    private static final String TAG = "MainThreadStallMonitor";
    private static final long HEARTBEAT_INTERVAL_MS = 1000L;
    private static final long STALL_THRESHOLD_MS = 1000L;

    private static final Handler MainHandler = new Handler(Looper.getMainLooper());
    private static boolean started = false;
    private static long nextExpectedAtUptimeMs = 0L;

    private MainThreadStallMonitor() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        nextExpectedAtUptimeMs = SystemClock.uptimeMillis() + HEARTBEAT_INTERVAL_MS;
        MainHandler.postDelayed(MainThreadStallMonitor::heartbeat, HEARTBEAT_INTERVAL_MS);
        CaltopoClient.CTDebug(TAG, "Started main-thread stall monitor.");
    }

    public static synchronized void stop() {
        if (!started) return;
        started = false;
        MainHandler.removeCallbacksAndMessages(null);
        CaltopoClient.CTDebug(TAG, "Stopped main-thread stall monitor.");
    }

    private static void heartbeat() {
        long nowUptimeMs = SystemClock.uptimeMillis();
        long delayMs = nowUptimeMs - nextExpectedAtUptimeMs;
        if (delayMs >= STALL_THRESHOLD_MS) {
            CaltopoClient.CTWarn(TAG, String.format(Locale.US,
                    "main thread stall delayMs=%d intervalMs=%d thread=%s scanningRunning=%b scannerUptime=%s " +
                            "ridIngestQueueDepth=%d ridIngestDropped=%d activeFlights=%d mapStatus=%s",
                    delayMs,
                    HEARTBEAT_INTERVAL_MS,
                    Thread.currentThread().getName(),
                    ScanningService.IsRunning(),
                    ScanningService.UpTime(),
                    ScanningService.GetRidIngestQueueDepth(),
                    ScanningService.GetDroppedRidIngestPacketCount(),
                    CaltopoClient.GetActiveFlightCountSnapshot(),
                    CaltopoMap.GetMapStatus()));
        }
        synchronized (MainThreadStallMonitor.class) {
            if (!started) return;
            nextExpectedAtUptimeMs = nowUptimeMs + HEARTBEAT_INTERVAL_MS;
            MainHandler.postDelayed(MainThreadStallMonitor::heartbeat, HEARTBEAT_INTERVAL_MS);
        }
    }
}
