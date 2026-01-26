package org.ncssar.rid2caltopo.app;


import static androidx.core.app.ServiceCompat.startForeground;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Process;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.MediaMTXNative;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

public class MediaMTXService extends Service {

    private static final String TAG = "MediaMtxService";
    private static int processPid = 0;

    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (processPid != 0 && processPid != Process.myPid()) {
            CTInfo(TAG, "MediaMTX already running in pid " + processPid);
            return START_NOT_STICKY;
        }
        processPid = Process.myPid();
        CTDebug(TAG, "MediaMTX service started in pid " + processPid);
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, "streaming")
                .setContentTitle("Drone Video Relay")
                .setContentText("Streaming active")
                .setSmallIcon(R.drawable.earth)
                .build();

        startForeground(1, notification);

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
