package com.fitwatch.app;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ActivityFragment extends Fragment implements BleConnector.Callback {

    // ── Core engines ──────────────────────────────────────────────────────────
    private final SyncEngine        syncEngine        = SyncEngine.getInstance();
    private final PredictionManager predictionManager = new PredictionManager();
    private final CsvManager        csvManager        = new CsvManager();

    // ── BLE ───────────────────────────────────────────────────────────────────
    private BleConnector            bleConnector;

    // ── Speech ────────────────────────────────────────────────────────────────
    private TextToSpeech            tts;
    private String                  lastRightSpoken   = "";
    private String                  lastLeftSpoken    = "";
    private long                    lastSpeakTime     = 0;
    private static final long       SPEAK_INTERVAL    = 6000;
    private String                  lastSpokenActivity = null;

    // ── Wake lock ─────────────────────────────────────────────────────────────
    private PowerManager.WakeLock   wakeLock;

    // ── UI views ──────────────────────────────────────────────────────────────
    private RecyclerView            rvActivities, rvBuffer;
    private ActivityAdapter         activityAdapter;
    private BufferAdapter           bufferAdapter;
    private TextInputEditText       etCustomId, etCustomActivity;
    private MaterialButton          btnStart, btnStop, btnConnectRight,
            btnConnectLeft, btnAddCustom, btnExport;
    private android.widget.TextView txtPredictions, txtSelectedActivity,
            txtRecordState, txtRightStatus, txtLeftStatus;

    // ── Scan dialog helpers ───────────────────────────────────────────────────
    private DeviceAdapter           activeAdapter = null;
    private List<BluetoothDevice>   activeList    = null;
    private final Map<String, BluetoothDevice> cachedDevices = new HashMap<>();

    // ── Activity list ─────────────────────────────────────────────────────────
    private final ArrayList<String> activities = new ArrayList<>();

    // ── Recording state ───────────────────────────────────────────────────────
    private boolean         recording        = false;
    private volatile String selectedActivity = null;
    private String          lastRightLabel   = "";
    private String          lastLeftLabel    = "";

    // ── Connection state ──────────────────────────────────────────────────────
    private boolean rightConnected = false;
    private boolean leftConnected  = false;

    // ── Shared ViewModel (bridges to InferenceFragment) ───────────────────────
    private SharedRecordingViewModel sharedVm;

    // ── Launchers ─────────────────────────────────────────────────────────────
    private ActivityResultLauncher<String>   exportLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LabelMapper.init(requireContext());
        bleConnector = new BleConnector(requireContext());
        bleConnector.setCallback(this);

        // Shared ViewModel — scoped to the host Activity so InferenceFragment sees it too
        sharedVm = new ViewModelProvider(requireActivity())
                .get(SharedRecordingViewModel.class);

        // Export raw BLE CSV
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> {
                    if (uri != null) {
                        csvManager.exportToUri(requireContext(), uri);
                        toast("CSV exported");
                        // ── Notify InferenceFragment: a new recording CSV is ready ──
                        sharedVm.recordedCsvUri.postValue(uri);
                    }
                });

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean ok =
                            Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_SCAN))
                                    && Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_CONNECT))
                                    && Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                    toast(ok ? "Permissions granted" : "All permissions required for BLE");
                });

        tts = new TextToSpeech(requireContext(), status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_activity, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle savedInstanceState) {

        // Bind views
        txtPredictions      = v.findViewById(R.id.txtPredictions);
        txtSelectedActivity = v.findViewById(R.id.txtSelectedActivity);
        txtRecordState      = v.findViewById(R.id.txtRecordState);
        txtRightStatus      = v.findViewById(R.id.txtRightStatus);
        txtLeftStatus       = v.findViewById(R.id.txtLeftStatus);
        rvActivities        = v.findViewById(R.id.rvActivities);
        rvBuffer            = v.findViewById(R.id.rvBuffer);
        etCustomActivity    = v.findViewById(R.id.etCustomActivity);
        etCustomId          = v.findViewById(R.id.etCustomId);
        btnAddCustom        = v.findViewById(R.id.btnAddCustomActivity);
        btnStart            = v.findViewById(R.id.btnStart);
        btnStop             = v.findViewById(R.id.btnStop);
        btnConnectRight     = v.findViewById(R.id.btnConnectRight);
        btnConnectLeft      = v.findViewById(R.id.btnConnectLeft);
        btnExport           = v.findViewById(R.id.btnExport);

        MaterialButton btnDisconnectRight = v.findViewById(R.id.btnDisconnectRight);
        MaterialButton btnDisconnectLeft  = v.findViewById(R.id.btnDisconnectLeft);

        btnDisconnectRight.setOnClickListener(x ->
                new AlertDialog.Builder(getContext())
                        .setTitle("Disconnect Right Device")
                        .setMessage("Are you sure?")
                        .setPositiveButton("Yes", (d, w) -> bleConnector.disconnectRight())
                        .setNegativeButton("Cancel", null).show());

        btnDisconnectLeft.setOnClickListener(x ->
                new AlertDialog.Builder(getContext())
                        .setTitle("Disconnect Left Device")
                        .setMessage("Are you sure?")
                        .setPositiveButton("Yes", (d, w) -> bleConnector.disconnectLeft())
                        .setNegativeButton("Cancel", null).show());

        // ── Activity adapter ──────────────────────────────────────────────────
        activityAdapter = new ActivityAdapter(activities, new ActivityAdapter.Listener() {

            @Override
            public void onSelected(String activity) {
                String clean = activity.contains(":")
                        ? activity.split(": ", 2)[1] : activity;
                String previous = selectedActivity;
                selectedActivity = clean;
                txtSelectedActivity.setText("Selected: " + clean);

                if (recording) {
                    txtRecordState.setText("Recording: " + clean);
                    lastRightLabel = ""; lastLeftLabel = "";
                    if (previous != null && !previous.equals(clean))
                        speak("Activity changed from " + previous + " to " + clean);
                }
                updateControls();
            }

            @Override
            public void onDelete(String activity) {
                int id     = Integer.parseInt(activity.split(":")[0]);
                String name = activity.split(": ", 2)[1];
                LabelMapper.removeLabel(requireContext(), id);
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

        // ── Live listener: CSV write + buffer + speech ────────────────────────
        syncEngine.setLiveListener(row -> {
            if (row == null || row.d1 == null || row.d2 == null) return;
            if (row.d1.predictedLabel == 0 && row.d2.predictedLabel == 0) return;

            if (recording && selectedActivity != null) {
                csvManager.write(row, selectedActivity);
            }

            handleSpeech(row);

            requireActivity().runOnUiThread(() -> {
                bufferAdapter.addRow(row);
                rvBuffer.scrollToPosition(bufferAdapter.getItemCount() - 1);
            });
        });

        // ── Window listener: on-device prediction display ─────────────────────
        syncEngine.setWindowListener(window -> {
            int rightCode = predictionManager.modeRightCode(window);
            int leftCode  = predictionManager.modeLeftCode(window);
            requireActivity().runOnUiThread(() ->
                    txtPredictions.setText(
                            "Right: " + rightCode + ": " + LabelMapper.toText(rightCode) +
                                    "\nLeft:  " + leftCode  + ": " + LabelMapper.toText(leftCode)));
        });

        btnStart.setOnClickListener(x  -> startRecording());
        btnStop.setOnClickListener(x   -> stopRecording());
        btnExport.setOnClickListener(x -> exportLauncher.launch("activity.csv"));
        btnConnectRight.setOnClickListener(x -> requestScan(true));
        btnConnectLeft.setOnClickListener(x  -> requestScan(false));
        btnAddCustom.setOnClickListener(x    -> addCustom());

        // Restore display text after tab switch (view is recreated)
        txtRecordState.setText(recording
                ? "Recording: " + selectedActivity : "Recording: OFF");
        if (selectedActivity != null)
            txtSelectedActivity.setText("Selected: " + selectedActivity);

        startBackgroundScan();
        updateControls();
    }

    // ── Background BLE scan ───────────────────────────────────────────────────

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
            @Override public void onScanFinished() {}
        });
    }

    private void requestScan(boolean isRight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean ok =
                    ContextCompat.checkSelfPermission(requireContext(),
                            Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                            && ContextCompat.checkSelfPermission(requireContext(),
                            Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                            && ContextCompat.checkSelfPermission(requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!ok) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION});
                return;
            }
        }
        showScanDialog(isRight);
    }

    private void showScanDialog(boolean isRight) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_device_scan, null);
        RecyclerView rv  = view.findViewById(R.id.rvDevices);
        View         progress = view.findViewById(R.id.scanProgress);

        List<BluetoothDevice> list = new ArrayList<>(cachedDevices.values());
        activeList = list;

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(view).create();

        DeviceAdapter adapter = new DeviceAdapter(list, device -> {
            if (isRight) {
                bleConnector.connectRight(device.getAddress());
                txtRightStatus.setText("RIGHT: Connecting...");
            } else {
                bleConnector.connectLeft(device.getAddress());
                txtLeftStatus.setText("LEFT: Connecting...");
            }
            dialog.dismiss();
        });

        activeAdapter = adapter;
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(adapter);
        dialog.show();
        dialog.setOnDismissListener(d -> { activeAdapter = null; activeList = null; });
        if (!list.isEmpty()) progress.setVisibility(View.GONE);
    }

    private void refreshActivityList() {
        activities.clear();
        for (Map.Entry<Integer, String> e : LabelMapper.getAll().entrySet())
            activities.add(e.getKey() + ": " + e.getValue());
        activityAdapter.notifyDataSetChanged();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startRecording() {
        lastRightLabel  = ""; lastLeftLabel   = "";
        lastSpeakTime   = 0;  lastRightSpoken = ""; lastLeftSpoken = "";
        lastSpokenActivity = selectedActivity;

        if (!rightConnected && !leftConnected) {
            toast("Connect at least ONE device"); return;
        }
        if (selectedActivity == null) {
            toast("Select activity first"); return;
        }

        PowerManager pm = (PowerManager)
                requireContext().getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FitWatch::Lock");
        wakeLock.acquire();

        recording = true;
        csvManager.start(requireContext());
        txtRecordState.setText("Recording: " + selectedActivity);
        speak("Recording started");
        updateControls();
    }

    private void stopRecording() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        recording = false;
        csvManager.stop();
        txtRecordState.setText("Recording: OFF");
        updateControls();
        // Auto-launch the save dialog so the user names the file;
        // once saved, exportLauncher posts the Uri to sharedVm and
        // InferenceFragment's "Run Inference" button activates automatically.
        toast("Recording stopped — save CSV to run inference.");
        exportLauncher.launch("activity_" +
                new java.text.SimpleDateFormat("yyyyMMdd_HHmm",
                        java.util.Locale.US).format(new java.util.Date()) + ".csv");
    }

    // ── Custom activity ───────────────────────────────────────────────────────

    private void addCustom() {
        String name  = etCustomActivity.getText().toString().trim().toUpperCase();
        String idStr = etCustomId.getText().toString().trim();
        if (name.isEmpty() || idStr.isEmpty()) { toast("Enter both ID and Activity"); return; }
        int id;
        try { id = Integer.parseInt(idStr); } catch (Exception e) { toast("Invalid ID"); return; }
        if (!LabelMapper.addLabel(requireContext(), id, name)) {
            toast("ID or Activity already exists"); return;
        }
        refreshActivityList();
        selectedActivity = name;
        txtSelectedActivity.setText("Selected: " + name);
        etCustomActivity.setText(""); etCustomId.setText("");
        toast("Added: " + id + ": " + name);
    }

    // ── Controls ──────────────────────────────────────────────────────────────

    private void updateControls() {
        btnStart.setEnabled(selectedActivity != null
                && (rightConnected || leftConnected) && !recording);
        btnStop.setEnabled(recording);
        btnConnectRight.setEnabled(!rightConnected);
        btnConnectLeft.setEnabled(!leftConnected);
    }

    // ── Speech ────────────────────────────────────────────────────────────────

    private void handleSpeech(SyncRow row) {
        if (!recording || row == null) return;
        int r = (row.d1 != null) ? row.d1.predictedLabel : 0;
        int l = (row.d2 != null) ? row.d2.predictedLabel : 0;
        if (r == 0 && l == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastSpeakTime < SPEAK_INTERVAL) return;
        lastSpeakTime = now;

        String rightText = LabelMapper.toText(r);
        String leftText  = LabelMapper.toText(l);
        StringBuilder sb = new StringBuilder();

        if (row.d2 != null)
            sb.append(lastLeftSpoken.isEmpty() ? "Left hand " + leftText
                    : !leftText.equals(lastLeftSpoken) ? "Left changed to " + leftText
                    : "Left hand " + leftText).append(". ");

        if (row.d1 != null)
            sb.append(lastRightSpoken.isEmpty() ? "Right hand " + rightText
                    : !rightText.equals(lastRightSpoken) ? "Right changed to " + rightText
                    : "Right hand " + rightText).append(". ");

        lastRightSpoken = rightText;
        lastLeftSpoken  = leftText;
        speak(sb.toString());
    }

    private void speak(String text) {
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void toast(String m) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), m, Toast.LENGTH_SHORT).show());
    }

    // ── BleConnector.Callback ─────────────────────────────────────────────────

    @Override
    public void onRightStatus(String t) {
        requireActivity().runOnUiThread(() -> {
            txtRightStatus.setText("RIGHT: " + t);

            if (t.equalsIgnoreCase("Connected")) {
                rightConnected = true;
                // Stop scanning once both connected — frees radio for notifications
                if (leftConnected) bleConnector.stopScan();

            } else if (t.equalsIgnoreCase("Disconnected")) {
                // ── Only reset/speak on TRUE disconnect, not "Connecting..." or "Failed"
                // Calling syncEngine.reset() on "Connecting..." wiped queues mid-connect
                // and caused the "Recording: OFF" / false disconnect speech bug.
                rightConnected = false;
                syncEngine.reset();
                speak("Right device disconnected");
                if (!leftConnected && recording) {
                    stopRecording();
                    speak("Recording stopped");
                }
            } else {
                // "Connecting..." or "Failed" — update flag only, no reset or speech
                rightConnected = false;
            }
            updateControls();
        });
    }

    @Override
    public void onLeftStatus(String t) {
        requireActivity().runOnUiThread(() -> {
            txtLeftStatus.setText("LEFT: " + t);

            if (t.equalsIgnoreCase("Connected")) {
                leftConnected = true;
                if (rightConnected) bleConnector.stopScan();

            } else if (t.equalsIgnoreCase("Disconnected")) {
                leftConnected = false;
                syncEngine.reset();
                speak("Left device disconnected");
                if (!rightConnected && recording) {
                    stopRecording();
                    speak("Recording stopped");
                }
            } else {
                leftConnected = false;
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
        // Only show BLE messages when not recording — never overwrite "Recording: X"
        if (!recording) {
            requireActivity().runOnUiThread(() -> txtRecordState.setText(t));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        bleConnector.stopScan();
        bleConnector.shutdown();
        syncEngine.shutdown();
    }
}