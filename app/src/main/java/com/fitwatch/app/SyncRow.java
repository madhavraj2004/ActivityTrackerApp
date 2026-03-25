package com.fitwatch.app;

public class SyncRow {

    public long timestamp;

    public SensorPacket d1;
    public SensorPacket d2;

    public SyncRow(long ts, SensorPacket d1, SensorPacket d2) {
        this.timestamp = ts;
        this.d1 = d1;
        this.d2 = d2;
    }
}