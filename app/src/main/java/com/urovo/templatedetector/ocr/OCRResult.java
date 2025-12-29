package com.urovo.templatedetector.ocr;

import android.graphics.PointF;
import android.graphics.RectF;

/**
 * OCR识别结果
 */
public class OCRResult {

    private final String text;
    private final RectF boundingBox;
    private final PointF[] cornerPoints;
    private final float confidence;

    public OCRResult(String text, RectF boundingBox, PointF[] cornerPoints, float confidence) {
        this.text = text;
        this.boundingBox = boundingBox;
        this.cornerPoints = cornerPoints;
        this.confidence = confidence;
    }

    /**
     * 获取识别文字
     */
    public String getText() {
        return text;
    }

    /**
     * 获取边界框
     */
    public RectF getBoundingBox() {
        return boundingBox;
    }

    /**
     * 获取四角点
     */
    public PointF[] getCornerPoints() {
        return cornerPoints;
    }

    /**
     * 获取置信度 (0.0 - 1.0)
     */
    public float getConfidence() {
        return confidence;
    }

    /**
     * 检查结果是否有效
     */
    public boolean isValid() {
        return text != null && !text.isEmpty() && boundingBox != null;
    }

    @Override
    public String toString() {
        return "OCRResult{" +
                "text='" + text + '\'' +
                ", confidence=" + confidence +
                '}';
    }
}
