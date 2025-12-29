package com.urovo.templatedetector.decoder;

import android.graphics.PointF;
import android.graphics.RectF;

/**
 * ROI检测结果
 * 用于存储detectBarcodes检测到的ROI区域信息
 */
public class ROIDetectionResult {
    
    private final RectF boundingBox;
    private final PointF[] cornerPoints;
    private final PointF centerPoint;
    private final float confidence;
    
    public ROIDetectionResult(RectF boundingBox, PointF[] cornerPoints, float confidence) {
        this.boundingBox = boundingBox;
        this.cornerPoints = cornerPoints;
        this.confidence = confidence;
        
        // 计算中心点
        if (cornerPoints != null && cornerPoints.length >= 4) {
            float sumX = 0, sumY = 0;
            for (PointF point : cornerPoints) {
                if (point != null) {
                    sumX += point.x;
                    sumY += point.y;
                }
            }
            this.centerPoint = new PointF(sumX / cornerPoints.length, sumY / cornerPoints.length);
        } else if (boundingBox != null) {
            this.centerPoint = new PointF(boundingBox.centerX(), boundingBox.centerY());
        } else {
            this.centerPoint = null;
        }
    }
    
    /**
     * ROI边界框（图像坐标系）
     */
    public RectF getBoundingBox() {
        return boundingBox;
    }
    
    /**
     * ROI角点（图像坐标系）
     */
    public PointF[] getCornerPoints() {
        return cornerPoints;
    }
    
    /**
     * ROI中心点（图像坐标系）
     */
    public PointF getCenterPoint() {
        return centerPoint;
    }
    
    /**
     * 检测置信度
     */
    public float getConfidence() {
        return confidence;
    }
    
    @Override
    public String toString() {
        return "ROIDetectionResult{" +
                "boundingBox=" + boundingBox +
                ", confidence=" + confidence +
                ", centerPoint=" + centerPoint +
                '}';
    }
}