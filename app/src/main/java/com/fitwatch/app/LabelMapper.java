package com.fitwatch.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * LabelMapper stores the human-readable name for each numeric activity label.
 *
 * CRITICAL: The default names here MUST match the LABEL_MAP in InferenceFragment
 * exactly (case-insensitive). If they differ, the Valid column in the inference
 * CSV will always be 0 even when the prediction is correct.
 *
 * Notebook label_map  →  stored here (uppercase)  →  LABEL_MAP in InferenceFragment (lowercase)
 * "walk 1"            →  WALK                     →  "walk"              ✅
 * "drinking (left)"   →  DRINKING (LEFT)           →  "drinking (left)"   ✅  ← was "DRINK LEFT" before
 * "phone call (left)" →  PHONE CALL (LEFT)         →  "phone call (left)" ✅  ← was "PHONE LEFT" before
 */
public class LabelMapper {

    private static final String PREF = "label_map";
    private static final String KEY  = "map";

    private static Map<Integer, String> map = new HashMap<>();

    public static void init(Context ctx) {

        if (!map.isEmpty()) return;

        // ── Default labels — names match LABEL_MAP in InferenceFragment exactly ──
        // Format: UPPERCASE stored here; lowercased at comparison time in InferenceFragment
        map.put(1,  "WALK");
        map.put(2,  "RUN");
        map.put(3,  "SIT");
        map.put(4,  "STAND");
        map.put(5,  "STAIR DOWN");
        map.put(6,  "STAIR UP");
        map.put(7,  "CLAPPING");
        map.put(8,  "COUGHING");
        map.put(9,  "DRINKING (LEFT)");    // was "DRINK LEFT"  — fixed
        map.put(10, "DRINKING (RIGHT)");   // was "DRINK RIGHT" — fixed
        map.put(11, "KNOCKING (LEFT)");    // was "KNOCK LEFT"  — fixed
        map.put(12, "KNOCKING (RIGHT)");   // was "KNOCK RIGHT" — fixed
        map.put(13, "PHONE CALL (LEFT)");  // was "PHONE LEFT"  — fixed
        map.put(14, "PHONE CALL (RIGHT)"); // was "PHONE RIGHT" — fixed
        map.put(15, "CYCLING");
        map.put(16, "KEYBOARD TYPING");

        load(ctx);
    }

    public static String toText(int label) {
        return map.getOrDefault(label, "UNKNOWN");
    }

    public static int getNextLabel() {
        int max = 0;
        for (int k : map.keySet()) if (k > max) max = k;
        return max + 1;
    }

    public static boolean addLabel(Context ctx, int id, String name) {
        name = name.toUpperCase();
        if (map.containsKey(id))    return false;
        if (map.containsValue(name)) return false;
        map.put(id, name);
        save(ctx);
        return true;
    }

    public static void removeLabel(Context ctx, int id) {
        map.remove(id);
        save(ctx);
    }

    public static int getIdFromName(String name) {
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return -1;
    }

    public static Map<Integer, String> getAll() {
        return map;
    }

    private static void save(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : map.entrySet())
            sb.append(e.getKey()).append(":").append(e.getValue()).append(",");
        sp.edit().putString(KEY, sb.toString()).apply();
    }

    private static void load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY, null);
        if (saved == null) return;
        for (String e : saved.split(",")) {
            if (!e.contains(":")) continue;
            String[] p = e.split(":", 2);
            try { map.put(Integer.parseInt(p[0]), p[1]); }
            catch (Exception ignored) {}
        }
    }
}