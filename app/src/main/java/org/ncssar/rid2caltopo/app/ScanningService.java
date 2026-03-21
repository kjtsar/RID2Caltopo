
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.app;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.ncssar.rid2caltopo.R;
import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.SimpleTimer;
import org.opendroneid.android.bluetooth.BluetoothScanner;
import org.opendroneid.android.bluetooth.OpenDroneIdDataManager;
import org.opendroneid.android.bluetooth.WiFiScanner;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;

/* This foreground Service required to receive Bluetooth and Wifi
   updates when the app is backgrounded/paused.
 */

public class ScanningService extends Service {
    private static final String TAG = "ScanningService";
    private static final String CHANNEL_ID = "OpenDroneIdScanner";
    private static final String CHANNEL_NAME = "OpenDroneId Scanner Service";
    private static final int NOTIFICATION_ID = 1;
    public static final SimpleTimer ScannerUptime = new SimpleTimer();;
    private BluetoothScanner btScanner;
    private WiFiScanner wiFiScanner;
    private boolean scanning = false;
    private static Context AppContext = R2CApplication.getAppCtxt();
    private static OpenDroneIdDataManager DataManager = new OpenDroneIdDataManager(null);
    public static long GetStartTimeInMsec() {return ScannerUptime.getStartTimeInMsec();}
    public void startScanning() {
        if (scanning) {
            CTError(TAG, "startScanning(): ignoring start request while running.");
            return;
        }
        scanning = true;
        if (null == AppContext) AppContext = R2CApplication.getAppCtxt();
        CTDebug(TAG, String.format(Locale.US, "startScanning(): ScanningService 0x%x", this.hashCode()));
        wiFiScanner = new WiFiScanner(AppContext, DataManager);
        wiFiScanner.startScan();

        btScanner = new BluetoothScanner(AppContext, DataManager);
        btScanner.startScan();
    }

    public void stopScanning() {
        if (!scanning) {
            CTError(TAG, "stopScanning(): Ignoring request to stop when idle");
            return;
        }
        CTDebug(TAG, String.format(Locale.US, "stopScanning(): ScanningService 0x%x", this.hashCode()));
        wiFiScanner.stopScan();
        btScanner.stopScan();
        scanning = false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CTDebug(TAG, String.format(Locale.US,
                "onCreate(): Starting ScanningService:0x%x in pid:%d",
                this.hashCode(), Process.myPid()));

        if (null == AppContext) AppContext = R2CApplication.getAppCtxt();
        if (null == AppContext || null == DataManager) {
            CTError(TAG, "onCreate() missing required app context - stopping service.");
            stopSelf();
            return;
        }

        CTDebug(TAG, String.format(Locale.US, "onCreate(): Context:0x%x DataManager:0x%x",
                AppContext.hashCode(), DataManager.hashCode()));

        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID,
                CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notManager = getSystemService(NotificationManager.class);
        notManager.createNotificationChannel(serviceChannel);
    }
    @NonNull
    public static String UpTime() {
        return ScannerUptime.durationAsString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        CTDebug(TAG, String.format(Locale.US,
                "onDestroy(): ScanningService 0x%x", this.hashCode()));
        if (scanning) {
            stopScanning();
        }
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
    }

    @Override
    public
    void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            CTDebug(TAG, "ScanningService shutting down.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (null == AppContext) {
            AppContext = R2CApplication.getAppCtxt();
            if (null == AppContext) {
                return START_REDELIVER_INTENT;
            }
        }
        CTDebug(TAG, String.format(Locale.US, "onStartCommand(): AppContext:0x%x", AppContext.hashCode()));


        intent = new Intent(AppContext, R2CActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(AppContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(AppContext, CHANNEL_ID)
                .setContentTitle("OpenDroneID Scanning Service")
                .setContentText("Scanning for remoteID broadcasts on Bluetooth and Wireless interfaces")
                .setSmallIcon(R.drawable.ic_notification_drone)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .build();

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        startScanning();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        CTDebug(TAG, "onTaskRemoved()");
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
    }
}
