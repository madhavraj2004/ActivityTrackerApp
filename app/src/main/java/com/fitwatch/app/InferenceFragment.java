package com.fitwatch.app;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * InferenceFragment
 */
public class InferenceFragment extends Fragment {

    private static final String TAG         = "InferenceFragment";
    private static final int    WINDOW_SIZE = 60; // 3s × 20Hz = matches training

    // ── Timestamp format matches screenshot: "22-03-2026 13:46" ──────────────
    private final SimpleDateFormat outTsFmt =
            new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US);

    // ── Legacy alias map for old LabelMapper names stored in CSV ─────────────
    private static final Map<String, String> LEGACY_ALIASES =
            new HashMap<String, String>() {{
                put("drink left",   "drinking (left)");
                put("drink right",  "drinking (right)");
                put("knock left",   "knocking (left)");
                put("knock right",  "knocking (right)");
                put("phone left",   "phone call (left)");
                put("phone right",  "phone call (right)");
            }};

    // ── New CsvManager column names (D1_acc_X format) ────────────────────────
    private static final String[] NEW_FEATURE_COLS = {
            "D1_acc_X","D1_acc_Y","D1_acc_Z","D1_gyro_X","D1_gyro_Y","D1_gyro_Z",
            "D2_acc_X","D2_acc_Y","D2_acc_Z","D2_gyro_X","D2_gyro_Y","D2_gyro_Z"
    };

    // ── Old CsvManager column names (d1_ax format) ───────────────────────────
    private static final String[] OLD_FEATURE_COLS = {
            "d1_ax","d1_ay","d1_az","d1_gx","d1_gy","d1_gz",
            "d2_ax","d2_ay","d2_az","d2_gx","d2_gy","d2_gz"
    };

    // ── UI ────────────────────────────────────────────────────────────────────
    private TextView                txtStatus, txtSummary, txtFileName;
    private LinearProgressIndicator progressBar;
    private MaterialButton          btnPickCsv, btnRunInference, btnExportResult;

    // ── ViewModel for retained state (replaces setRetainInstance) ──────────
    private InferenceViewModel viewModel;

    // ── Shared ViewModel (receives recorded CSV uri from ActivityFragment) ────
    private SharedRecordingViewModel sharedVm;

    // ── Background thread ─────────────────────────────────────────────────────
    private final HandlerThread inferenceThread = new HandlerThread("InferenceThread");
    private Handler             inferenceHandler;

    // ── Launchers ─────────────────────────────────────────────────────────────
    private ActivityResultLauncher<String[]> pickCsvLauncher;
    private ActivityResultLauncher<String>   exportLauncher;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        viewModel = new ViewModelProvider(this).get(InferenceViewModel.class);

        LabelMapper.init(requireContext());
        if (!inferenceThread.isAlive()) inferenceThread.start();
        inferenceHandler = new Handler(inferenceThread.getLooper());

        pickCsvLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        viewModel.inputCsvUri = uri;
                        if (txtFileName != null)
                            txtFileName.setText("File: " + uri.getLastPathSegment());
                        if (btnRunInference != null)
                            btnRunInference.setEnabled(viewModel.modelLoaded);
                        setStatus("CSV loaded — tap Run Inference.");
                    }
                });

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> { if (uri != null) exportOutputCsv(uri); });

        // Init output header only once
        if (viewModel.outputRows.isEmpty())
            viewModel.outputRows.add("Timestamp,Actual_Label,Predicted_Label,Confidence,Latency(ms),Valid");

        // Load model only once
        if (!viewModel.modelLoaded)
            inferenceHandler.post(this::loadModel);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_inference, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle savedInstanceState) {

        txtStatus       = v.findViewById(R.id.txtStatus);
        txtSummary      = v.findViewById(R.id.txtSummary);
        txtFileName     = v.findViewById(R.id.txtFileName);
        progressBar     = v.findViewById(R.id.inferenceProgress);
        btnPickCsv      = v.findViewById(R.id.btnPickCsv);
        btnRunInference = v.findViewById(R.id.btnRunInference);
        btnExportResult = v.findViewById(R.id.btnExportResult);

        progressBar.setVisibility(View.GONE);
        btnRunInference.setEnabled(viewModel.inputCsvUri != null && viewModel.modelLoaded);
        btnExportResult.setEnabled(viewModel.outputRows.size() > 1);

        // Restore UI after configuration change
        if (viewModel.inputCsvUri != null)
            txtFileName.setText("File: " + viewModel.inputCsvUri.getLastPathSegment());

        if (viewModel.modelLoaded) {
            setStatus("Model ready ✅  Pick a CSV and run inference.");
        }

        if (viewModel.summaryText != null && !viewModel.summaryText.isEmpty()) {
            txtSummary.setText(viewModel.summaryText);
        }

        btnPickCsv.setOnClickListener(x ->
                pickCsvLauncher.launch(new String[]{
                        "text/csv","text/comma-separated-values",
                        "application/octet-stream","*/*"}));

        btnRunInference.setOnClickListener(x -> runBatchInference());

        btnExportResult.setOnClickListener(x ->
                exportLauncher.launch("inference_results.csv"));

        // ── Observe CSV Uri pushed by ActivityFragment on recording stop ──────
        sharedVm = new ViewModelProvider(requireActivity())
                .get(SharedRecordingViewModel.class);

        sharedVm.recordedCsvUri.observe(getViewLifecycleOwner(), uri -> {
            if (uri == null) return;

            // Store it as the input CSV (same as if the user had picked it manually)
            viewModel.inputCsvUri = uri;
            txtFileName.setText("File: " + uri.getLastPathSegment());

            // Enable Run Inference as soon as the model is also ready
            boolean canRun = viewModel.modelLoaded;
            btnRunInference.setEnabled(canRun);

            if (canRun) {
                setStatus("Recording saved ✅  Tap Run Inference to analyse.");
            } else {
                setStatus("Recording saved — waiting for model to load…");
            }
        });
    }

    // ── Step 1: Load ONNX model from assets ──────────────────────────────────

    private void loadModel() {
        try {
            viewModel.ortEnv = OrtEnvironment.getEnvironment();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream is = requireContext().getAssets().open("xgb_1-16.onnx")) {
                byte[] chunk = new byte[8192]; int n;
                while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            viewModel.ortSession  = viewModel.ortEnv.createSession(buf.toByteArray(), opts);
            viewModel.modelLoaded = true;
            Log.d(TAG, "Model loaded. Inputs=" + viewModel.ortSession.getInputNames()
                    + " Outputs=" + viewModel.ortSession.getOutputNames());
            setStatus("Model ready ✅  Pick a CSV and run inference.");
            if (getActivity() != null && btnRunInference != null && viewModel.inputCsvUri != null)
                requireActivity().runOnUiThread(() -> btnRunInference.setEnabled(true));
        } catch (Exception e) {
            Log.e(TAG, "Model load failed", e);
            setStatus("❌ Model load failed: " + e.getMessage());
        }
    }

    // ── Step 2: Read CSV → batch 60 rows → feature extract → infer ───────────

    private void runBatchInference() {
        if (viewModel.inputCsvUri == null) { toast("Pick a CSV first"); return; }
        if (!viewModel.modelLoaded)        { toast("Model not ready");  return; }

        viewModel.outputRows.clear();
        viewModel.outputRows.add("Timestamp,Actual_Label,Predicted_Label,Confidence,Latency(ms),Valid");

        setStatus("Reading CSV…");
        showProgress(true);

        inferenceHandler.post(() -> {
            try {
                // ── 2a. Read CSV and auto-detect column format ────────────────
                List<float[]> dataRows   = new ArrayList<>();
                List<String>  timestamps = new ArrayList<>();
                List<String>  activities = new ArrayList<>();
                int[]         colIdx     = new int[12];
                int tsCol = -1, actCol = -1;

                try (InputStream is = requireContext().getContentResolver()
                        .openInputStream(viewModel.inputCsvUri);
                     BufferedReader br = new BufferedReader(new InputStreamReader(is))) {

                    String line = br.readLine();
                    if (line == null) throw new IllegalArgumentException("Empty CSV");

                    String[] headers = line.split(",");
                    Map<String, Integer> hMap = new HashMap<>();
                    for (int i = 0; i < headers.length; i++)
                        hMap.put(headers[i].trim(), i);

                    String[] selectedCols;
                    boolean  hasNew = hMap.containsKey(NEW_FEATURE_COLS[0]);
                    boolean  hasOld = hMap.containsKey(OLD_FEATURE_COLS[0]);

                    if (hasNew) {
                        selectedCols = NEW_FEATURE_COLS;
                        Log.d(TAG, "Using new column format (D1_acc_X)");
                    } else if (hasOld) {
                        selectedCols = OLD_FEATURE_COLS;
                        Log.d(TAG, "Using old column format (d1_ax)");
                    } else {
                        throw new IllegalArgumentException(
                                "CSV missing required IMU columns. Expected D1_acc_X or d1_ax format.");
                    }

                    for (int i = 0; i < 12; i++) {
                        Integer idx = hMap.get(selectedCols[i]);
                        if (idx == null)
                            throw new IllegalArgumentException("Missing column: " + selectedCols[i]);
                        colIdx[i] = idx;
                    }
                    tsCol  = hMap.getOrDefault("timestamp", -1);
                    actCol = hMap.getOrDefault("activity",  -1);

                    while ((line = br.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        String[] parts = line.split(",");
                        float[] row = new float[12];
                        for (int i = 0; i < 12; i++)
                            row[i] = parseFloat(parts, colIdx[i]);
                        dataRows.add(row);
                        timestamps.add(tsCol >= 0 && tsCol < parts.length
                                ? parts[tsCol].trim() : "");
                        activities.add(actCol >= 0 && actCol < parts.length
                                ? parts[actCol].trim() : "UNKNOWN");
                    }
                }

                int totalRows    = dataRows.size();
                int totalWindows = totalRows / WINDOW_SIZE;

                if (totalWindows == 0) {
                    setStatus("❌ Need ≥ " + WINDOW_SIZE + " rows, got " + totalRows);
                    showProgress(false); return;
                }

                setStatus("Inferring " + totalWindows + " windows…");
                final int total = totalWindows;
                requireActivity().runOnUiThread(() -> {
                    progressBar.setMax(total); progressBar.setProgress(0);
                });

                int correctCount = 0;

                for (int w = 0; w < totalWindows; w++) {

                    int startRow = w * WINDOW_SIZE;
                    float[][] segment = new float[WINDOW_SIZE][12];
                    for (int r = 0; r < WINDOW_SIZE; r++)
                        segment[r] = dataRows.get(startRow + r);

                    float[] features = FeatureExtractor.extract(segment);
                    float[][] inputArr = new float[1][features.length];
                    inputArr[0] = features;

                    long t0 = System.nanoTime();

                    OnnxTensor tensor = OnnxTensor.createTensor(viewModel.ortEnv, inputArr);
                    OrtSession.Result result = viewModel.ortSession.run(
                            Collections.singletonMap("float_input", tensor));

                    double latencyMs = (System.nanoTime() - t0) / 1_000_000.0;

                    long[] rawLabels     = (long[]) result.get("label").get().getValue();
                    int    predictedEnc  = (int) rawLabels[0];         // 0-indexed (0..15)
                    int    predictedCode = predictedEnc + 1;            // 1-indexed (1..16)
                    String predictedName = LabelMapper.toText(predictedCode).toLowerCase();
                    float confidence = extractConfidenceWithSoftmax(result, predictedEnc);

                    tensor.close();
                    result.close();

                    String tsFormatted = formatTimestamp(timestamps.get(startRow));

                    int actualCode = getMajorityLabelCode(activities, startRow, WINDOW_SIZE);
                    String actualLabel = LabelMapper.toText(actualCode).toLowerCase();
                    int valid = predictedName.equalsIgnoreCase(actualLabel) ? 1 : 0;
                    if (valid == 1) correctCount++;

                    String confStr    = String.format(Locale.US, "%.3f", confidence);
                    String latencyStr = String.format(Locale.US, "%.2f", latencyMs);

                    viewModel.outputRows.add(tsFormatted + "," +
                            actualLabel   + "," +
                            predictedName + "," +
                            confStr       + "," +
                            latencyStr    + "," +
                            valid);

                    final int done = w + 1;
                    requireActivity().runOnUiThread(() ->
                            progressBar.setProgressCompat(done, true));
                }

                final int   fc  = correctCount;
                final int   ft  = totalWindows;
                final float acc = ft > 0 ? (float) fc / ft * 100f : 0f;
                final String accStr = String.format(Locale.US, "%.1f", acc);

                requireActivity().runOnUiThread(() -> {
                    setStatus("✅ Done — " + ft + " windows inferred");
                    viewModel.summaryText = "Windows: " + ft
                            + "  Correct: " + fc
                            + "  Accuracy: " + accStr + "%";
                    if (txtSummary != null)
                        txtSummary.setText(viewModel.summaryText);
                    showProgress(false);
                    if (btnExportResult != null) btnExportResult.setEnabled(true);
                });

            } catch (Exception e) {
                Log.e(TAG, "Inference failed", e);
                setStatus("❌ Error: " + e.getMessage());
                showProgress(false);
            }
        });
    }
    private int getMajorityLabelCode(List<String> activities, int start, int size) {
        int[] count = new int[17]; // 1..16

        for (int i = 0; i < size; i++) {
            String label = normalizeLabel(activities.get(start + i));
            int code = getLabelCode(label);

            if (code > 0) {
                count[code]++;
            }
        }

        int max = 0;
        int majority = 0;

        for (int i = 1; i <= 16; i++) {
            if (count[i] > max) {
                max = count[i];
                majority = i;
            }
        }

        return majority;
    }
    private int getLabelCode(String label) {
        if (label == null) return 0;

        String cleaned = label
                .replaceAll("[\\r\\n]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase();

        return LabelMapper.getIdFromName(cleaned);
    }

    private float extractConfidenceWithSoftmax(OrtSession.Result result, int predictedEnc) {
        try {
            Object probRaw = result.get("probabilities").get().getValue();
            float[] rawScores = null;

            if (probRaw instanceof float[][]) {
                rawScores = ((float[][]) probRaw)[0];
            } else if (probRaw instanceof float[]) {
                rawScores = (float[]) probRaw;
            } else if (probRaw instanceof Object[]) {
                Object first = ((Object[]) probRaw)[0];
                if (first instanceof float[]) rawScores = (float[]) first;
            }

            if (rawScores == null || rawScores.length == 0) {
                Log.w(TAG, "No raw scores");
                return 0f;
            }

            float max = rawScores[0];
            for (float s : rawScores) if (s > max) max = s;

            float[] expScores = new float[rawScores.length];
            float   sumExp    = 0f;
            for (int i = 0; i < rawScores.length; i++) {
                expScores[i] = (float) Math.exp(rawScores[i] - max);
                sumExp       += expScores[i];
            }

            float[] softmax = new float[rawScores.length];
            for (int i = 0; i < rawScores.length; i++)
                softmax[i] = expScores[i] / sumExp;

            if (predictedEnc >= 0 && predictedEnc < softmax.length)
                return softmax[predictedEnc];

            float mx = 0f;
            for (float p : softmax) if (p > mx) mx = p;
            return mx;

        } catch (Exception e) {
            Log.w(TAG, "Confidence failed: " + e.getMessage());
            return 0f;
        }
    }

    private static String normalizeLabel(String raw) {
        if (raw == null) return "unknown";

        String cleaned = raw
                .replaceAll("[\\r\\n]", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();

        String alias = LEGACY_ALIASES.get(cleaned);
        return alias != null ? alias : cleaned;
    }

    private void exportOutputCsv(Uri uri) {
        inferenceHandler.post(() -> {
            try (OutputStream os =
                         requireContext().getContentResolver().openOutputStream(uri);
                 BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os))) {
                for (String row : viewModel.outputRows) { bw.write(row); bw.newLine(); }
                final int count = viewModel.outputRows.size() - 1;
                requireActivity().runOnUiThread(() ->
                        toast("Exported " + count + " windows"));
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                toast("Export failed: " + e.getMessage());
            }
        });
    }

    private String formatTimestamp(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            return outTsFmt.format(new Date(Long.parseLong(raw.trim())));
        } catch (NumberFormatException e) {
            return raw.trim();
        }
    }

    private static float parseFloat(String[] parts, int idx) {
        try {
            if (idx < 0 || idx >= parts.length) return 0f;
            return Float.parseFloat(parts[idx].trim());
        } catch (NumberFormatException e) { return 0f; }
    }

    private void setStatus(String msg) {
        if (getActivity() == null || txtStatus == null) return;
        requireActivity().runOnUiThread(() -> txtStatus.setText(msg));
    }

    private void showProgress(boolean show) {
        if (getActivity() == null || progressBar == null) return;
        requireActivity().runOnUiThread(() ->
                progressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    private void toast(String msg) {
        if (getActivity() == null) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        inferenceThread.quitSafely();
        // Closing of ortSession and ortEnv is now handled by ViewModel.onCleared()
    }
}