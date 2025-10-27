package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

public class DelayedExec {
    private static final String TAG = "DelayedExec";
    private Runnable runnable;
    private Handler handler;
    private long repeatMsec;  // zero == no repeat.
    private boolean running;

    public DelayedExec() {
    }

    public static void RunAfterDelayInMsec(Runnable aRunnable, long delayInMsec) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(aRunnable, delayInMsec);
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
                handler.postDelayed(this::dispatcher, repeatMsec);
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
        if (running) {
            handler.removeCallbacks(this.runnable);
        } else if (null == handler) {
            handler = new Handler(Looper.getMainLooper());
            this.runnable = runnable;
        }
        repeatMsec = repeatDelayInMsec;
        running = true;
        handler.postDelayed(this::dispatcher, delayInMsec);
    }

    public void stop() {
        if (running) {
            handler.removeCallbacks(this.runnable);
            repeatMsec = 0;
        }
        running = false;
    }

    protected void finalize() {
        if (running) handler.removeCallbacks(this.runnable);
        running = false;
    }
}