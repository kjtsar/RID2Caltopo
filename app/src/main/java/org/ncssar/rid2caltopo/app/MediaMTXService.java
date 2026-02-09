package org.ncssar.rid2caltopo.app;

import static android.system.OsConstants.SIGRTMAX;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import static java.lang.Thread.sleep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Process;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.MediaMTXBootstrap;
import org.ncssar.rid2caltopo.data.MediaMTXNative;
import org.ncssar.rid2caltopo.data.MediaMTXStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;


public class MediaMTXService extends Service {
    private static final String TAG = "MediaMTXService";
    private static int MEDIA_MTX_NOTIFICATION_ID = 1;
    private static boolean listenersRegistered = false;
    private static int processPid = 0;

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
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, "streaming")
                .setContentTitle("Drone Video Relay")
                .setContentText("Streaming active")
                .setSmallIcon(R.drawable.earth)
                .build();

        startForeground(MEDIA_MTX_NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            CTDebug(TAG, "MediaMTXService shutting down.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        CTDebug(TAG, "MediaMTXService.onStartCommand()");

        if (processPid != 0 && processPid != Process.myPid()) {
            CTInfo(TAG, "MediaMTX already running in pid " + processPid);
            return START_NOT_STICKY;
        }
        if (!listenersRegistered) {
            MediaMTXBootstrap.init();
            listenersRegistered = true;
        }

        processPid = Process.myPid();
        CTDebug(TAG, "MediaMTX service started in pid " + processPid);

        try {
            File bin = extractAsset("mediamtx");
            File cfg = extractAsset("mediamtx.yml");
            CTDebug(TAG, "Starting MediaMTX Server...");

            int rc = MediaMTXNative.start(bin.getAbsolutePath(), cfg.getAbsolutePath());
            if (rc != 0) {
                CTError(TAG, "MediaMTX failed to start");
                stopSelf();
            }
        } catch (Exception e) {
            CTError(TAG, "MediaMTX_Start() raised", e);
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        MediaMTXNative.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "streaming",
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
}
