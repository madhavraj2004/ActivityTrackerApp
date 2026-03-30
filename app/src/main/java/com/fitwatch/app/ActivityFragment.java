package com.fitwatch.app;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ActivityFragment extends Fragment implements BleConnector.Callback {

    private final SyncEngine syncEngine = new SyncEngine();
    private final PredictionManager predictionManager = new PredictionManager();
    private final CsvManager csvManager = new CsvManager();

    private BleConnector bleConnector;
    private TextToSpeech tts;
    private android.os.PowerManager.WakeLock wakeLock;
    private RecyclerView rvActivities, rvBuffer;
    private ActivityAdapter activityAdapter;
    private BufferAdapter bufferAdapter;
    private TextInputEditText etCustomId;
    private DeviceAdapter activeAdapter = null;
    private List<BluetoothDevice> activeList = null;
    private MaterialButton btnStart, btnStop, btnConnectRight, btnConnectLeft, btnAddCustom, btnExport;
    private TextInputEditText etCustomActivity;
    private String lastRightSpoken = "";
    private String lastLeftSpoken = "";
    private android.widget.TextView txtPredictions, txtSelectedActivity, txtRecordState, txtRightStatus, txtLeftStatus;
    private final Map<String, BluetoothDevice> cachedDevices = new HashMap<>();
    private final ArrayList<String> activities = new ArrayList<>();
    private long lastSpeakTime = 0;

    private static final long SPEAK_INTERVAL = 6000; // 6 seconds
    private String lastSpokenActivity = null;
    private boolean recording = false;
    private volatile String selectedActivity = null;
    private String lastRightLabel = "";
    private String lastLeftLabel = "";
    private boolean rightConnected = false;
    private boolean leftConnected = false;
    private ActivityResultLauncher<String> exportLauncher;
    // 🔥 CHANGE THIS
    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabelMapper.init(requireContext());
        bleConnector = new BleConnector(requireContext());
        bleConnector.setCallback(this);

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> {
                    if (uri != null) {
                        csvManager.exportToUri(requireContext(), uri);
                        toast("CSV exported");
                    }
                }
        );

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {

                    boolean scan = Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_SCAN));
                    boolean connect = Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_CONNECT));
                    boolean location = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));

                    if (!scan || !connect || !location) {
                        toast("All permissions required for BLE");
                    } else {
                        toast("Permissions granted");
                    }
                }
        );

        tts = new TextToSpeech(requireContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
            }
        });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_activity, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle savedInstanceState) {

        txtPredictions = v.findViewById(R.id.txtPredictions);
        txtSelectedActivity = v.findViewById(R.id.txtSelectedActivity);
        txtRecordState = v.findViewById(R.id.txtRecordState);
        txtRightStatus = v.findViewById(R.id.txtRightStatus);
        txtLeftStatus = v.findViewById(R.id.txtLeftStatus);

        rvActivities = v.findViewById(R.id.rvActivities);
        rvBuffer = v.findViewById(R.id.rvBuffer);
        MaterialButton btnDisconnectRight = v.findViewById(R.id.btnDisconnectRight);
        MaterialButton btnDisconnectLeft  = v.findViewById(R.id.btnDisconnectLeft);
        etCustomActivity = v.findViewById(R.id.etCustomActivity);
        etCustomId = v.findViewById(R.id.etCustomId);
        btnAddCustom = v.findViewById(R.id.btnAddCustomActivity);
        btnStart = v.findViewById(R.id.btnStart);
        btnStop = v.findViewById(R.id.btnStop);
        btnConnectRight = v.findViewById(R.id.btnConnectRight);
        btnConnectLeft = v.findViewById(R.id.btnConnectLeft);
        btnExport = v.findViewById(R.id.btnExport);

        // Activities

        btnDisconnectRight.setOnClickListener(v1 -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("Disconnect Right Device")
                    .setMessage("Are you sure you want to disconnect RIGHT device?")
                    .setPositiveButton("Yes", (d, w) -> {
                        bleConnector.disconnectRight();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnDisconnectLeft.setOnClickListener(v2 -> {
            new AlertDialog.Builder(getContext())
                    .setTitle("Disconnect Left Device")
                    .setMessage("Are you sure you want to disconnect LEFT device?")
                    .setPositiveButton("Yes", (d, w) -> {
                        bleConnector.disconnectLeft();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        activityAdapter = new ActivityAdapter(activities, new ActivityAdapter.Listener() {

            @Override
            public void onSelected(String activity) {

                String clean = activity.contains(":")
                        ? activity.split(": ", 2)[1]
                        : activity;

                String previous = selectedActivity;

                selectedActivity = clean;

                txtSelectedActivity.setText("Selected: " + clean);

                if (recording) {
                    txtRecordState.setText("Recording: " + clean);
                    lastRightLabel = "";
                    lastLeftLabel = "";

                    if (previous != null && !previous.equals(clean)) {
                        speak("Activity changed from " + previous + " to " + clean);
                    }
                }

                updateControls();
            }

            @Override
            public void onDelete(String activity) {

                int id = Integer.parseInt(activity.split(":")[0]);
                String name = activity.split(": ", 2)[1];

                LabelMapper.removeLabel(requireContext(), id);

                // 🔥 handle selected deletion
                if (selectedActivity != null && selectedActivity.equals(name)) {
                    selectedActivity = null;
                    txtSelectedActivity.setText("Selected: none");
                }

                refreshActivityList();
                updateControls();
                toast("Deleted: " + activity);
            }
        });
        refreshActivityList();
        rvActivities.setLayoutManager(new GridLayoutManager(getContext(), 2));
        rvActivities.setAdapter(activityAdapter);

        bufferAdapter = new BufferAdapter();
        rvBuffer.setLayoutManager(new LinearLayoutManager(getContext()));
        rvBuffer.setAdapter(bufferAdapter);

        syncEngine.setLiveListener(row -> {

            if (row == null || row.d1 == null || row.d2 == null) return;

            // ✅ Skip rows where BOTH sides have label 0
            if (row.d1.predictedLabel == 0 && row.d2.predictedLabel == 0) return;

            // ✅ CSV write — CsvManager.write() posts to its own writeThread internally.
            // This lambda itself runs on SyncEngine's syncThread, so returning fast
            // here is important. write() returns immediately (just posts a message).
            if (recording && selectedActivity != null) {
                csvManager.write(row, selectedActivity);
            }

            // ✅ Speech — unchanged
            handleSpeech(row);

            // ✅ Live buffer UI — unchanged
            requireActivity().runOnUiThread(() -> {
                bufferAdapter.addRow(row);
                rvBuffer.scrollToPosition(bufferAdapter.getItemCount() - 1);
            });
        });

        // PREDICTION
        syncEngine.setWindowListener(window -> {

            int rightCode = predictionManager.modeRightCode(window);
            int leftCode  = predictionManager.modeLeftCode(window);

            String rightText = LabelMapper.toText(rightCode);
            String leftText  = LabelMapper.toText(leftCode);

            requireActivity().runOnUiThread(() -> {

                txtPredictions.setText(
                        "Right: " + rightCode + ": " + rightText +
                                "\nLeft: " + leftCode + ": " + leftText
                );
            });
        });

        btnStart.setOnClickListener(v1 -> startRecording());
        btnStop.setOnClickListener(v12 -> stopRecording());
        btnExport.setOnClickListener(v13 -> exportCsv());

        btnConnectRight.setOnClickListener(v14 -> requestScan(true));
        btnConnectLeft.setOnClickListener(v15 -> requestScan(false));

        btnAddCustom.setOnClickListener(v16 -> addCustom());
        startBackgroundScan();
        updateControls();
    }
    private void startBackgroundScan() {

        bleConnector.startScan(new BleConnector.ScanListener() {

            @Override
            public void onDeviceFound(BluetoothDevice device) {

                String mac = device.getAddress();

                if (!cachedDevices.containsKey(mac)) {

                    cachedDevices.put(mac, device);

                    if (activeAdapter != null && activeList != null) {
                        requireActivity().runOnUiThread(() -> {
                            activeList.add(device);
                            activeAdapter.notifyItemInserted(activeList.size() - 1);
                        });
                    }
                }
            }

            @Override
            public void onScanFinished() {
                // optional
            }
        });
    }
    // 🔥 SCAN FLOW
    private void requestScan(boolean isRight) {



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            boolean scanGranted = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;

            boolean connectGranted = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;

            boolean locationGranted = ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;

            if (!scanGranted || !connectGranted || !locationGranted) {

                permissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                });

                return;
            }
        }

        showScanDialog(isRight);
    }
    private void refreshActivityList() {

        activities.clear();

        for (java.util.Map.Entry<Integer, String> e : LabelMapper.getAll().entrySet()) {
            activities.add(e.getKey() + ": " + e.getValue());
        }

        activityAdapter.notifyDataSetChanged();
    }
    private void showScanDialog(boolean isRight) {

        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_device_scan, null);

        RecyclerView rv = view.findViewById(R.id.rvDevices);
        View progress = view.findViewById(R.id.scanProgress);

        // ✅ INSTANT FROM CACHE
        List<BluetoothDevice> list = new ArrayList<>(cachedDevices.values());
        activeList = list;

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(view)
                .create();

        DeviceAdapter adapter = new DeviceAdapter(list, device -> {

            android.util.Log.d("BLE", "Connecting to: " + device.getAddress());

            if (isRight) {
                bleConnector.connectRight(device.getAddress());
                txtRightStatus.setText("RIGHT: Connecting...");
            } else {
                bleConnector.connectLeft(device.getAddress());
                txtLeftStatus.setText("LEFT: Connecting...");
            }

            dialog.dismiss();
        });

        // 🔥 ADD THIS
        activeAdapter = adapter;

        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);

        dialog.show();

        // 🔥 CLEANUP (VERY IMPORTANT)
        dialog.setOnDismissListener(d -> {
            activeAdapter = null;
            activeList = null;
        });

        if (!list.isEmpty()) {
            progress.setVisibility(View.GONE);
        }
    }
    private void handleSpeech(SyncRow row) {
        if (!recording) return;
        if (row == null) return;

        int r = (row.d1 != null) ? row.d1.predictedLabel : 0;
        int l = (row.d2 != null) ? row.d2.predictedLabel : 0;

        if (r == 0 && l == 0) return;

        String rightText = LabelMapper.toText(r);
        String leftText  = LabelMapper.toText(l);

        long now = System.currentTimeMillis();

        // 🔥 ONLY EVERY 6 SECONDS
        if (now - lastSpeakTime < SPEAK_INTERVAL) return;

        lastSpeakTime = now;

        StringBuilder speech = new StringBuilder();

// LEFT
        if (row.d2 != null) {
            if (lastLeftSpoken.isEmpty()) {
                speech.append("Left hand ").append(leftText).append(". ");
            } else if (!leftText.equals(lastLeftSpoken)) {
                speech.append("Left hand changed from ")
                        .append(lastLeftSpoken)
                        .append(" to ")
                        .append(leftText)
                        .append(". ");
            } else {
                speech.append("Left hand ").append(leftText).append(". ");
            }
        }

// RIGHT
        if (row.d1 != null) {
            if (lastRightSpoken.isEmpty()) {
                speech.append("Right hand ").append(rightText).append(". ");
            } else if (!rightText.equals(lastRightSpoken)) {
                speech.append("Right hand changed from ")
                        .append(lastRightSpoken)
                        .append(" to ")
                        .append(rightText)
                        .append(". ");
            } else {
                speech.append("Right hand ").append(rightText).append(". ");
            }
        }

        // 🔥 UPDATE LAST STATE
        lastRightSpoken = rightText;
        lastLeftSpoken = leftText;

        speak(speech.toString());
    }
    private void startRecording() {
        lastRightLabel = "";
        lastLeftLabel = "";
        lastSpeakTime = 0;
        lastRightSpoken = "";
        lastLeftSpoken = "";
        lastSpokenActivity = selectedActivity;
        if (!rightConnected && !leftConnected) {
            toast("Connect at least ONE device");
            return;
        }

        if (selectedActivity == null) {
            toast("Select activity first");
            return;
        }

        PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitWatch::Lock");
        wakeLock.acquire();

        recording = true;

        csvManager.start(requireContext());

        txtRecordState.setText("Recording: " + selectedActivity);

        speak("Recording started");

        updateControls();
    }

    private void stopRecording() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        recording = false;
        csvManager.stop();
        txtRecordState.setText("Recording: OFF");
        updateControls();
    }

    private void exportCsv() {
        exportLauncher.launch("activity.csv");
    }

    private void addCustom() {

        String name = etCustomActivity.getText().toString().trim().toUpperCase();
        String idStr = etCustomId.getText().toString().trim();

        if (name.isEmpty() || idStr.isEmpty()) {
            toast("Enter both ID and Activity");
            return;
        }

        int id;

        try {
            id = Integer.parseInt(idStr);
        } catch (Exception e) {
            toast("Invalid ID");
            return;
        }

        boolean added = LabelMapper.addLabel(requireContext(), id, name);

        if (!added) {
            toast("ID or Activity already exists");
            return;
        }

        refreshActivityList();

        selectedActivity = name;
        txtSelectedActivity.setText("Selected: " + name);

        etCustomActivity.setText("");
        etCustomId.setText("");

        toast("Added: " + id + ": " + name);
    }

    private void updateControls() {
        btnStart.setEnabled(
                selectedActivity != null &&
                        (rightConnected || leftConnected) &&
                        !recording
        );
        btnStop.setEnabled(recording);
        btnConnectRight.setEnabled(!rightConnected);
        btnConnectLeft.setEnabled(!leftConnected);
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void toast(String m) {
        if (getActivity() == null) return;

        getActivity().runOnUiThread(() -> {
            Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show();
        });
    }


    @Override
    public void onRightStatus(String t) {
        requireActivity().runOnUiThread(() -> {
            txtRightStatus.setText("RIGHT: " + t);
            rightConnected = t.equalsIgnoreCase("Connected");

            if (!rightConnected) {
                syncEngine.reset();
                speak("Right device disconnected");
            }

            // 🔥 STOP if both gone
            if (!rightConnected && !leftConnected && recording) {
                stopRecording();
                speak("All devices disconnected. Recording stopped");
            }

            updateControls();
        });
    }

    @Override
    public void onLeftStatus(String t) {
        requireActivity().runOnUiThread(() -> {
            txtLeftStatus.setText("LEFT: " + t);
            leftConnected = t.equalsIgnoreCase("Connected");

            if (!leftConnected) {
                syncEngine.reset();
                speak("Left device disconnected");
            }

            // 🔥 STOP if both gone
            if (!rightConnected && !leftConnected && recording) {
                stopRecording();
                speak("All devices disconnected. Recording stopped");
            }

            updateControls();
        });
    }
    @Override
    public void onRightPacket(SensorPacket p) {
        android.util.Log.d("BLE", "RIGHT → " + p.shortAxes() + " P=" + p.predictedLabel);
        syncEngine.addRight(p);
    }

    @Override
    public void onLeftPacket(SensorPacket p) {
        android.util.Log.d("BLE", "LEFT → " + p.shortAxes() + " P=" + p.predictedLabel);
        syncEngine.addLeft(p);
    }

    @Override
    public void onMessage(String t) {
        requireActivity().runOnUiThread(() -> {
            txtRecordState.setText(t); // or create a separate log view
        });
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        bleConnector.stopScan();
        bleConnector.shutdown();   // ← shuts down dedicated gattThread cleanly
        syncEngine.shutdown();
    }
}