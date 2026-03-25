package com.fitwatch.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> enableBluetoothLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 🔥 Permission launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!granted) {
                            allGranted = false;
                            break;
                        }
                    }

                    if (!allGranted) {
                        Toast.makeText(this, "Permissions required for BLE", Toast.LENGTH_LONG).show();
                    }
                }
        );

        // 🔥 Bluetooth enable launcher
        enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK) {
                        Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // 🔥 Ask permissions
        checkAndRequestPermissions();

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.container, new ActivityFragment())
                    .commit();
        }
    }

    // ================= PERMISSIONS =================

    private void checkAndRequestPermissions() {

        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }

            // 🔥 Needed on many devices
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }

        } else {
            // Android < 12
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissions.isEmpty()) {
            permissionLauncher.launch(permissions.toArray(new String[0]));
        }
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ================= BLUETOOTH CHECK =================

    private void checkBluetoothEnabled() {

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();

        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            return;
        }

        if (!adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
        }
    }

    // 🔥 Called every time app comes to foreground
    @Override
    protected void onResume() {
        super.onResume();

        checkBluetoothEnabled();
    }
}