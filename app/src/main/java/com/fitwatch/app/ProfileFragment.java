package com.fitwatch.app;

import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.fitwatch.app.R;

import java.io.File;

public class ProfileFragment extends Fragment {

    Button exportBtn;

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_profile, container, false);



        return view;
    }

    private void exportCsv() {

        File file = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                "activity_dataset.csv"
        );

        if (file.exists()) {
            Toast.makeText(getContext(),
                    "CSV saved at: " + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(),
                    "No CSV file found!",
                    Toast.LENGTH_SHORT).show();
        }
    }
}