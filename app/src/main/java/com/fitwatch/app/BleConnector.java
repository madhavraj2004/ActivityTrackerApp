package com.fitwatch.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BleConnector {

    public interface Callback {
        void onRightStatus(String text);
        void onLeftStatus(String text);
        void onRightPacket(SensorPacket packet);
        void onLeftPacket(SensorPacket packet);
        void onMessage(String text);
    }

    public interface ScanListener {
        void onDeviceFound(BluetoothDevice device);
        void onScanFinished();
    }

    // ✅ CORRECT UUIDs (FROM YOUR ESP32)
    private static final UUID SERVICE_UUID =
            UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");

    private static final UUID CHAR_UUID =
            UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");

    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final BluetoothAdapter adapter;
    private final Context context;
    private Callback callback;

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    private BluetoothGatt rightGatt;
    private BluetoothGatt leftGatt;

    private boolean rightConnecting = false;
    private boolean leftConnecting = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public BleConnector(Context ctx) {
        context = ctx;
        BluetoothManager manager =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : null;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    // ===================== SCAN =====================

    @SuppressLint("MissingPermission")
    public void startScan(ScanListener listener) {

        stopScan();

        if (adapter == null || !adapter.isEnabled()) return;

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;

        scanCallback = new ScanCallback() {

            Set<String> seen = new HashSet<>();

            @Override
            public void onScanResult(int callbackType, ScanResult result) {

                BluetoothDevice device = result.getDevice();
                if (device == null) return;

                Log.d("SCAN", "Found: " + device.getAddress());

                if (!seen.contains(device.getAddress())) {
                    seen.add(device.getAddress());
                    listener.onDeviceFound(device);
                }
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        scanner.startScan(null, settings, scanCallback);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        try {
            if (scanner != null && scanCallback != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (Exception ignored) {}

        scanCallback = null;
        scanner = null;
    }

    // ===================== CONNECT =====================

    public void connectRight(String mac) {
        connect(mac, true);
    }

    public void connectLeft(String mac) {
        connect(mac, false);
    }

    @SuppressLint("MissingPermission")
    private void connect(String mac, boolean isRight) {

        if (adapter == null) return;

        if (isRight && rightConnecting) return;
        if (!isRight && leftConnecting) return;

        if (isRight) rightConnecting = true;
        else leftConnecting = true;

        BluetoothDevice device = adapter.getRemoteDevice(mac);

        BluetoothGatt gatt = device.connectGatt(
                context,
                false,
                new DeviceGattCallback(isRight),
                BluetoothDevice.TRANSPORT_LE
        );

        if (isRight) rightGatt = gatt;
        else leftGatt = gatt;

        if (callback != null) {
            if (isRight) callback.onRightStatus("Connecting...");
            else callback.onLeftStatus("Connecting...");
        }
    }
    @SuppressLint("MissingPermission")
    public void disconnectRight() {
        if (rightGatt != null) {
            rightGatt.disconnect();
            rightGatt.close();
            rightGatt = null;
        }

        if (callback != null) {
            callback.onRightStatus("Disconnected");
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnectLeft() {
        if (leftGatt != null) {
            leftGatt.disconnect();
            leftGatt.close();
            leftGatt = null;
        }

        if (callback != null) {
            callback.onLeftStatus("Disconnected");
        }
    }
    // ===================== GATT =====================

    private class DeviceGattCallback extends BluetoothGattCallback {

        private final boolean isRight;

        DeviceGattCallback(boolean isRight) {
            this.isRight = isRight;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {

                if (isRight) rightConnecting = false;
                else leftConnecting = false;

                if (callback != null) {
                    if (isRight) callback.onRightStatus("Connected");
                    else callback.onLeftStatus("Connected");
                }

                mainHandler.postDelayed(gatt::discoverServices, 300);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            BluetoothGattService service = gatt.getService(SERVICE_UUID);

            if (service == null) {
                Log.e("BLE", "❌ Service not found");
                return;
            }

            BluetoothGattCharacteristic c = service.getCharacteristic(CHAR_UUID);

            if (c == null) {
                Log.e("BLE", "❌ Characteristic not found");
                return;
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            gatt.setCharacteristicNotification(c, true);

            BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD_UUID);

            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }

            Log.d("BLE", "✅ Notification enabled");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            String data = new String(characteristic.getValue(), StandardCharsets.UTF_8).trim();

            // 🔥 RAW LOG
            Log.d("BLE_RAW", data);

            SensorPacket packet = SensorPacket.fromCsv(data);

            if (packet == null) {
                Log.e("BLE_PARSE", "Invalid packet: " + data);
                return;
            }

            // 🔥 PARSED LOG
            Log.d("BLE_PARSED",
                    (isRight ? "RIGHT" : "LEFT") +
                            " → " + packet.shortAxes() +
                            " P=" + packet.predictedLabel
            );

            if (callback != null) {
                if (isRight) callback.onRightPacket(packet);
                else callback.onLeftPacket(packet);
            }
        }
    }
}