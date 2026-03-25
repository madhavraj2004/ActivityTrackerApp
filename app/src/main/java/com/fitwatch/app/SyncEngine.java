package com.fitwatch.app;

import java.util.*;

public class SyncEngine {

    public interface LiveListener {
        void onNewRow(SyncRow row);
    }

    public interface WindowListener {
        void onWindowReady(List<SyncRow> window);
    }

    private LiveListener liveListener;
    private WindowListener windowListener;

    private final Queue<SensorPacket> rightQueue = new LinkedList<>();
    private final Queue<SensorPacket> leftQueue  = new LinkedList<>();

    private long lastRightTs = -1;
    private long lastLeftTs  = -1;

    private SensorPacket lastRightUsed = null;
    private SensorPacket lastLeftUsed  = null;

    private final List<SyncRow> window = new ArrayList<>();

    public void setLiveListener(LiveListener l) {
        this.liveListener = l;
    }

    public void setWindowListener(WindowListener l) {
        this.windowListener = l;
    }

    // ================= RIGHT =================
    public void addRight(SensorPacket packet) {

        if (packet == null) return;

        // 🔥 DATA LOSS DETECTION
        if (lastRightTs != -1) {
            long diff = packet.imuTimestamp - lastRightTs;

            if (diff > 80) {
                android.util.Log.e("LOSS_RIGHT",
                        "❌ Missing RIGHT packet gap=" + diff);
            }
        }

        lastRightTs = packet.imuTimestamp;

        rightQueue.add(packet);

        trySync();
    }

    // ================= LEFT =================
    public void addLeft(SensorPacket packet) {

        if (packet == null) return;

        if (lastLeftTs != -1) {
            long diff = packet.imuTimestamp - lastLeftTs;

            if (diff > 80) {
                android.util.Log.e("LOSS_LEFT",
                        "❌ Missing LEFT packet gap=" + diff);
            }
        }

        lastLeftTs = packet.imuTimestamp;

        leftQueue.add(packet);

        trySync();
    }

    // ================= SYNC =================
    private void trySync() {

        while (!rightQueue.isEmpty() || !leftQueue.isEmpty()) {

            SensorPacket r = rightQueue.peek();
            SensorPacket l = leftQueue.peek();

            // BOTH AVAILABLE
            if (r != null && l != null) {

                long diff = Math.abs(r.imuTimestamp - l.imuTimestamp);

                if (diff <= 40) {

                    rightQueue.poll();
                    leftQueue.poll();

                    lastRightUsed = r;
                    lastLeftUsed  = l;

                    createRow(r, l);
                }
                else if (r.imuTimestamp < l.imuTimestamp) {

                    rightQueue.poll();
                    lastRightUsed = r;

                    createRow(r, lastLeftUsed);
                }
                else {

                    leftQueue.poll();
                    lastLeftUsed = l;

                    createRow(lastRightUsed, l);
                }
            }

            // ONLY RIGHT
            else if (r != null) {

                rightQueue.poll();
                lastRightUsed = r;

                createRow(r, lastLeftUsed);
            }

            // ONLY LEFT
            else if (l != null) {

                leftQueue.poll();
                lastLeftUsed = l;

                createRow(lastRightUsed, l);
            }
        }
    }

    // ================= CREATE ROW =================
    private void createRow(SensorPacket r, SensorPacket l) {

        if (r == null && l == null) return;

        long ts = System.currentTimeMillis();

        SyncRow row = new SyncRow(
                ts,
                r != null ? r : lastRightUsed,
                l != null ? l : lastLeftUsed
        );

        if (liveListener != null) {
            liveListener.onNewRow(row);
        }

        window.add(row);

        if (window.size() >= 120) {
            if (windowListener != null) {
                windowListener.onWindowReady(new ArrayList<>(window));
            }
            window.clear();
        }
    }

    public void reset() {
        rightQueue.clear();
        leftQueue.clear();
        window.clear();
        lastRightUsed = null;
        lastLeftUsed = null;
    }
}