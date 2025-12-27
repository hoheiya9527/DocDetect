package com.example.codedetect.tracker;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.codedetect.ar.ARCoreManager;
import com.example.codedetect.detector.BarcodeDetector;
import com.example.codedetect.model.BarcodeInfo;
import com.example.codedetect.model.LayoutPose;
import com.example.codedetect.pose.PoseEstimator;
import com.google.ar.core.Anchor;
import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 条码AR追踪器
 * 整合条码检测、姿态估算和ARCore追踪
 */
public class BarcodeARTracker {
    
    private static final String TAG = "BarcodeARTracker";
    
    private final BarcodeDetector barcodeDetector;
    private final PoseEstimator poseEstimator;
    private final ARCoreManager arCoreManager;
    
    // 条码位置映射（条码内容 -> 版面上的位置）
    private final Map<String, float[]> barcodePositionMap;
    
    // 追踪状态
    private boolean isInitialized = false;
    private long lastPoseUpdateTime = 0;
    private static final long POSE_UPDATE_INTERVAL = 100; // 100ms
    
    // 回调接口
    private TrackingCallback trackingCallback;
    
    public interface TrackingCallback {
        void onTrackingStarted();
        void onTrackingUpdated(@NonNull Anchor anchor, @NonNull LayoutPose pose);
        void onTrackingLost();
        void onError(@NonNull String message);
    }
    
    public BarcodeARTracker(@NonNull ARCoreManager arCoreManager) {
        this.arCoreManager = arCoreManager;
        this.barcodeDetector = new BarcodeDetector();
        this.poseEstimator = new PoseEstimator();
        this.barcodePositionMap = new HashMap<>();
        
        setupCallbacks();
    }
    
    /**
     * 设置回调
     */
    private void setupCallbacks() {
        // 条码检测回调
        barcodeDetector.setCallback(new BarcodeDetector.DetectionCallback() {
            @Override
            public void onBarcodesDetected(@NonNull List<BarcodeInfo> barcodes) {
                handleBarcodesDetected(barcodes);
            }
            
            @Override
            public void onDetectionFailed(@NonNull Exception e) {
                Log.e(TAG, "Barcode detection failed", e);
                if (trackingCallback != null) {
                    trackingCallback.onError("条码检测失败: " + e.getMessage());
                }
            }
        });
        
        // ARCore追踪状态回调
        arCoreManager.setTrackingStateCallback(state -> {
            if (state == TrackingState.TRACKING) {
                if (!isInitialized && trackingCallback != null) {
                    trackingCallback.onTrackingStarted();
                    isInitialized = true;
                }
            } else if (state == TrackingState.PAUSED) {
                if (trackingCallback != null) {
                    trackingCallback.onTrackingLost();
                }
            }
        });
    }
    
    /**
     * 设置条码在版面上的已知位置
     * 
     * @param barcodeValue 条码内容
     * @param x X坐标（米）
     * @param y Y坐标（米）
     */
    public void setBarcodePosition(@NonNull String barcodeValue, float x, float y) {
        barcodePositionMap.put(barcodeValue, new float[]{x, y});
    }
    
    /**
     * 批量设置条码位置
     */
    public void setBarcodePositions(@NonNull Map<String, float[]> positions) {
        barcodePositionMap.clear();
        barcodePositionMap.putAll(positions);
    }
    
    /**
     * 设置版面尺寸
     */
    public void setLayoutSize(float width, float height) {
        poseEstimator.setLayoutSize(width, height);
    }
    
    /**
     * 设置相机内参
     */
    public void setCameraIntrinsics(double fx, double fy, double cx, double cy) {
        poseEstimator.setCameraIntrinsics(fx, fy, cx, cy);
    }
    
    /**
     * 设置追踪回调
     */
    public void setTrackingCallback(TrackingCallback callback) {
        this.trackingCallback = callback;
    }
    
    /**
     * 处理检测到的条码
     */
    private void handleBarcodesDetected(@NonNull List<BarcodeInfo> barcodes) {
        if (barcodes.isEmpty()) {
            return;
        }
        
        // 为条码添加已知位置信息
        int validBarcodes = 0;
        for (BarcodeInfo barcode : barcodes) {
            float[] position = barcodePositionMap.get(barcode.getRawValue());
            if (position != null) {
                barcode.setKnownPosition(position[0], position[1]);
                validBarcodes++;
            }
        }
        
        if (validBarcodes == 0) {
            Log.w(TAG, "No barcodes with known positions detected");
            return;
        }
        
        // 限制姿态更新频率
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPoseUpdateTime < POSE_UPDATE_INTERVAL) {
            return;
        }
        
        // 估算版面姿态
        LayoutPose pose = poseEstimator.estimatePose(barcodes);
        if (pose == null) {
            Log.w(TAG, "Failed to estimate pose");
            return;
        }
        
        // 检查置信度
        if (pose.getConfidence() < 0.3f) {
            Log.w(TAG, "Low confidence pose: " + pose.getConfidence());
            return;
        }
        
        // 创建或更新ARCore Anchor
        arCoreManager.createOrUpdateAnchor(pose);
        
        // 通知回调
        Anchor anchor = arCoreManager.getLayoutAnchor();
        if (anchor != null && trackingCallback != null) {
            trackingCallback.onTrackingUpdated(anchor, pose);
        }
        
        lastPoseUpdateTime = currentTime;
        
        Log.d(TAG, "Pose updated - Confidence: " + pose.getConfidence() + 
                ", Valid barcodes: " + validBarcodes);
    }
    
    /**
     * 更新追踪（每帧调用）
     */
    public void update(@NonNull androidx.camera.core.ImageProxy imageProxy) {
        // 更新ARCore（如果可用）
        if (arCoreManager.getSession() != null) {
            Frame arFrame = arCoreManager.update();
        }
        
        // 处理条码检测
        barcodeDetector.processImage(imageProxy);
    }
    
    /**
     * 获取当前的Anchor
     */
    @Nullable
    public Anchor getCurrentAnchor() {
        return arCoreManager.getLayoutAnchor();
    }
    
    /**
     * 重置追踪
     */
    public void reset() {
        isInitialized = false;
        lastPoseUpdateTime = 0;
        Log.i(TAG, "Tracker reset");
    }
    
    /**
     * 释放资源
     */
    public void release() {
        barcodeDetector.release();
        poseEstimator.release();
        Log.i(TAG, "Tracker released");
    }
}
