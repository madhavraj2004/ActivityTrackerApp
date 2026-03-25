package com.fitwatch.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

public class LabelMapper {

    private static final String PREF = "label_map";
    private static final String KEY = "map";

    private static Map<Integer, String> map = new HashMap<>();

    // 🔥 INIT DEFAULT LABELS
    public static void init(Context ctx) {

        if (!map.isEmpty()) return;

        map.put(1, "WALK");
        map.put(2, "RUN");
        map.put(3, "SIT");
        map.put(4, "STAND");
        map.put(5, "STAIR DOWN");
        map.put(6, "STAIR UP");
        map.put(7, "CLAPPING");
        map.put(8, "COUGHING");
        map.put(9, "DRINK LEFT");
        map.put(10, "DRINK RIGHT");
        map.put(11, "KNOCK LEFT");
        map.put(12, "KNOCK RIGHT");
        map.put(13, "PHONE LEFT");
        map.put(14, "PHONE RIGHT");
        map.put(15, "CYCLING");
        map.put(16, "KEYBOARD TYPING");

        load(ctx);
    }

    public static String toText(int label) {
        return map.getOrDefault(label, "UNKNOWN");
    }

    public static int getNextLabel() {
        int max = 0;
        for (int k : map.keySet()) {
            if (k > max) max = k;
        }
        return max + 1;
    }

    public static boolean addLabel(Context ctx, int id, String name) {

        name = name.toUpperCase();

        // ❌ Prevent duplicate ID
        if (map.containsKey(id)) return false;

        // ❌ Prevent duplicate name
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
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }

        return -1;
    }
    public static Map<Integer, String> getAll() {
        return map;
    }

    // 🔥 SAVE / LOAD
    private static void save(Context ctx) {

        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<Integer, String> e : map.entrySet()) {
            sb.append(e.getKey()).append(":").append(e.getValue()).append(",");
        }

        sp.edit().putString(KEY, sb.toString()).apply();
    }

    private static void load(Context ctx) {

        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);

        String saved = sp.getString(KEY, null);
        if (saved == null) return;

        String[] entries = saved.split(",");

        for (String e : entries) {
            if (!e.contains(":")) continue;

            String[] p = e.split(":");

            try {
                map.put(Integer.parseInt(p[0]), p[1]);
            } catch (Exception ignored) {}
        }
    }
}