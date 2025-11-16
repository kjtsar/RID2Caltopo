/*
 * Copyright (C) 2025 Ken Taylor
 * Copyright (C) 2021 Skydio
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * This file replaces the WiFiBeaconScanner and WiFiNaNScanner modules from
 * the opendroneid/receiver-android project that relied on depreciated
 * API.
 */

package org.opendroneid.android.bluetooth;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.IdentityChangedListener;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.CtDroneSpec;

public class WiFiScanner {
    private static final int CIDLen = 3;
    private static final int DriStartByteOffset = 4;
    private static final int[] DRI_CID = {0xFA, 0x0B, 0xBC};
    private static final int VendorTypeLen = 1;
    private static final int VendorTypeValue = 0x0D;
    private WifiAwareManager wifiAwareManager;
    private WifiAwareSession wifiAwareSession;
    private final OpenDroneIdDataManager dataManager;
    private WifiManager wifiManager;
    private int scanSuccess;
    private int scanFails;
    Context context;
    final String startTime;
    private  BroadcastReceiver wifiScanReceiver;

    private WifiManager.ScanResultsCallback scanResultsCallback;
    private static final String TAG = WiFiScanner.class.getSimpleName();

    public WiFiScanner(Context context, OpenDroneIdDataManager dataManager) {
        this.dataManager = dataManager;
        this.startTime = getCurrTimeStr();
        this.context = context;

        wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scanResultsCallback = new WifiManager.ScanResultsCallback() {
                @Override
                public void onScanResultsAvailable() {
                    try {
                        List<ScanResult> wifiList = wifiManager.getScanResults();
                        for (ScanResult scanResult : wifiList) {
                            try {
                                handleResult(scanResult);
                            } catch (Exception e) {
                                CTError(TAG, "oSRA():handleResult() raised:\n", e);
                            }
                        }
                    } catch (SecurityException se) {
                        CTError(TAG, "oSRA(): getScanResults() raised:\n", se);
                    }
                }
            };
        } else {
            wifiScanReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    boolean success = intent.getBooleanExtra(
                            WifiManager.EXTRA_RESULTS_UPDATED, false);
                    if (success) {
                        scanSuccess++;
                        try {
                            List<ScanResult> wifiList = wifiManager.getScanResults();
                            for (ScanResult scanResult : wifiList) {
                                try {
                                    handleResult(scanResult);
                                } catch (NoSuchFieldException | IllegalAccessException e) {
                                    CTError(TAG, "oR(): handleResult() raised:\n", e);
                                }
                            }
                        } catch (SecurityException se) {
                            CTError(TAG, "oR(): getScanResults() raised:\n", se);
                        }
                        // scan failure handling
                        scanFails++;
                    }
                }
            };
        }
    }
    void processRemoteIdVendorIE(ScanResult scanResult, ByteBuffer buf) {
        if (buf.remaining() < 30)
            return;
        byte[] dri_CID = new byte[CIDLen];
        byte[] arr = new byte[buf.remaining()];
        buf.get(dri_CID, 0, CIDLen);
        byte[] vendorType = new byte[VendorTypeLen];
        buf.get(vendorType);
        if ((dri_CID[0] & 0xFF) == DRI_CID[0] && (dri_CID[1] & 0xFF) == DRI_CID[1] &&
                (dri_CID[2] & 0xFF) == DRI_CID[2] && vendorType[0] == VendorTypeValue) {
            buf.position(DriStartByteOffset);
            buf.get(arr, 0, buf.remaining());
            long timeNano = SystemClock.elapsedRealtimeNanos();
            CtDroneSpec.TransportTypeEnum transportType = CtDroneSpec.TransportTypeEnum.WIFI;
            dataManager.receiveDataWiFiBeacon(arr, scanResult.BSSID, scanResult.BSSID.hashCode(),
                    scanResult.level, timeNano, transportType);
        }
    }

    void handleResult(ScanResult scanResult) throws NoSuchFieldException, IllegalAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // On earlier Android APIs, the information element field is hidden.
            // Use reflection to access it.
            Object value = ScanResult.class.getField("informationElements").get(scanResult);
            ScanResult.InformationElement[] elements = (ScanResult.InformationElement[]) value;
            if (elements == null)
                return;
            for (ScanResult.InformationElement element : elements) {
                if (element == null)
                    continue;
                Object valueId = element.getClass().getField("id").get(element);
                if (valueId == null)
                    continue;
                int id = (int) valueId;
                if (id == 221) {
                    Object valueBytes = element.getClass().getField("bytes").get(element);
                    if (valueBytes == null)
                        continue;
                    ByteBuffer buf = ByteBuffer.wrap(((byte[]) valueBytes)).asReadOnlyBuffer();
                    processRemoteIdVendorIE(scanResult, buf);
                }
            }
        } else {
            for (ScanResult.InformationElement element : scanResult.getInformationElements()) {
                if (element != null && element.getId() == 221) {
                    ByteBuffer buf = element.getBytes();
                    processRemoteIdVendorIE(scanResult, buf);
                }
            }
        }
    }

    public void startScan() {
        CaltopoClient.CTDebug(TAG, "Starting WiFi beacon scanning");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager.registerScanResultsCallback(context.getMainExecutor(), scanResultsCallback);
        } else {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            context.registerReceiver(wifiScanReceiver, intentFilter);
        }

        wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
        if (wifiAwareManager != null && wifiAwareManager.isAvailable()) {
            try {
                CaltopoClient.CTDebug(TAG, "Starting WiFi NaN scanning");
                wifiAwareManager.attach(attachCallback, identityChangedListener, null);
            } catch (Exception e) {
                CTError(TAG, "wifiAwareManager().attach() raised:\n", e);
            }
        }
    }

    public void stopScan() {
        CaltopoClient.CTInfo(TAG, "Stopping WiFi beacon scanning");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager.unregisterScanResultsCallback(scanResultsCallback);
        } else {
            context.unregisterReceiver(wifiScanReceiver);
        }

        if (wifiAwareManager != null && wifiAwareManager.isAvailable() && wifiAwareSession != null) {
            CaltopoClient.CTInfo(TAG, "WiFi NaN closing");
            wifiAwareSession.close();
        }
    }

    private String getCurrTimeStr() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        return sdf.format(new Date());
    }


    private final AttachCallback attachCallback = new AttachCallback() {
        @Override
        public void onAttached(WifiAwareSession session) {
            wifiAwareSession = session;
            SubscribeConfig config = new SubscribeConfig.Builder()
                    .setServiceName("org.opendroneid.remoteid")
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    CTError(TAG,"onAttached: Missing NEARBY_WIFI_DEVICES permission");
                    return;
                }
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                CTError(TAG, "onAttached: Missing ACCESS_FINE_LOCATION permission");
                return;
            }

            CaltopoClient.CTDebug(TAG,"onAttached(): wifiAwareSession starting subscription.");
            wifiAwareSession.subscribe(config, new DiscoverySessionCallback() {
                @Override
                public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
                    CaltopoClient.CTInfo(TAG, "onSubscribeStarted");
                }

                @Override
                public void onMessageReceived(PeerHandle peerHandle, byte[] message)
                {
                    CaltopoClient.CTInfo(TAG, "onMessageReceived: " + message.length + ": " + Arrays.toString(message));
                }
                @Override
                public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                    CaltopoClient.CTInfo(TAG, "onServiceDiscovered: " + serviceSpecificInfo.length + ": " + Arrays.toString(serviceSpecificInfo));

                    CtDroneSpec.TransportTypeEnum transportType = CtDroneSpec.TransportTypeEnum.WNAN;
                    long timeNano = SystemClock.elapsedRealtimeNanos();
                    dataManager.receiveDataNaN(serviceSpecificInfo, peerHandle.hashCode(), timeNano, transportType);
                }

                @Override
                public void onSessionConfigFailed() {
                    CTError(TAG, "onSessionConfigFailed()");

                }
            }, null);
        }

        @Override
        public void onAttachFailed() {
            CTError(TAG, "wifiAware onAttachFailed. Code to properly handle this must be added.");
        }
    };

    private final IdentityChangedListener identityChangedListener = new IdentityChangedListener() {
        @Override
        public void onIdentityChanged(byte[] mac) {
            Byte[] macAddress = new Byte[mac.length];
            int i = 0;
            for (byte b: mac)
                macAddress[i++] = b;
            CaltopoClient.CTInfo(TAG, "identityChangedListener: onIdentityChanged. MAC: " + Arrays.toString(macAddress));
        }
    };

}

