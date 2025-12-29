package com.urovo.templatedetector.decoder;

import android.graphics.PointF;
import android.graphics.RectF;

/**
 * 条码解码结果
 */
public class BarcodeResult {
    
    private final String content;
    private final String format;
    private final PointF centerPoint;
    private final RectF boundingBox;
    private final PointF[] cornerPoints;
    
    // ROI检测框信息（红框）
    private final RectF roiBox;
    private final PointF[] roiCornerPoints;
    
    // 解码成功标记
    private final boolean decodeSuccess;
    
    public BarcodeResult(String content, String format, PointF centerPoint, 
                         RectF boundingBox, PointF[] cornerPoints) {
        this.content = content;
        this.format = format;
        this.centerPoint = centerPoint;
        this.boundingBox = boundingBox;
        this.cornerPoints = cornerPoints;
        this.roiBox = null;
        this.roiCornerPoints = null;
        this.decodeSuccess = true;
    }
    
    public BarcodeResult(String content, String format, PointF centerPoint, 
                         RectF boundingBox, PointF[] cornerPoints,
                         RectF roiBox, PointF[] roiCornerPoints, boolean decodeSuccess) {
        this.content = content;
        this.format = format;
        this.centerPoint = centerPoint;
        this.boundingBox = boundingBox;
        this.cornerPoints = cornerPoints;
        this.roiBox = roiBox;
        this.roiCornerPoints = roiCornerPoints;
        this.decodeSuccess = decodeSuccess;
    }
    
    public String getContent() {
        return content;
    }
    
    public String getFormat() {
        return format;
    }
    
    /**
     * 条码中心点（图像坐标系）
     */
    public PointF getCenterPoint() {
        return centerPoint;
    }
    
    /**
     * 条码边界框（图像坐标系）
     */
    public RectF getBoundingBox() {
        return boundingBox;
    }
    
    /**
     * 条码四个角点（图像坐标系），可能为null
     */
    public PointF[] getCornerPoints() {
        return cornerPoints;
    }
    
    /**
     * ROI检测框（图像坐标系），可能为null
     */
    public RectF getROIBox() {
        return roiBox;
    }
    
    /**
     * ROI检测框角点（图像坐标系），可能为null
     */
    public PointF[] getROICornerPoints() {
        return roiCornerPoints;
    }
    
    /**
     * 是否解码成功
     */
    public boolean isDecodeSuccess() {
        return decodeSuccess;
    }
    
    @Override
    public String toString() {
        return "BarcodeResult{" +
                "content='" + content + '\'' +
                ", format='" + format + '\'' +
                ", center=" + centerPoint +
                '}';
    }
}
