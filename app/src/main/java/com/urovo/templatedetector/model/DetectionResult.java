package com.urovo.templatedetector.model;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

/**
 * 标签检测结果
 */
public class DetectionResult {

    private final boolean detected;
    private final PointF[] cornerPoints;        // 扩展后的角点（用于显示）
    private final PointF[] originalCornerPoints; // 原始角点（用于裁剪）
    private final RectF boundingBox;
    private final Bitmap segmentationMask;
    private final float confidence;
    private final float rotationAngle;

    private DetectionResult(Builder builder) {
        this.detected = builder.detected;
        this.cornerPoints = builder.cornerPoints;
        this.originalCornerPoints = builder.originalCornerPoints;
        this.boundingBox = builder.boundingBox;
        this.segmentationMask = builder.segmentationMask;
        this.confidence = builder.confidence;
        this.rotationAngle = builder.rotationAngle;
    }

    /**
     * 创建未检测到的结果
     */
    public static DetectionResult notDetected() {
        return new Builder().setDetected(false).build();
    }

    /**
     * 是否检测到标签
     */
    public boolean isDetected() {
        return detected;
    }

    /**
     * 获取四角点坐标（扩展后，用于显示检测框）
     * 顺序: 左上、右上、右下、左下
     */
    public PointF[] getCornerPoints() {
        return cornerPoints;
    }

    /**
     * 获取原始四角点坐标（未扩展，用于透视校正裁剪）
     * 顺序: 左上、右上、右下、左下
     */
    public PointF[] getOriginalCornerPoints() {
        return originalCornerPoints != null ? originalCornerPoints : cornerPoints;
    }

    /**
     * 获取边界框
     */
    public RectF getBoundingBox() {
        return boundingBox;
    }

    /**
     * 获取分割掩码
     */
    public Bitmap getSegmentationMask() {
        return segmentationMask;
    }

    /**
     * 获取置信度
     */
    public float getConfidence() {
        return confidence;
    }

    /**
     * 获取旋转角度
     */
    public float getRotationAngle() {
        return rotationAngle;
    }

    /**
     * 检查角点是否有效
     */
    public boolean hasValidCorners() {
        return cornerPoints != null && cornerPoints.length == 4;
    }

    /**
     * Builder模式
     */
    public static class Builder {
        private boolean detected = false;
        private PointF[] cornerPoints;
        private PointF[] originalCornerPoints;
        private RectF boundingBox;
        private Bitmap segmentationMask;
        private float confidence = 0f;
        private float rotationAngle = 0f;

        public Builder setDetected(boolean detected) {
            this.detected = detected;
            return this;
        }

        public Builder setCornerPoints(PointF[] cornerPoints) {
            this.cornerPoints = cornerPoints;
            return this;
        }

        public Builder setOriginalCornerPoints(PointF[] originalCornerPoints) {
            this.originalCornerPoints = originalCornerPoints;
            return this;
        }

        public Builder setBoundingBox(RectF boundingBox) {
            this.boundingBox = boundingBox;
            return this;
        }

        public Builder setSegmentationMask(Bitmap segmentationMask) {
            this.segmentationMask = segmentationMask;
            return this;
        }

        public Builder setConfidence(float confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder setRotationAngle(float rotationAngle) {
            this.rotationAngle = rotationAngle;
            return this;
        }

        public DetectionResult build() {
            return new DetectionResult(this);
        }
    }
}
