package com.fitwatch.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BufferAdapter extends RecyclerView.Adapter<BufferAdapter.VH> {

    private final List<SyncRow> rows = new ArrayList<>();
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // =========================
    // 🔥 ADD ROW (OPTIMIZED)
    // =========================
    public void addRow(SyncRow row) {

        if (row == null || row.d1 == null || row.d2 == null) return;

        rows.add(row);

        // ✅ CHANGE 40 → 10
        if (rows.size() > 10) {
            rows.remove(0);
            notifyItemRemoved(0);
        }

        notifyItemInserted(rows.size() - 1);
    }
    public void clear() {
        rows.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_buffer, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {

        SyncRow row = rows.get(position);

        // 🔥 Safe binding
        if (row == null || row.d1 == null || row.d2 == null) return;

        h.time.setText(fmt.format(new Date(row.timestamp)));

        h.row1.setText(
                "RIGHT → " + row.d1.shortAxes() +
                        " | P=" + row.d1.predictedLabel
        );

        h.row2.setText(
                "LEFT  → " + row.d2.shortAxes() +
                        " | P=" + row.d2.predictedLabel
        );
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView time, row1, row2;

        VH(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.txtTime);
            row1 = itemView.findViewById(R.id.txtRow1);
            row2 = itemView.findViewById(R.id.txtRow2);
        }
    }
}