package com.fitwatch.app;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PredictionManager {

    public int modeRightCode(List<SyncRow> window) {
        return mode(window, true);
    }

    public int modeLeftCode(List<SyncRow> window) {
        return mode(window, false);
    }

    private int mode(List<SyncRow> window, boolean right) {
        if (window == null || window.isEmpty()) return 0;

        Map<Integer, Integer> freq = new HashMap<>();
        for (SyncRow row : window) {
            int code = right ? row.d1.predictedLabel : row.d2.predictedLabel;
            freq.put(code, freq.getOrDefault(code, 0) + 1);
        }

        int bestCode = 0;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                bestCode = e.getKey();
            }
        }
        return bestCode;
    }
}