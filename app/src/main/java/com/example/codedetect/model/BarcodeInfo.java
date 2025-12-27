package com.example.codedetect.model;

import android.graphics.Point;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 条码信息模型
 * 封装单个条码的检测结果和3D位置信息
 */
public class BarcodeInfo {
    
    private final String rawValue;
    private final int format;
    private final Point[] cornerPoints;
    private final long timestamp;
    
    // 3D空间位置（相对于版面坐标系）
    private float[] position3D;
    
    // 条码在版面上的已知位置（如果有）
    private Float knownX;
    private Float knownY;
    
    public BarcodeInfo(@NonNull String rawValue, int format, 
                       @Nullable Point[] cornerPoints, long timestamp) {
        this.rawValue = rawValue;
        this.format = format;
        this.cornerPoints = cornerPoints;
        this.timestamp = timestamp;
    }
    
    @NonNull
    public String getRawValue() {
        return rawValue;
    }
    
    public int getFormat() {
        return format;
    }
    
    @Nullable
    public Point[] getCornerPoints() {
        return cornerPoints;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setPosition3D(float x, float y, float z) {
        this.position3D = new float[]{x, y, z};
    }
    
    @Nullable
    public float[] getPosition3D() {
        return position3D;
    }
    
    public void setKnownPosition(float x, float y) {
        this.knownX = x;
        this.knownY = y;
    }
    
    @Nullable
    public Float getKnownX() {
        return knownX;
    }
    
    @Nullable
    public Float getKnownY() {
        return knownY;
    }
    
    public boolean hasKnownPosition() {
        return knownX != null && knownY != null;
    }
    
    public boolean hasCornerPoints() {
        return cornerPoints != null && cornerPoints.length == 4;
    }
}
