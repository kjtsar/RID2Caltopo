/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DelayedExec {
    private static final String TAG = "DelayedExec";
    private Runnable runnable;
    private Handler handler;
    private Runnable dispatcherRunnable;
    private long repeatMsec;  // zero == no repeat.
    private boolean running;
    private final boolean preferMainLooper;
    private ScheduledFuture<?> scheduledFuture;

    // Fallback executor used when Android Looper is unavailable (unit-test environments).
    private static final ScheduledExecutorService testExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DelayedExec-test");
                t.setDaemon(true);
                return t;
            });

    public DelayedExec() {
        this(true);
    }

    public DelayedExec(boolean preferMainLooper) {
        this.preferMainLooper = preferMainLooper;
    }

    public static void RunAfterDelayInMsec(Runnable aRunnable, long delayInMsec) {
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null) {
            new Handler(mainLooper).postDelayed(aRunnable, delayInMsec);
        } else {
            // No Android Looper — running in a unit-test JVM.  Use a thread-based fallback.
            testExecutor.schedule(aRunnable, delayInMsec, TimeUnit.MILLISECONDS);
        }
    }

    private void dispatcher() {
        if (running) {
            boolean savedRunState = running = (0 != repeatMsec);
            try {
                runnable.run();
            } catch (Exception e) {
                CTError(TAG, String.format(Locale.US,
                        "runnable(%s) raised (terminating any repeat): ",
                        runnable.toString()), e);
                repeatMsec = 0;
                running = false;
            }

            if (savedRunState && !running) {
                CTInfo(TAG, String.format(Locale.US,
                        "'%s' stopped by runnable.", runnable.toString()));
                return;
            } else if (!savedRunState) {
                CTInfo(TAG, String.format(Locale.US,
                        "'%s' restarted by runnable.", runnable.toString()));
                return;
            }

            if (savedRunState) {
                schedule(repeatMsec);
            } else {
                CTInfo(TAG, String.format(Locale.US,
                        "'%s' completed single delayed operation.", runnable.toString()));
                running = false;
            }
        }
    }


    public boolean isRunning() {
        return running;
    }

    public void start(Runnable runnable, long delayInMsec, long repeatDelayInMsec) {
        if (null == dispatcherRunnable) {
            dispatcherRunnable = this::dispatcher;
        }
        this.runnable = runnable;
        stopScheduledFuture();
        if (handler != null) {
            handler.removeCallbacks(dispatcherRunnable);
        }
        repeatMsec = repeatDelayInMsec;
        running = true;
        ensureHandlerIfNeeded();
        schedule(delayInMsec);
    }

    public void stop() {
        if (handler != null && dispatcherRunnable != null) {
            handler.removeCallbacks(dispatcherRunnable);
        }
        stopScheduledFuture();
        repeatMsec = 0;
        running = false;
    }

    protected void finalize() {
        if (handler != null && dispatcherRunnable != null) {
            handler.removeCallbacks(dispatcherRunnable);
        }
        stopScheduledFuture();
        running = false;
    }

    private void ensureHandlerIfNeeded() {
        if (!preferMainLooper || handler != null) return;
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null) {
            handler = new Handler(mainLooper);
        }
    }

    private void schedule(long delayInMsec) {
        if (handler != null) {
            handler.postDelayed(dispatcherRunnable, delayInMsec);
        } else {
            scheduledFuture = testExecutor.schedule(this::dispatcher, delayInMsec, TimeUnit.MILLISECONDS);
        }
    }

    private void stopScheduledFuture() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }
}
