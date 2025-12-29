package com.urovo.templatedetector.model;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.UUID;

/**
 * 内容区域模型
 * 表示检测到的文字或条码区域
 */
public class ContentRegion {

    /**
     * 内容类型
     */
    public enum ContentType {
        OCR,      // 文字识别
        BARCODE   // 条码
    }

    private final String id;
    private final ContentType type;
    private final String content;
    private final String format;
    private final RectF boundingBox;
    private final PointF[] cornerPoints;
    private final float confidence;
    private boolean selected;

    public ContentRegion(ContentType type, String content, String format,
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
     * 获取内容类型
     */
    public ContentType getType() {
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
     * 获取格式化显示字符串
     * 格式: [TYPE]content
     */
    public String getFormattedDisplay() {
        String typeLabel = type == ContentType.OCR ? "OCR" : format;
        return "[" + typeLabel + "] " + content;
    }

    /**
     * 检查点是否在区域内
     */
    public boolean contains(float x, float y) {
        return boundingBox != null && boundingBox.contains(x, y);
    }

    @Override
    public String toString() {
        return "ContentRegion{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", content='" + content + '\'' +
                ", format='" + format + '\'' +
                ", selected=" + selected +
                '}';
    }
}
