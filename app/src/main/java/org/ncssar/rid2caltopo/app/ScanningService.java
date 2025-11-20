package org.ncssar.rid2caltopo.app;

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

    private R2CActivity mAppActivity;
    private Context mAppContext;
    private OpenDroneIdDataManager mDataManager;

    public static long GetStartTimeInMsec() {return ScannerUptime.getStartTimeInMsec();}
    public void startScanning() {
        if (scanning) {
            CTError(TAG, "startScanning(): ignoring start request while running.");
            return;
        }
        scanning = true;
        CaltopoClient.CTDebug(TAG, String.format(Locale.US, "startScanning(): ScanningService 0x%x", this.hashCode()));
        wiFiScanner = new WiFiScanner(mAppContext, mDataManager);
        wiFiScanner.startScan();

        btScanner = new BluetoothScanner(mAppContext, mDataManager);
        btScanner.startScan();
    }

    public void stopScanning() {
        if (!scanning) {
            CTError(TAG, "stopScanning(): Ignoring request to stop when idle");
            return;
        }
        CaltopoClient.CTDebug(TAG, String.format(Locale.US, "stopScanning(): ScanningService 0x%x", this.hashCode()));
        wiFiScanner.stopScan();
        btScanner.stopScan();
        scanning = false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CaltopoClient.CTDebug(TAG, String.format(Locale.US,
                "onCreate(): Starting ScanningService:0x%x in pid:%d",
                this.hashCode(), Process.myPid()));
        mAppActivity = R2CActivity.getR2CActivity();
        mAppContext = getApplicationContext();
        mDataManager = R2CActivity.getDataManager();

        if (null == mAppActivity || null == mAppContext || null == mDataManager) {
            CTError(TAG, "onCreate() missing required app context - terminating.");
            System.exit(3);
            return;
        }

        CaltopoClient.CTDebug(TAG, String.format(Locale.US, "onCreate(): appActivity:0x%x dataManager: 0x%x",
                mAppActivity.hashCode(), mDataManager.hashCode()));

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
        CaltopoClient.CTDebug(TAG, String.format(Locale.US,
                "onDestroy(): ScanningService 0x%x", this.hashCode()));
        stopScanning();
        super.onDestroy();
        // FIXME: This shouldn't be necessary if we release all references...
        Process.killProcess(Process.myPid());
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
        if (null == mAppActivity) {
            mAppActivity = R2CActivity.getR2CActivity();
            if (null == mAppActivity) {
                return START_REDELIVER_INTENT;
            }
        }
        CaltopoClient.CTDebug(TAG, String.format(Locale.US, "onStartCommand(): mAppActivity 0x%x", mAppActivity.hashCode()));


        /* FIXME: No matter what I've tried here, Android will fire up a new instance of the
            R2C/DebugActivity class - rather than just bring the existing instance to the front.
            As a result, DebugActivity.onCreate() checks the instance id to see if it is the
            original, and if it isn't it just exits.
         */
        intent = new Intent(mAppActivity, R2CActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(mAppActivity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(mAppActivity, CHANNEL_ID)
                .setContentTitle("OpenDroneID Scanning Service")
                .setContentText("Scanning for remoteID broadcasts on Bluetooth and Wireless interfaces")
                .setSmallIcon(R.drawable.r2c)
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
        CaltopoClient.CTDebug(TAG, "onTaskRemoved()");
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
