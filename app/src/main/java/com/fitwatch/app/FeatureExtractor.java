package com.fitwatch.app;

/**
 * Extracts the exact 276 features used to train the ONNX model.
 *
 * Input:  float[WINDOW_SIZE][12]  — 60 rows × 12 columns
 *         col order: D1_ax, D1_ay, D1_az, D1_gx, D1_gy, D1_gz,
 *                    D2_ax, D2_ay, D2_az, D2_gx, D2_gy, D2_gz
 *
 * Output: float[276] in this exact order (matches np.hstack in notebook):
 *   [0..107]   time_domain_features    (12 axes × 9 = 108)
 *   [108..167] frequency_domain_features (12 axes × 5 = 60)
 *   [168..177] magnitude_features      (2 groups × 5 = 10)
 *   [178..201] jerk_features           (6 acc axes × 4 = 24)
 *   [202..267] correlation_features    (C(12,2) = 66)
 *   [268..269] vertical_acc_energy     (2)
 *   [270..275] pitch_features          (2 groups × 3 = 6)
 */
public class FeatureExtractor {

    private static final int AXES    = 12;
    private static final int WINDOW  = 60;

    // Axis index groups — must match notebook exactly
    private static final int[] ACC_AXES  = {0, 1, 2, 6, 7, 8};   // D1 acc + D2 acc
    private static final int[] GYRO_AXES = {3, 4, 5, 9, 10, 11}; // D1 gyro + D2 gyro
    private static final int[] ALL_AXES  = {0,1,2,3,4,5,6,7,8,9,10,11};
    private static final int[] VERT_AXES = {2, 8};                // D1_az, D2_az
    // Accelerometer groups for pitch: [ax_col, ay_col, az_col]
    private static final int[][] PITCH_GROUPS = {{0,1,2},{6,7,8}};
    // Accelerometer axis groups for magnitude
    private static final int[][] MAG_GROUPS   = {ACC_AXES, GYRO_AXES};

    /**
     * Main entry point. Takes a (60 × 12) window and returns float[276].
     */
    public static float[] extract(float[][] segment) {
        float[] out = new float[276];
        int pos = 0;

        // 1. Time domain (108)
        for (int ax = 0; ax < AXES; ax++) {
            float[] col = getCol(segment, ax);
            float[] feats = timeDomain(col);
            for (float f : feats) out[pos++] = f;
        }

        // 2. Frequency domain (60)
        for (int ax = 0; ax < AXES; ax++) {
            float[] col = getCol(segment, ax);
            float[] feats = frequencyDomain(col);
            for (float f : feats) out[pos++] = f;
        }

        // 3. Magnitude (10)
        for (int[] group : MAG_GROUPS) {
            float[] feats = magnitudeGroup(segment, group);
            for (float f : feats) out[pos++] = f;
        }

        // 4. Jerk (24) — diff of acc axes only
        float[][] jerk = jerkDiff(segment, ACC_AXES);  // (59 × 6)
        for (int ax = 0; ax < jerk[0].length; ax++) {
            float[] col = getColFromJerk(jerk, ax);
            float[] feats = jerkFeats(col);
            for (float f : feats) out[pos++] = f;
        }

        // 5. Correlation upper-triangle (66)
        float[] corrFeats = correlationFeats(segment, ALL_AXES);
        for (float f : corrFeats) out[pos++] = f;

        // 6. Vertical acc energy (2)
        for (int ax : VERT_AXES) {
            float col[] = getCol(segment, ax);
            float energy = 0;
            for (float v : col) energy += v * v;
            out[pos++] = energy;
        }

        // 7. Pitch features (6)
        for (int[] group : PITCH_GROUPS) {
            float[] feats = pitchFeats(segment, group);
            for (float f : feats) out[pos++] = f;
        }

        return out;
    }

    // ─── Time domain: 9 features per axis ────────────────────────────────────
    // mean, std, min, max, ptp, median, iqr, rms, zcr
    private static float[] timeDomain(float[] data) {
        int n = data.length;
        float sum = 0, sum2 = 0, min = data[0], max = data[0];
        for (float v : data) {
            sum  += v;
            sum2 += v * v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float mean = sum / n;
        float std  = (float) Math.sqrt(sum2 / n - mean * mean);
        float rms  = (float) Math.sqrt(sum2 / n);
        float ptp  = max - min;

        float[] sorted = data.clone();
        java.util.Arrays.sort(sorted);
        float median = percentile(sorted, 50);
        float q75    = percentile(sorted, 75);
        float q25    = percentile(sorted, 25);
        float iqr    = q75 - q25;

        // zero crossing rate
        int zc = 0;
        for (int i = 1; i < n; i++) {
            if (Math.signum(data[i]) != Math.signum(data[i-1]) &&
                    (Math.signum(data[i]) != 0 || Math.signum(data[i-1]) != 0)) {
                zc++;
            }
        }
        float zcr = (float) zc / n;

        return new float[]{mean, std, min, max, ptp, median, iqr, rms, zcr};
    }

    // ─── Frequency domain: 5 features per axis ───────────────────────────────
    // mean_fft, std_fft, max_fft, dom_freq, energy  (DC removed)
    private static float[] frequencyDomain(float[] data) {
        int n = data.length;
        // Real FFT — only positive frequencies, length = n/2 + 1
        float[] fft = rfftMagnitude(data);
        // Remove DC (index 0) — matches fft_vals[1:] in notebook
        int fLen = fft.length - 1;
        float[] vals = new float[fLen];
        for (int i = 0; i < fLen; i++) vals[i] = fft[i + 1];

        float sum = 0, sum2 = 0, maxF = vals[0];
        int   domIdx = 0;
        for (int i = 0; i < fLen; i++) {
            sum  += vals[i];
            sum2 += vals[i] * vals[i];
            if (vals[i] > maxF) { maxF = vals[i]; domIdx = i; }
        }
        float mean   = sum  / fLen;
        float std    = (float) Math.sqrt(sum2 / fLen - mean * mean);
        float energy = sum2;

        return new float[]{mean, std, maxF, domIdx, energy};
    }

    // ─── Magnitude: 5 features per axis group ────────────────────────────────
    // mean, std, max, min, energy
    private static float[] magnitudeGroup(float[][] seg, int[] axes) {
        int n = seg.length;
        float[] mag = new float[n];
        for (int r = 0; r < n; r++) {
            float s = 0;
            for (int ax : axes) s += seg[r][ax] * seg[r][ax];
            mag[r] = (float) Math.sqrt(s);
        }
        float sum = 0, sum2 = 0, mn = mag[0], mx = mag[0];
        for (float v : mag) {
            sum  += v; sum2 += v * v;
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        float mean   = sum / n;
        float std    = (float) Math.sqrt(sum2 / n - mean * mean);
        float energy = sum2;
        return new float[]{mean, std, mx, mn, energy};
    }

    // ─── Jerk: diff along acc axes, then 4 features per axis ─────────────────
    // mean, std, max, energy
    private static float[][] jerkDiff(float[][] seg, int[] axes) {
        int n = seg.length - 1;
        float[][] jerk = new float[n][axes.length];
        for (int r = 0; r < n; r++) {
            for (int a = 0; a < axes.length; a++) {
                jerk[r][a] = seg[r+1][axes[a]] - seg[r][axes[a]];
            }
        }
        return jerk;
    }

    private static float[] getColFromJerk(float[][] jerk, int col) {
        float[] out = new float[jerk.length];
        for (int r = 0; r < jerk.length; r++) out[r] = jerk[r][col];
        return out;
    }

    private static float[] jerkFeats(float[] data) {
        int n = data.length;
        float sum = 0, sum2 = 0, mx = data[0];
        for (float v : data) {
            sum  += v; sum2 += v * v;
            if (v > mx) mx = v;
        }
        float mean   = sum / n;
        float std    = (float) Math.sqrt(sum2 / n - mean * mean);
        float energy = sum2;
        return new float[]{mean, std, mx, energy};
    }

    // ─── Correlation upper triangle: C(12,2)=66 features ─────────────────────
    private static float[] correlationFeats(float[][] seg, int[] axes) {
        int k    = axes.length;
        int n    = seg.length;

        // Compute mean per axis
        float[] means = new float[k];
        for (int a = 0; a < k; a++) {
            float s = 0;
            for (int r = 0; r < n; r++) s += seg[r][axes[a]];
            means[a] = s / n;
        }

        // Correlation matrix — only upper triangle needed
        int pairs = k * (k - 1) / 2;
        float[] corrs = new float[pairs];
        int idx = 0;
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                float cov = 0, si = 0, sj = 0;
                for (int r = 0; r < n; r++) {
                    float di = seg[r][axes[i]] - means[i];
                    float dj = seg[r][axes[j]] - means[j];
                    cov += di * dj;
                    si  += di * di;
                    sj  += dj * dj;
                }
                float denom = (float)(Math.sqrt(si) * Math.sqrt(sj));
                corrs[idx++] = denom > 0 ? cov / denom : 0f;
            }
        }
        return corrs;
    }

    // ─── Pitch: range, std, max ───────────────────────────────────────────────
    private static float[] pitchFeats(float[][] seg, int[] group) {
        int n = seg.length;
        float[] pitch = new float[n];
        for (int r = 0; r < n; r++) {
            float ax = seg[r][group[0]];
            float ay = seg[r][group[1]];
            float az = seg[r][group[2]];
            // pitch = arctan2(-ax, sqrt(ay^2 + az^2))
            pitch[r] = (float) Math.atan2(-ax, Math.sqrt(ay*ay + az*az));
        }
        float mn = pitch[0], mx = pitch[0], sum = 0, sum2 = 0;
        for (float p : pitch) {
            sum += p; sum2 += p*p;
            if (p < mn) mn = p;
            if (p > mx) mx = p;
        }
        float mean = sum / n;
        float std  = (float) Math.sqrt(sum2 / n - mean * mean);
        return new float[]{mx - mn, std, mx};
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private static float[] getCol(float[][] seg, int col) {
        float[] out = new float[seg.length];
        for (int r = 0; r < seg.length; r++) out[r] = seg[r][col];
        return out;
    }

    /**
     * Percentile on a pre-sorted array. Matches numpy's linear interpolation.
     */
    private static float percentile(float[] sorted, double pct) {
        int n = sorted.length;
        double idx = (pct / 100.0) * (n - 1);
        int lo = (int) idx;
        int hi = Math.min(lo + 1, n - 1);
        double frac = idx - lo;
        return (float)(sorted[lo] * (1 - frac) + sorted[hi] * frac);
    }

    /**
     * Real FFT magnitude — Cooley-Tukey for power-of-2 via zero-padding,
     * then fall back to DFT for arbitrary length.
     * Returns float[n/2 + 1] of magnitudes.
     */
    private static float[] rfftMagnitude(float[] data) {
        int n    = data.length;
        int outN = n / 2 + 1;
        float[] mag = new float[outN];

        // DFT (O(n²) — fine for n=60)
        for (int k = 0; k < outN; k++) {
            double re = 0, im = 0;
            for (int t = 0; t < n; t++) {
                double angle = 2 * Math.PI * k * t / n;
                re +=  data[t] * Math.cos(angle);
                im -= data[t] * Math.sin(angle);
            }
            mag[k] = (float) Math.sqrt(re * re + im * im);
        }
        return mag;
    }
}