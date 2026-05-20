package com.fitwatch.app;

import android.net.Uri;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * SharedRecordingViewModel
 *
 * Scoped to the HOST ACTIVITY (not a fragment), so both ActivityFragment
 * and InferenceFragment see the same instance.
 *
 * Flow:
 *   ActivityFragment.stopRecording()
 *       └─► posts the saved-CSV Uri here
 *               └─► InferenceFragment observes it
 *                       └─► enables "Run Inference" automatically
 *                               └─► after inference completes, enables "Export CSV"
 */
public class SharedRecordingViewModel extends ViewModel {

    /**
     * Emits the Uri of the most-recently exported recording CSV.
     * Null  = nothing recorded yet / cleared.
     * Non-null = a CSV is ready to infer.
     */
    public final MutableLiveData<Uri> recordedCsvUri = new MutableLiveData<>(null);
}