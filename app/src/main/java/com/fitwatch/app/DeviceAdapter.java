package com.fitwatch.app;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.*;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

    public interface Listener {
        void onClick(BluetoothDevice device);
    }

    private List<BluetoothDevice> list;
    private Listener listener;

    public DeviceAdapter(List<BluetoothDevice> list, Listener l) {
        this.list = list;
        this.listener = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int v) {
        return new VH(LayoutInflater.from(p.getContext())
                .inflate(R.layout.item_device, p, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int i) {
        BluetoothDevice d = list.get(i);
        Context context = h.itemView.getContext();

        String deviceName = "Unknown Device";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED) {

                String name = d.getName();
                if (name != null && !name.isEmpty()) {
                    deviceName = name;
                }
            }
        } else {
            String name = d.getName();
            if (name != null && !name.isEmpty()) {
                deviceName = name;
            }
        }

        // 🔥 Improve visibility
        h.name.setTextColor(0xFF000000);   // black
        h.mac.setTextColor(0xFF555555);

        h.name.setText(deviceName);
        h.mac.setText(d.getAddress());

        h.itemView.setOnClickListener(v -> listener.onClick(d));
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, mac;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.txtName);
            mac = v.findViewById(R.id.txtMac);
        }
    }
}