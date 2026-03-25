package com.fitwatch.app;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class ActivityAdapter extends RecyclerView.Adapter<ActivityAdapter.VH> {

    public interface Listener {
        void onSelected(String activity);
        void onDelete(String activity); // 🔥 NEW
    }

    private final List<String> items;
    private final Listener listener;
    private String selected;

    private final int[] palette = new int[] {
            Color.parseColor("#18A999"),
            Color.parseColor("#6F42C1"),
            Color.parseColor("#3F51B5"),
            Color.parseColor("#13C2D9"),
            Color.parseColor("#E91E63"),
            Color.parseColor("#8D6E63"),
            Color.parseColor("#607D8B"),
            Color.parseColor("#C0CA33"),
            Color.parseColor("#9E9E9E")
    };

    public ActivityAdapter(List<String> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setSelected(String activity) {
        this.selected = activity;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_activity, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {

        String item = items.get(position);
        h.txt.setText(item);

        int color = palette[position % palette.length];
        h.card.setStrokeColor(color);
        h.card.setStrokeWidth(8);

        // ✅ Selection highlight
        if (item.equals(selected)) {
            h.card.setCardElevation(12f);
            h.card.setAlpha(1f);
        } else {
            h.card.setCardElevation(4f);
            h.card.setAlpha(0.95f);
        }

        // ✅ Select click
        h.itemView.setOnClickListener(v -> {
            selected = item;
            notifyDataSetChanged();

            if (listener != null) {
                listener.onSelected(item);
            }
        });

        // 🔥 DELETE BUTTON CLICK
        h.btnDelete.setOnClickListener(v -> {

            android.app.AlertDialog.Builder builder =
                    new android.app.AlertDialog.Builder(v.getContext());

            builder.setTitle("Delete Activity?");
            builder.setMessage(item);

            builder.setPositiveButton("Yes", (dialog, which) -> {
                if (listener != null) {
                    listener.onDelete(item);
                }
            });

            builder.setNegativeButton("No", null);

            builder.show();
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {

        MaterialCardView card;
        TextView txt;
        ImageView btnDelete; // 🔥 NEW

        VH(@NonNull View itemView) {
            super(itemView);

            card = (MaterialCardView) itemView;
            txt = itemView.findViewById(R.id.txtActivity);

            // 🔥 INIT DELETE BUTTON
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}