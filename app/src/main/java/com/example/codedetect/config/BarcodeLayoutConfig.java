package com.example.codedetect.config;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 条码版面配置
 * 定义条码在版面上的位置映射
 */
public class BarcodeLayoutConfig {
    
    /**
     * 创建示例配置（3x3网格布局）
     * 假设A4纸尺寸：210mm x 297mm
     */
    @NonNull
    public static Map<String, float[]> createSampleLayout() {
        Map<String, float[]> layout = new HashMap<>();
        
        // 条码尺寸：30mm x 30mm
        float barcodeSize = 0.03f; // 米
        float spacing = 0.02f;     // 间距
        
        // 起始位置（左上角留边距）
        float startX = 0.02f;
        float startY = 0.02f;
        
        // 3x3网格
        int index = 1;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float x = startX + col * (barcodeSize + spacing);
                float y = startY + row * (barcodeSize + spacing);
                
                String barcodeValue = String.format("BARCODE%03d", index);
                layout.put(barcodeValue, new float[]{x, y});
                
                index++;
            }
        }
        
        return layout;
    }
    
    /**
     * 从JSON配置文件加载布局
     * TODO: 实现JSON解析
     */
    @NonNull
    public static Map<String, float[]> loadFromJson(@NonNull String jsonConfig) {
        // 实现JSON解析逻辑
        return new HashMap<>();
    }
    
    /**
     * 创建自定义布局
     */
    @NonNull
    public static Map<String, float[]> createCustomLayout(
            @NonNull String[] barcodeValues,
            @NonNull float[][] positions) {
        
        if (barcodeValues.length != positions.length) {
            throw new IllegalArgumentException(
                    "Barcode values and positions must have the same length");
        }
        
        Map<String, float[]> layout = new HashMap<>();
        for (int i = 0; i < barcodeValues.length; i++) {
            layout.put(barcodeValues[i], positions[i]);
        }
        
        return layout;
    }
}
