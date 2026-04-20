package org.ncssar.rid2caltopo.app;

import static android.system.OsConstants.SIGRTMAX;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import static java.lang.Thread.sleep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.os.Process;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.MediaMTXBootstrap;
import org.ncssar.rid2caltopo.data.MediaMTXConfig;
import org.ncssar.rid2caltopo.data.MediaMTXEvent;
import org.ncssar.rid2caltopo.data.MediaMTXNative;
import org.ncssar.rid2caltopo.data.MediaMTXRecordingSync;
import org.ncssar.rid2caltopo.data.MediaMTXStatus;
import org.ncssar.rid2caltopo.data.MediaMTXStructuredDispatcher;
import org.ncssar.rid2caltopo.data.CaltopoClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import kotlin.Unit;


public class MediaMTXService extends Service {
    private static final String TAG = "MediaMTXService";
    private static final String ACTION_STOP_SERVICE = "STOP_SERVICE";
    private static final String ACTION_RESTART_SERVICE = "RESTART_SERVICE";
    private static final String CHANNEL_ID = "streaming";
    private static final int MEDIA_MTX_NOTIFICATION_ID = 2;
    private static boolean listenersRegistered = false;
    private static boolean recordingListenerRegistered = false;
    private static int processPid = 0;
    private static volatile boolean serviceRunning = false;
    public static boolean IsRunning() { return serviceRunning; }

    public static void onNativeProcessExit(int pid, int status, int signaled) {
        CTDebug(TAG, "MediaMTX exited pid=" + pid +
                " status=" + status +
                " signaled=" + signaled);
        String description;
        if (0 != signaled) {
            if (signaled == SIGRTMAX) {
                description = "terminated by Android";
            } else {
                description = "terminated by signal " + signaled;
            }
        } else {
            description = "exited with status " + status;
        }
        MediaMTXStatus.onServerExited(description);
    }

    public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        createNotificationChannel();
        Intent launchIntent = new Intent(this, R2CActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                1,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Drone Video Relay")
                .setContentText("Streaming active")
                .setSmallIcon(R.drawable.ic_notification_drone)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .build();

        startForeground(MEDIA_MTX_NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_SERVICE.equals(action)) {
            CTDebug(TAG, "MediaMTXService shutting down.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART_SERVICE.equals(action)) {
            CTDebug(TAG, "MediaMTXService restarting native server.");
            ensureListenersRegistered();
            processPid = Process.myPid();
            restartNativeServer();
            return START_NOT_STICKY;
        }

        CTDebug(TAG, "MediaMTXService.onStartCommand()");

        if (processPid != 0 && processPid != Process.myPid()) {
            CTInfo(TAG, "MediaMTX already running in pid " + processPid);
            return START_NOT_STICKY;
        }
        ensureListenersRegistered();

        processPid = Process.myPid();
        CTDebug(TAG, "MediaMTX service started in pid " + processPid);

        startNativeServer();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        processPid = 0;
        try {
            stopForeground(true);
        } catch (Exception e) {
            CTDebug(TAG, "onDestroy(): stopForeground ignored: " + e.getMessage());
        }
        new Thread(MediaMTXNative::stop, "mediamtx-native-stop").start();
        // Run syncAll on a background thread to avoid ForegroundServiceDidNotStopInTimeException.
        // Android 14+ enforces a ~20 s shutdown timeout on the main thread; copying large video
        // files to the SAF archive can easily exceed that.  Any fragments not synced here will
        // be picked up by the syncAll() call at the next startNativeServer() invocation.
        final Context appContext = getApplicationContext();
        new Thread(() -> MediaMTXRecordingSync.syncAll(appContext, null),
                "mediamtx-sync-shutdown").start();
        super.onDestroy();
    }

    private void ensureListenersRegistered() {
        if (!listenersRegistered) {
            MediaMTXBootstrap.init();
            listenersRegistered = true;
        }
        if (!recordingListenerRegistered) {
            final Context appContext = getApplicationContext();
            MediaMTXStructuredDispatcher.addListener(event -> {
                if (!CaltopoClient.GetCaptureVideoStreamsFlag()) return Unit.INSTANCE;
                if (event instanceof MediaMTXEvent.StreamStopped) {
                    MediaMTXRecordingSync.syncAll(appContext, ((MediaMTXEvent.StreamStopped) event).getPath());
                }
                return Unit.INSTANCE;
            });
            recordingListenerRegistered = true;
        }
    }

    private void restartNativeServer() {
        MediaMTXRecordingSync.syncAll(getApplicationContext(), null);
        MediaMTXNative.stop();
        startNativeServer();
    }

    private void startNativeServer() {
        try {
            MediaMTXRecordingSync.syncAll(getApplicationContext(), null);
            File bin = extractAsset("mediamtx");
            File cfg = buildConfigAsset("mediamtx.yml");
            CTDebug(TAG, "Starting MediaMTX Server...");

            int rc = MediaMTXNative.start(bin.getAbsolutePath(), cfg.getAbsolutePath());
            if (rc != 0) {
                CTError(TAG, "MediaMTX failed to start");
                stopSelf();
            }
        } catch (Exception e) {
            CTError(TAG, "MediaMTX_Start() raised", e);
        }
    }

    public static void requestRestart(Context context) {
        Intent restartIntent = new Intent(context, MediaMTXService.class);
        restartIntent.setAction(ACTION_RESTART_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(restartIntent);
        } else {
            context.startService(restartIntent);
        }
    }

    public static void requestStop(Context context) {
        Intent stopIntent = new Intent(context, MediaMTXService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        ContextCompat.startForegroundService(context, stopIntent);
    }

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForeground(true);
        stopSelf();
        CaltopoClient.ShutdownAsync();
        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void checkFile(File f) {
        CTDebug(TAG, f
                + " exists=" + f.exists()
                + " r=" + f.canRead()
                + " w=" + f.canWrite()
                + " x=" + f.canExecute());
    }

    private File extractAsset(String assetName) throws IOException {

        File etcDir = new File(getFilesDir(), "etc");
        checkFile(etcDir);
        etcDir.mkdirs();
        File outFile = new File(etcDir, assetName);

        long bytesWritten = 0;
        CTDebug(TAG, "extractAsset(): building " + outFile);
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytesWritten += read;
                out.write(buffer, 0, read);
            }
            outFile.setExecutable(true, false);
            outFile.setReadable(true, false);
            outFile.setWritable(true, true);
        }
        CTDebug(TAG, String.format(Locale.US, "Wrote %.3f KB into %s", bytesWritten / 1024F, outFile));
        checkFile(outFile);
        return outFile;
    }

    private File buildConfigAsset(String assetName) throws IOException {
        File etcDir = new File(getFilesDir(), "etc");
        checkFile(etcDir);
        etcDir.mkdirs();
        File outFile = new File(etcDir, assetName);
        String baseConfig = loadAssetText(assetName);
        File recordingRoot = MediaMTXRecordingSync.getRecordingStagingDir(getApplicationContext());
        if (!recordingRoot.exists() && !recordingRoot.mkdirs()) {
            CTError(TAG, "Unable to create MediaMTX recording staging dir " + recordingRoot);
        }
        String runtimeConfig = MediaMTXConfig.buildRuntimeConfig(
                baseConfig,
                CaltopoClient.GetCaptureVideoStreamsFlag(),
                recordingRoot
        );
        try (OutputStream out = new FileOutputStream(outFile)) {
            out.write(runtimeConfig.getBytes(StandardCharsets.UTF_8));
        }
        outFile.setReadable(true, false);
        outFile.setWritable(true, true);
        checkFile(outFile);
        return outFile;
    }

    private String loadAssetText(String assetName) throws IOException {
        try (InputStream in = getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
