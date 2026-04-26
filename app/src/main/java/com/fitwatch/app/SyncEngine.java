package com.fitwatch.app;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class SyncEngine {

    public interface LiveListener {
        void onNewRow(SyncRow row);
    }

    public interface WindowListener {
        void onWindowReady(List<SyncRow> window);
    }

    // ─── Singleton ────────────────────────────────────────────────────────────
    private static SyncEngine instance;

    public static SyncEngine getInstance() {
        if (instance == null) {
            instance = new SyncEngine();
        }
        return instance;
    }

    // ─── Listeners ────────────────────────────────────────────────────────────
    // TWO separate live listeners so ActivityFragment and InferenceFragment
    // never overwrite each other. Both receive rows independently.
    private LiveListener   liveListener;       // ActivityFragment → CSV + buffer + speech
    private LiveListener   inferenceListener;  // InferenceFragment → live ONNX inference
    private WindowListener windowListener;     // PredictionManager mode window

    public void setLiveListener(LiveListener l)      { this.liveListener      = l; }
    public void setInferenceListener(LiveListener l) { this.inferenceListener = l; }
    public void setWindowListener(WindowListener l)  { this.windowListener    = l; }

    // ─── Single serial thread for ALL sync work ───────────────────────────────
    private final HandlerThread syncThread;
    private final Handler       syncHandler;

    private final Queue<SensorPacket> rightQueue = new LinkedList<>();
    private final Queue<SensorPacket> leftQueue  = new LinkedList<>();

    private SensorPacket lastRightUsed = null;
    private SensorPacket lastLeftUsed  = null;

    private long lastRightTs = -1;
    private long lastLeftTs  = -1;

    // ─── Both-sides-fresh gate (CSV write only) ───────────────────────────────
    private boolean rightFreshSinceWrite = false;
    private boolean leftFreshSinceWrite  = false;

    private final List<SyncRow> window = new ArrayList<>();

    // ─── Dedicated write thread so CSV I/O never stalls sync ─────────────────
    private final HandlerThread writeThread;
    private final Handler       writeHandler;

    public SyncEngine() {
        syncThread = new HandlerThread("SyncEngine-Thread");
        syncThread.start();
        syncHandler = new Handler(syncThread.getLooper());

        writeThread = new HandlerThread("CsvWrite-Thread");
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());
    }

    // ─── PUBLIC: called from BLE callback thread ──────────────────────────────

    public void addRight(SensorPacket packet) {
        if (packet == null) return;
        syncHandler.post(() -> {
            detectLoss("RIGHT", packet.imuTimestamp, lastRightTs);
            lastRightTs          = packet.imuTimestamp;
            rightFreshSinceWrite = true;
            rightQueue.add(packet);
            drainQueues();
        });
    }

    public void addLeft(SensorPacket packet) {
        if (packet == null) return;
        syncHandler.post(() -> {
            detectLoss("LEFT", packet.imuTimestamp, lastLeftTs);
            lastLeftTs          = packet.imuTimestamp;
            leftFreshSinceWrite = true;
            leftQueue.add(packet);
            drainQueues();
        });
    }

    // ─── PRIVATE: runs only on syncHandler thread ─────────────────────────────

    private void drainQueues() {

        while (true) {

            SensorPacket r = rightQueue.peek();
            SensorPacket l = leftQueue.peek();

            if (r == null && l == null) break;

            if (r != null && l != null) {
                long diff = Math.abs(r.imuTimestamp - l.imuTimestamp);

                if (diff <= 40) {
                    rightQueue.poll(); leftQueue.poll();
                    lastRightUsed = r; lastLeftUsed = l;
                    emitRow(r, l);
                } else if (r.imuTimestamp < l.imuTimestamp) {
                    rightQueue.poll();
                    lastRightUsed = r;
                    emitRow(r, lastLeftUsed);
                } else {
                    leftQueue.poll();
                    lastLeftUsed = l;
                    emitRow(lastRightUsed, l);
                }
            } else if (r != null) {
                rightQueue.poll();
                lastRightUsed = r;
                emitRow(r, lastLeftUsed);
            } else {
                leftQueue.poll();
                lastLeftUsed = l;
                emitRow(lastRightUsed, l);
            }
        }
    }

    private void emitRow(SensorPacket r, SensorPacket l) {

        SensorPacket resolvedR = (r != null) ? r : lastRightUsed;
        SensorPacket resolvedL = (l != null) ? l : lastLeftUsed;

        // Both sides must be non-null — skip until both devices have connected
        if (resolvedR == null || resolvedL == null) return;

        long    ts  = System.currentTimeMillis();
        SyncRow row = new SyncRow(ts, resolvedR, resolvedL);

        // ── Both-sides-fresh gate ─────────────────────────────────────────────
        // liveListener (CSV + buffer + speech) only fires when BOTH sides have
        // each sent at least one new packet since the last write.
        // This eliminates repeated-timestamp rows in the recorded CSV.
        if (rightFreshSinceWrite && leftFreshSinceWrite) {
            if (liveListener != null) {
                liveListener.onNewRow(row);
            }
            rightFreshSinceWrite = false;
            leftFreshSinceWrite  = false;
        }

        // ── Inference listener — fires on EVERY resolved row ──────────────────
        // InferenceFragment needs a continuous stream at ~20Hz to fill its
        // 60-row window. It must NOT be gated — the fresh gate would starve it
        // to ~10Hz (one side at a time) which halves inference rate.
        if (inferenceListener != null) {
            inferenceListener.onNewRow(row);
        }

        // ── PredictionManager window ──────────────────────────────────────────
        window.add(row);
        if (window.size() >= 120) {
            if (windowListener != null) {
                windowListener.onWindowReady(new ArrayList<>(window));
            }
            window.clear();
        }
    }

    // ─── Loss detector ────────────────────────────────────────────────────────

    private void detectLoss(String side, long currentTs, long lastTs) {
        if (lastTs == -1) return;
        long gap = currentTs - lastTs;
        if (gap > 80) {
            int missed = (int) ((gap / 50) - 1);
            Log.e("LOSS_" + side, "❌ Gap=" + gap + "ms → ~" + missed + " packet(s) missed");
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    public void reset() {
        syncHandler.post(() -> {
            rightQueue.clear();
            leftQueue.clear();
            window.clear();
            lastRightUsed        = null;
            lastLeftUsed         = null;
            lastRightTs          = -1;
            lastLeftTs           = -1;
            rightFreshSinceWrite = false;
            leftFreshSinceWrite  = false;
        });
    }

    public void shutdown() {
        syncThread.quitSafely();
        writeThread.quitSafely();
    }
}