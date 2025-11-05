/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import androidx.core.app.ActivityCompat;

import org.ncssar.rid2caltopo.data.CaltopoClient;
import org.ncssar.rid2caltopo.data.CtDroneSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BluetoothScanner {
    private static final String TAG = "BluetoothScanner";

    private final OpenDroneIdDataManager dataManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private final Context context;

    public BluetoothScanner(Context context, OpenDroneIdDataManager dataManager) {
        this.context = context;
        this.dataManager = dataManager;
        bluetoothAdapter = getBluetoothAdapter(context);
    }

    public static BluetoothAdapter getBluetoothAdapter(Context context) {
        Object object = context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (object == null) return null;
        return ((android.bluetooth.BluetoothManager) object).getAdapter();
    }

    private final ScanCallback scanCallback = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord scanRecord = result.getScanRecord();
            if (scanRecord == null)
                return;
            byte[] bytes = scanRecord.getBytes();

            String addr = result.getDevice().getAddress().substring(0, 8);
            int advertiseFlags = scanRecord.getAdvertiseFlags();
            int rssi = result.getRssi();
            String string = String.format(Locale.US, "scan: addr=%s flags=0x%02X rssi=% d, len=%d",
                    addr, advertiseFlags, rssi, bytes != null ? bytes.length : -1);

            CtDroneSpec.TransportTypeEnum transportType = CtDroneSpec.TransportTypeEnum.BT4;
            if (bluetoothAdapter.isLeCodedPhySupported()) {
                if (result.getPrimaryPhy() == BluetoothDevice.PHY_LE_CODED)
                    transportType = CtDroneSpec.TransportTypeEnum.BT5;
            }

            if (null != dataManager) dataManager.receiveDataBluetooth(bytes, result, transportType);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            CaltopoClient.CTInfo(TAG, "onBatchScanResults: " + results);
        }

        @Override
        public void onScanFailed(int errorCode) {
            CaltopoClient.CTError(TAG, "onScanFailed: errorCode is " + errorCode);
        }
    };

    /* OpenDroneID Bluetooth beacons identify themselves by setting the GAP AD Type to
     * "Service Data - 16-bit UUID" and the value to 0xFFFA for ASTM International, ASTM Remote ID.
     * https://www.bluetooth.com/specifications/assigned-numbers/ -> "Generic Access Profile"
     * https://www.bluetooth.com/specifications/assigned-numbers/ -> "16-bit UUIDs"
     * Vol 3, Part B, Section 2.5.1 of the Bluetooth 5.1 Core Specification
     * The AD Application Code is set to 0x0D = Open Drone ID.
     */
    private static final UUID SERVICE_UUID = UUID.fromString("0000fffa-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid SERVICE_pUUID = new ParcelUuid(SERVICE_UUID);
    private static final byte[] OPEN_DRONE_ID_AD_CODE = new byte[]{(byte) 0x0D};

    public void startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            CaltopoClient.CTError(TAG, "Bluetooth scan not supported.");
            return;
        }
        CaltopoClient.CTDebug(TAG, "Basic Bluetooth scan supported.");
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();

        ScanFilter.Builder builder = new ScanFilter.Builder();
        builder.setServiceData(SERVICE_pUUID, OPEN_DRONE_ID_AD_CODE);
        List<ScanFilter> scanFilters = new ArrayList<>();
        scanFilters.add(builder.build());

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        if (bluetoothAdapter.isLeCodedPhySupported() &&
                bluetoothAdapter.isLeExtendedAdvertisingSupported()) {
            CaltopoClient.CTDebug(TAG, "startScan: Enable scanning also for devices advertising on an LE Coded PHY S2 or S8");
            scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setLegacy(false)
                    .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .build();
        }

        if (bluetoothLeScanner != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    CaltopoClient.CTError(TAG, "startScan: Did not get BLUETOOTH_SCAN permission");
                    return;
                }
            }
            CaltopoClient.CTDebug(TAG, "startScan: Calling bluetoothLeScanner.startScan");
            bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback);
        }
    }

    public void stopScan() {
        if (bluetoothLeScanner != null && bluetoothAdapter.isEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    CaltopoClient.CTError(TAG, "stopScan: Did not get BLUETOOTH_SCAN permission");
                    return;
                }
            }
            CaltopoClient.CTDebug(TAG, "Calling bluetoothLeScanner.stopScan().");
            bluetoothLeScanner.stopScan(scanCallback);
        }
    }
}
