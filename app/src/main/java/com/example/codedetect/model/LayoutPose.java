package com.example.codedetect.model;

import androidx.annotation.NonNull;

/**
 * 版面姿态模型
 * 表示条码版面在3D空间中的位置和旋转
 */
public class LayoutPose {
    
    // 位置向量 (x, y, z)
    private final float[] translation;
    
    // 旋转向量 (rx, ry, rz) - Rodrigues表示
    private final float[] rotation;
    
    // 4x4变换矩阵
    private float[] transformMatrix;
    
    // 置信度 (0.0 - 1.0)
    private float confidence;
    
    // 时间戳
    private final long timestamp;
    
    public LayoutPose(@NonNull float[] translation, @NonNull float[] rotation, long timestamp) {
        if (translation.length != 3 || rotation.length != 3) {
            throw new IllegalArgumentException("Translation and rotation must be 3D vectors");
        }
        this.translation = translation.clone();
        this.rotation = rotation.clone();
        this.timestamp = timestamp;
        this.confidence = 1.0f;
    }
    
    @NonNull
    public float[] getTranslation() {
        return translation.clone();
    }
    
    @NonNull
    public float[] getRotation() {
        return rotation.clone();
    }
    
    public void setTransformMatrix(@NonNull float[] matrix) {
        if (matrix.length != 16) {
            throw new IllegalArgumentException("Transform matrix must be 4x4 (16 elements)");
        }
        this.transformMatrix = matrix.clone();
    }
    
    @NonNull
    public float[] getTransformMatrix() {
        if (transformMatrix == null) {
            computeTransformMatrix();
        }
        return transformMatrix.clone();
    }
    
    public void setConfidence(float confidence) {
        this.confidence = Math.max(0.0f, Math.min(1.0f, confidence));
    }
    
    public float getConfidence() {
        return confidence;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 从旋转向量和平移向量计算4x4变换矩阵
     */
    private void computeTransformMatrix() {
        transformMatrix = new float[16];
        
        // 简化实现：假设小角度旋转
        // 实际应使用Rodrigues公式或四元数
        float rx = rotation[0];
        float ry = rotation[1];
        float rz = rotation[2];
        
        // 构建旋转矩阵（简化版）
        transformMatrix[0] = 1.0f;
        transformMatrix[1] = -rz;
        transformMatrix[2] = ry;
        transformMatrix[3] = 0.0f;
        
        transformMatrix[4] = rz;
        transformMatrix[5] = 1.0f;
        transformMatrix[6] = -rx;
        transformMatrix[7] = 0.0f;
        
        transformMatrix[8] = -ry;
        transformMatrix[9] = rx;
        transformMatrix[10] = 1.0f;
        transformMatrix[11] = 0.0f;
        
        // 平移部分
        transformMatrix[12] = translation[0];
        transformMatrix[13] = translation[1];
        transformMatrix[14] = translation[2];
        transformMatrix[15] = 1.0f;
    }
}
