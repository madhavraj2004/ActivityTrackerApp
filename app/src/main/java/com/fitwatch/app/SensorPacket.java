package com.fitwatch.app;

import android.util.Log;

public class SensorPacket {

    public long imuTimestamp;
    public int predictedLabel;

    public float ax, ay, az;
    public float gx, gy, gz;

    public static SensorPacket fromCsv(String line) {

        try {
            String[] p = line.split(",");

            // 🔥 DEBUG
            Log.d("CSV_RAW", line);
            Log.d("CSV_LEN", "len=" + p.length);

            if (p.length < 14) return null;

            SensorPacket s = new SensorPacket();

            // ✅ SAFE parsing
            s.imuTimestamp = parseLongSafe(p[1]);

            // 🔥 IMPORTANT (4th column = index 3)
            s.predictedLabel = parseIntSafe(p[3]);

            s.ax = parseFloatSafe(p[8]);
            s.ay = parseFloatSafe(p[9]);
            s.az = parseFloatSafe(p[10]);

            s.gx = parseFloatSafe(p[11]);
            s.gy = parseFloatSafe(p[12]);
            s.gz = parseFloatSafe(p[13]);

            Log.d("CSV_PARSED", "P=" + s.predictedLabel);

            return s;

        } catch (Exception e) {
            Log.e("CSV_ERROR", "Parse failed: " + line);
            return null;
        }
    }

    private static int parseIntSafe(String v) {
        try { return Integer.parseInt(v.trim()); }
        catch (Exception e) { return 0; }
    }

    private static long parseLongSafe(String v) {
        try { return Long.parseLong(v.trim()); }
        catch (Exception e) { return 0; }
    }

    private static float parseFloatSafe(String v) {
        try { return Float.parseFloat(v.trim()); }
        catch (Exception e) { return 0f; }
    }

    public String shortAxes() {
        return String.format("AX:%.2f AY:%.2f AZ:%.2f", ax, ay, az);
    }
}