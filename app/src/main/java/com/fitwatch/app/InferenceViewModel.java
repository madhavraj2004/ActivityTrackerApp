package com.fitwatch.app;

import android.net.Uri;
import androidx.lifecycle.ViewModel;
import java.util.ArrayList;
import java.util.List;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class InferenceViewModel extends ViewModel {
    public Uri inputCsvUri = null;
    public List<String> outputRows = new ArrayList<>();
    public boolean modelLoaded = false;
    public String summaryText = "";
    
    public OrtEnvironment ortEnv;
    public OrtSession ortSession;

    @Override
    protected void onCleared() {
        super.onCleared();
        try {
            if (ortSession != null) ortSession.close();
            if (ortEnv != null) ortEnv.close();
        } catch (Exception ignored) {}
    }
}
