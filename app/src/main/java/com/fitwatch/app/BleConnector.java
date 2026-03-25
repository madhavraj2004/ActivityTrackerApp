package com.fitwatch.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
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

    // ─── FIX 1: Dedicated high-priority HandlerThread for ALL GATT callbacks ──
    // The default BLE callback thread is a shared system thread. Under load
    // (UI rendering, scan results, other BLE traffic) Android can delay it
    // 70-150ms — exactly the app-side gaps we saw in the data.
    // A dedicated thread with THREAD_PRIORITY_URGENT_AUDIO ensures our
    // callbacks are never preempted by lower-priority work.
    private final HandlerThread gattThread;
    private final Handler gattHandler;

    public BleConnector(Context ctx) {
        context = ctx;
        BluetoothManager manager =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager != null ? manager.getAdapter() : null;

        // Start dedicated GATT thread at urgent-audio priority
        // (same priority Android uses internally for audio playback —
        //  high enough to never be starved, low enough to not affect UI)
        gattThread = new HandlerThread("BleGatt-Thread",
                Process.THREAD_PRIORITY_URGENT_AUDIO);
        gattThread.start();
        gattHandler = new Handler(gattThread.getLooper());
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
                if (!seen.contains(device.getAddress())) {
                    seen.add(device.getAddress());
                    listener.onDeviceFound(device);
                }
            }
        };

        // ─── FIX 2: LOW_POWER scan mode while connected ────────────────────
        // LOW_LATENCY scan mode aggressively uses the 2.4GHz radio and
        // interferes with active GATT connections on the same adapter.
        // Once devices are found, switch to LOW_POWER to free the radio
        // for notification delivery. This directly reduces radio-level drops.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
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

    public void connectRight(String mac) { connect(mac, true); }
    public void connectLeft(String mac)  { connect(mac, false); }

    @SuppressLint("MissingPermission")
    private void connect(String mac, boolean isRight) {

        if (adapter == null) return;
        if (isRight && rightConnecting) return;
        if (!isRight && leftConnecting) return;

        if (isRight) rightConnecting = true;
        else leftConnecting = true;

        BluetoothDevice device = adapter.getRemoteDevice(mac);

        // ─── FIX 3: Pass gattHandler to connectGatt ────────────────────────
        // connectGatt() with a Handler parameter routes ALL callbacks
        // (onConnectionStateChange, onServicesDiscovered, onCharacteristicChanged)
        // directly to our dedicated gattThread instead of the shared system
        // BLE thread. This is the single biggest fix for app-side delays —
        // our callbacks now run in isolation, never waiting behind other work.
        // Requires API 26+. For API < 26 it falls back to the default thread.
        BluetoothGatt gatt;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            gatt = device.connectGatt(
                    context,
                    false,
                    new DeviceGattCallback(isRight),
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    gattHandler   // ← dedicated thread, not system shared thread
            );
        } else {
            gatt = device.connectGatt(
                    context,
                    false,
                    new DeviceGattCallback(isRight),
                    BluetoothDevice.TRANSPORT_LE
            );
        }

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
        if (callback != null) callback.onRightStatus("Disconnected");
    }

    @SuppressLint("MissingPermission")
    public void disconnectLeft() {
        if (leftGatt != null) {
            leftGatt.disconnect();
            leftGatt.close();
            leftGatt = null;
        }
        if (callback != null) callback.onLeftStatus("Disconnected");
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    public void shutdown() {
        stopScan();
        gattThread.quitSafely();
    }

    // ===================== GATT CALLBACKS =====================

    private class DeviceGattCallback extends BluetoothGattCallback {

        private final boolean isRight;

        DeviceGattCallback(boolean isRight) {
            this.isRight = isRight;
        }

        // Runs on gattThread (our dedicated thread)
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BLE", (isRight ? "RIGHT" : "LEFT") +
                        " connection failed, status=" + status);
                gatt.close();
                if (isRight) rightConnecting = false;
                else leftConnecting = false;
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {

                if (isRight) rightConnecting = false;
                else leftConnecting = false;

                if (callback != null) {
                    if (isRight) callback.onRightStatus("Connected");
                    else callback.onLeftStatus("Connected");
                }

                // ─── FIX 4: Request minimum connection interval (7.5ms) ───
                // The default BLE connection interval is ~45ms. Requesting
                // CONNECTION_PRIORITY_HIGH asks the phone to negotiate the
                // minimum allowed interval (7.5ms) with the peripheral.
                // Lower interval = more frequent connection events = ESP32
                // can deliver notifications faster = fewer queued-up packets
                // waiting to be delivered = less risk of radio-level drops.
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);

                // Small delay before service discovery to let the connection
                // interval negotiation complete
                gattHandler.postDelayed(gatt::discoverServices, 500);
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (callback != null) {
                    if (isRight) callback.onRightStatus("Disconnected");
                    else callback.onLeftStatus("Disconnected");
                }
            }
        }

        // Runs on gattThread
        @Override
        @SuppressLint("MissingPermission")
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

            if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return;

            gatt.setCharacteristicNotification(c, true);

            BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }

            // ─── FIX 5: Request larger MTU ────────────────────────────────
            // Default MTU is 23 bytes. If your ESP32 packet ever grows beyond
            // 20 bytes, Android will fragment it across multiple ATT packets.
            // Requesting 512 lets the stack negotiate the maximum supported —
            // typically 247 bytes — ensuring each ESP32 packet arrives in a
            // single ATT PDU with no fragmentation overhead.
            gatt.requestMtu(512);

            Log.d("BLE", (isRight ? "RIGHT" : "LEFT") + " ✅ Notifications enabled");
        }

        // Runs on gattThread — zero contention with other work
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {

            byte[] value = characteristic.getValue();
            if (value == null || value.length == 0) return;

            String data = new String(value, StandardCharsets.UTF_8).trim();
            Log.d("BLE_RAW", data);

            SensorPacket packet = SensorPacket.fromCsv(data);
            if (packet == null) {
                Log.e("BLE_PARSE", "Invalid packet: " + data);
                return;
            }

            Log.d("BLE_PARSED",
                    (isRight ? "RIGHT" : "LEFT") +
                            " → " + packet.shortAxes() +
                            " P=" + packet.predictedLabel);

            // Deliver to SyncEngine — also posts immediately and returns,
            // so this callback completes in microseconds, ready for the next one
            if (callback != null) {
                if (isRight) callback.onRightPacket(packet);
                else callback.onLeftPacket(packet);
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d("BLE", (isRight ? "RIGHT" : "LEFT") +
                    " MTU negotiated: " + mtu + "bytes, status=" + status);
        }
    }
}