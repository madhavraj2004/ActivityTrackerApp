package com.fitwatch.app;

import android.content.Context;
import android.net.Uri;

import java.io.*;
import java.util.*;

public class CsvManager {

    private List<String> rows = new ArrayList<>();

    public void start(Context ctx) {
        rows.clear();

        rows.add("timestamp,activity," +
                "d1_imu_ts,d1_predicted,d1_ax,d1_ay,d1_az,d1_gx,d1_gy,d1_gz," +
                "d2_imu_ts,d2_predicted,d2_ax,d2_ay,d2_az,d2_gx,d2_gy,d2_gz");
    }

    public void write(SyncRow r, String activity) {

        String line =
                r.timestamp + "," + activity + "," +

                        r.d1.imuTimestamp + "," + r.d1.predictedLabel + "," +
                        r.d1.ax + "," + r.d1.ay + "," + r.d1.az + "," +
                        r.d1.gx + "," + r.d1.gy + "," + r.d1.gz + "," +

                        r.d2.imuTimestamp + "," + r.d2.predictedLabel + "," +
                        r.d2.ax + "," + r.d2.ay + "," + r.d2.az + "," +
                        r.d2.gx + "," + r.d2.gy + "," + r.d2.gz;

        rows.add(line);
    }

    public void stop() {}

    public boolean hasFile() {
        return !rows.isEmpty();
    }

    public void exportToUri(Context ctx, Uri uri) {
        try {
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os));

            for (String row : rows) {
                writer.write(row);
                writer.newLine();
            }

            writer.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}