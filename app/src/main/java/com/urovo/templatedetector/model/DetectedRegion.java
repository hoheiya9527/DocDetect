package com.urovo.templatedetector.model;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.UUID;

/**
 * 检测区域模型
 * 表示通过 OCR 或条码解码检测到的区域
 * 用于 ContentExtractor 输出和 RegionDrawView 显示
 */
public class DetectedRegion {

    /**
     * 区域类型
     */
    public enum Type {
        /** 条码/二维码 */
        BARCODE,
        /** 文字（OCR） */
        TEXT
    }

    private final String id;
    private final Type type;
    private final String content;
    private final String format;
    private final RectF boundingBox;
    private final PointF[] cornerPoints;
    private final float confidence;
    private boolean selected;

    public DetectedRegion(Type type, String content, String format,
                          RectF boundingBox, PointF[] cornerPoints, float confidence) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.content = content;
        this.format = format;
        this.boundingBox = boundingBox;
        this.cornerPoints = cornerPoints;
        this.confidence = confidence;
        this.selected = false;
    }

    /**
     * 获取唯一标识
     */
    public String getId() {
        return id;
    }

    /**
     * 获取区域类型
     */
    public Type getType() {
        return type;
    }

    /**
     * 获取内容值
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取格式（条码类型或"TEXT"）
     */
    public String getFormat() {
        return format;
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
     * 获取置信度
     */
    public float getConfidence() {
        return confidence;
    }

    /**
     * 是否选中
     */
    public boolean isSelected() {
        return selected;
    }

    /**
     * 设置选中状态
     */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    /**
     * 切换选中状态
     */
    public void toggleSelected() {
        this.selected = !this.selected;
    }

    /**
     * 检查点是否在区域内
     */
    public boolean contains(float x, float y) {
        return boundingBox != null && boundingBox.contains(x, y);
    }

    /**
     * 转换为 TemplateRegion
     */
    public TemplateRegion toTemplateRegion(String name, long templateId) {
        TemplateRegion.RegionType regionType = (type == Type.BARCODE)
                ? TemplateRegion.RegionType.BARCODE
                : TemplateRegion.RegionType.TEXT;
        
        TemplateRegion region = new TemplateRegion(name, templateId, regionType);
        region.setBoundingBox(boundingBox);
        if (cornerPoints != null) {
            region.setCornerPoints(cornerPoints);
        }
        return region;
    }

    @Override
    public String toString() {
        return "DetectedRegion{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", content='" + content + '\'' +
                ", format='" + format + '\'' +
                ", selected=" + selected +
                '}';
    }
}
