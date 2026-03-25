package com.fitwatch.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CsvManager {

    private static final String TAG = "CsvManager";

    // ─── Thread-safe row store ────────────────────────────────────────────────
    // CopyOnWriteArrayList lets exportToUri() iterate safely while write()
    // is still adding rows from the writeThread.
    private final List<String> rows = new CopyOnWriteArrayList<>();

    // ─── Dedicated write thread ───────────────────────────────────────────────
    // All write() calls are posted here so disk I/O never touches the sync or
    // BLE callback threads. Single thread = rows always written in order.
    private final HandlerThread writeThread;
    private final Handler writeHandler;

    private volatile boolean active = false;

    public CsvManager() {
        writeThread = new HandlerThread("CsvManager-WriteThread");
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());
    }

    // ─── Called once when recording starts ───────────────────────────────────

    public void start(Context ctx) {
        rows.clear();
        rows.add(
                "timestamp,activity," +
                        "d1_imu_ts,d1_predicted,d1_ax,d1_ay,d1_az,d1_gx,d1_gy,d1_gz," +
                        "d2_imu_ts,d2_predicted,d2_ax,d2_ay,d2_az,d2_gx,d2_gy,d2_gz"
        );
        active = true;
        Log.d(TAG, "Recording started");
    }

    // ─── Called for every SyncRow — safe to call from ANY thread ─────────────

    public void write(SyncRow r, String activity) {

        if (!active) return;
        if (r == null || r.d1 == null || r.d2 == null) return;

        // Capture everything needed for the write before posting,
        // so there is no shared-state risk on the lambda.
        final String line =
                r.timestamp         + "," +
                        activity            + "," +
                        r.d1.imuTimestamp   + "," +
                        r.d1.predictedLabel + "," +
                        r.d1.ax             + "," +
                        r.d1.ay             + "," +
                        r.d1.az             + "," +
                        r.d1.gx             + "," +
                        r.d1.gy             + "," +
                        r.d1.gz             + "," +
                        r.d2.imuTimestamp   + "," +
                        r.d2.predictedLabel + "," +
                        r.d2.ax             + "," +
                        r.d2.ay             + "," +
                        r.d2.az             + "," +
                        r.d2.gx             + "," +
                        r.d2.gy             + "," +
                        r.d2.gz;

        // Post to writeThread — returns immediately, never blocks caller
        writeHandler.post(() -> {
            rows.add(line);
            Log.v(TAG, "Row written, total=" + rows.size());
        });
    }

    // ─── Called when recording stops ─────────────────────────────────────────

    public void stop() {
        // Drain any pending writes before marking inactive.
        // postAtFrontOfQueue so no new writes sneak in after this.
        writeHandler.post(() -> {
            active = false;
            Log.d(TAG, "Recording stopped, rows=" + rows.size());
        });
    }

    // ─── Export ───────────────────────────────────────────────────────────────

    public boolean hasFile() {
        return rows.size() > 1; // more than just the header
    }

    /**
     * Exports to the given Uri. Call this AFTER stop() returns, or at minimum
     * after the user taps Export (by which point all BLE data has been written).
     * Runs on whatever thread called it — fine since CopyOnWriteArrayList is safe.
     */
    public void exportToUri(Context ctx, Uri uri) {
        try {
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                Log.e(TAG, "Cannot open output stream for URI");
                return;
            }
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));
            for (String row : rows) {   // CopyOnWriteArrayList iterates snapshot
                writer.write(row);
                writer.newLine();
            }
            writer.close();
            Log.d(TAG, "Export complete, rows=" + rows.size());
        } catch (Exception e) {
            Log.e(TAG, "Export failed", e);
        }
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    public void shutdown() {
        writeThread.quitSafely();
    }
}