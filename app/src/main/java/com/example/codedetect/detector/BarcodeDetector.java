package com.example.codedetect.detector;

import android.media.Image;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.example.codedetect.model.BarcodeInfo;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import java.util.List;

/**
 * 条码检测器
 * 使用ML Kit进行条码识别
 */
public class BarcodeDetector {
    
    private final BarcodeScanner scanner;
    private DetectionCallback callback;
    
    public interface DetectionCallback {
        void onBarcodesDetected(@NonNull List<BarcodeInfo> barcodes);
        void onDetectionFailed(@NonNull Exception e);
    }
    
    public BarcodeDetector() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_QR_CODE,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_CODE_93,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_DATA_MATRIX
                )
                .build();
        
        scanner = BarcodeScanning.getClient(options);
    }
    
    public void setCallback(DetectionCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 处理相机帧，检测条码
     */
    public void processImage(@NonNull ImageProxy imageProxy) {
        @androidx.camera.core.ExperimentalGetImage
        Image mediaImage = imageProxy.getImage();
        
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        
        InputImage image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.getImageInfo().getRotationDegrees()
        );
        
        long timestamp = System.currentTimeMillis();
        
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    List<BarcodeInfo> barcodeInfoList = convertToBarcodeInfo(barcodes, timestamp);
                    if (callback != null) {
                        callback.onBarcodesDetected(barcodeInfoList);
                    }
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) {
                        callback.onDetectionFailed(e);
                    }
                    imageProxy.close();
                });
    }
    
    /**
     * 转换ML Kit的Barcode对象为BarcodeInfo
     */
    private List<BarcodeInfo> convertToBarcodeInfo(List<Barcode> barcodes, long timestamp) {
        List<BarcodeInfo> result = new ArrayList<>();
        
        for (Barcode barcode : barcodes) {
            String rawValue = barcode.getRawValue();
            if (rawValue == null || rawValue.isEmpty()) {
                continue;
            }
            
            BarcodeInfo info = new BarcodeInfo(
                    rawValue,
                    barcode.getFormat(),
                    barcode.getCornerPoints(),
                    timestamp
            );
            
            result.add(info);
        }
        
        return result;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        scanner.close();
    }
}
