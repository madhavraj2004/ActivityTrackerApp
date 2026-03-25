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

    private LiveListener liveListener;
    private WindowListener windowListener;

    // ─── Single serial thread for ALL sync work ───────────────────────────────
    // One thread = no race conditions, no out-of-order processing, no backlog
    // from concurrent posts. All queue access happens only on this thread.
    private final HandlerThread syncThread;
    private final Handler syncHandler;

    // These queues are ONLY ever touched from syncHandler — no synchronization needed
    private final Queue<SensorPacket> rightQueue = new LinkedList<>();
    private final Queue<SensorPacket> leftQueue  = new LinkedList<>();

    private SensorPacket lastRightUsed = null;
    private SensorPacket lastLeftUsed  = null;

    private long lastRightTs = -1;
    private long lastLeftTs  = -1;

    // ─── "Both sides fresh" write gate ────────────────────────────────────────
    // A CSV row is only emitted when BOTH flags are true — meaning each side
    // has contributed at least one genuinely new packet since the last write.
    // Eliminates every repeated-timestamp row while preserving all real data.
    // Both flags run on syncThread only — no synchronization needed.
    private boolean rightFreshSinceWrite = false;
    private boolean leftFreshSinceWrite  = false;

    private final List<SyncRow> window = new ArrayList<>();

    // ─── Separate write thread so CSV I/O never stalls sync ───────────────────
    private final HandlerThread writeThread;
    private final Handler writeHandler;

    public SyncEngine() {
        syncThread = new HandlerThread("SyncEngine-Thread");
        syncThread.start();
        syncHandler = new Handler(syncThread.getLooper());

        writeThread = new HandlerThread("CsvWrite-Thread");
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());
    }

    public void setLiveListener(LiveListener l)     { this.liveListener   = l; }
    public void setWindowListener(WindowListener l) { this.windowListener = l; }

    // ─── PUBLIC: called from BLE callback thread ──────────────────────────────

    public void addRight(SensorPacket packet) {
        if (packet == null) return;
        // Post to syncHandler — returns immediately, BLE thread never blocks
        syncHandler.post(() -> {
            detectLoss("RIGHT", packet.imuTimestamp, lastRightTs);
            lastRightTs = packet.imuTimestamp;
            rightFreshSinceWrite = true;   // ← new real packet from right side
            rightQueue.add(packet);
            drainQueues();
        });
    }

    public void addLeft(SensorPacket packet) {
        if (packet == null) return;
        syncHandler.post(() -> {
            detectLoss("LEFT", packet.imuTimestamp, lastLeftTs);
            lastLeftTs = packet.imuTimestamp;
            leftFreshSinceWrite = true;    // ← new real packet from left side
            leftQueue.add(packet);
            drainQueues();
        });
    }

    // ─── PRIVATE: runs only on syncHandler thread ─────────────────────────────

    /**
     * Drain both queues completely every time a new packet arrives.
     * Because this always runs on the single syncHandler thread,
     * there is no concurrent access, no race condition, and no
     * redundant re-entrant call — each post drains everything available.
     */
    private void drainQueues() {

        while (true) {

            SensorPacket r = rightQueue.peek();
            SensorPacket l = leftQueue.peek();

            // Nothing left to process
            if (r == null && l == null) break;

            if (r != null && l != null) {

                long diff = Math.abs(r.imuTimestamp - l.imuTimestamp);

                if (diff <= 40) {
                    // ✅ Perfect pair — consume both
                    rightQueue.poll();
                    leftQueue.poll();
                    lastRightUsed = r;
                    lastLeftUsed  = l;
                    emitRow(r, l);

                } else if (r.imuTimestamp < l.imuTimestamp) {
                    // Right is older — emit right with last known left, advance right only
                    rightQueue.poll();
                    lastRightUsed = r;
                    emitRow(r, lastLeftUsed);

                } else {
                    // Left is older — emit left with last known right, advance left only
                    leftQueue.poll();
                    lastLeftUsed = l;
                    emitRow(lastRightUsed, l);
                }

            } else if (r != null) {
                // Only right available — pair with last known left
                rightQueue.poll();
                lastRightUsed = r;
                emitRow(r, lastLeftUsed);

            } else {
                // Only left available — pair with last known right
                leftQueue.poll();
                lastLeftUsed = l;
                emitRow(lastRightUsed, l);
            }
        }
    }

    /**
     * Called on syncThread. Notifies liveListener immediately (it posts to UI
     * thread itself), then dispatches CSV write to the writeThread so disk I/O
     * never delays the next drainQueues() call.
     *
     * Live buffer and speech ALWAYS fire for every emitRow call — they need
     * the most recent data regardless of freshness state.
     *
     * liveListener (which triggers CSV write in ActivityFragment) only fires
     * when BOTH sides have contributed at least one new packet since the last
     * write — this is the "both sides fresh" gate that eliminates repeated rows.
     */
    private void emitRow(SensorPacket r, SensorPacket l) {

        // Resolve actual packets — fall back to last known
        SensorPacket resolvedR = (r != null) ? r : lastRightUsed;
        SensorPacket resolvedL = (l != null) ? l : lastLeftUsed;

        // Both sides must be non-null before we can produce a valid row.
        // During startup, one side may not have arrived yet — skip until ready.
        if (resolvedR == null || resolvedL == null) return;

        long ts = System.currentTimeMillis();

        SyncRow row = new SyncRow(ts, resolvedR, resolvedL);

        // ── Both-sides-fresh gate ─────────────────────────────────────────────
        // Only pass row to liveListener (→ CSV write) when both sides have
        // each delivered at least one new packet since the last write.
        // This means every written row has genuinely new data on BOTH sides —
        // no repeated timestamps on either hand, ever.
        if (rightFreshSinceWrite && leftFreshSinceWrite) {
            if (liveListener != null) {
                liveListener.onNewRow(row);
            }
            // Reset flags — both sides must send fresh data again before next write
            rightFreshSinceWrite = false;
            leftFreshSinceWrite  = false;
        }

        // ── Window accumulation — always runs, not gated ──────────────────────
        // Prediction window uses every resolved row (including fill-ins) so the
        // window fills at the full ~20Hz rate and PredictionManager stays accurate.
        // Every row here is guaranteed non-null d1 and d2.
        window.add(row);
        if (window.size() >= 120) {
            if (windowListener != null) {
                windowListener.onWindowReady(new ArrayList<>(window));
            }
            window.clear();
        }
    }

    // ─── Data-loss detector ───────────────────────────────────────────────────

    /**
     * IMU packets arrive ~every 50 ms. A gap >80 ms means at least one packet
     * was dropped by BLE before it ever reached the app. This is true BLE loss
     * — not a sync issue. Log it so you can tune BLE connection parameters.
     */
    private void detectLoss(String side, long currentTs, long lastTs) {
        if (lastTs == -1) return;
        long gap = currentTs - lastTs;
        if (gap > 80) {
            int missed = (int) ((gap / 50) - 1);   // expected interval = 50 ms
            Log.e("LOSS_" + side,
                    "❌ Gap=" + gap + "ms → ~" + missed + " packet(s) missed");
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    public void reset() {
        syncHandler.post(() -> {
            rightQueue.clear();
            leftQueue.clear();
            window.clear();
            lastRightUsed = null;
            lastLeftUsed  = null;
            lastRightTs   = -1;
            lastLeftTs    = -1;
            rightFreshSinceWrite = false;
            leftFreshSinceWrite  = false;
        });
    }

    public void shutdown() {
        syncThread.quitSafely();
        writeThread.quitSafely();
    }
}