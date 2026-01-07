package com.urovo.templatedetector.model;

import android.graphics.PointF;
import android.graphics.RectF;

import org.litepal.crud.LitePalSupport;

/**
 * 模板区域实体
 * 定义模板中需要识别的区域（ROI）
 */
public class TemplateRegion extends LitePalSupport {

    /** 条码区域扩展比例（每边扩展 15%） */
    public static final float EXPAND_RATIO_BARCODE = 0.20f;
    
    /** 文字区域扩展比例（每边扩展 10%） */
    public static final float EXPAND_RATIO_TEXT = 0.10f;

    /**
     * 区域内容类型
     */
    public enum RegionType {
        /** 条码/二维码 */
        BARCODE,
        /** 文字（OCR） */
        TEXT
    }

    private long id;
    
    /** 区域名称（序号，如：区域1、区域2） */
    private String name;
    
    /** 所属模板ID */
    private long templateId;
    
    /** 所属模板（多对一关系） */
    private Template template;
    
    /** 区域类型（BARCODE/TEXT） */
    private String regionType;
    
    /** 边界框 - 左 */
    private float boundLeft;
    
    /** 边界框 - 上 */
    private float boundTop;
    
    /** 边界框 - 右 */
    private float boundRight;
    
    /** 边界框 - 下 */
    private float boundBottom;
    
    /** 四角点坐标（JSON格式存储） */
    private String cornerPointsJson;
    
    /** 排序顺序 */
    private int sortOrder;
    
    /** 是否启用 */
    private boolean enabled;
    
    /** 创建时间 */
    private long createTime;
    
    /** 更新时间 */
    private long updateTime;

    public TemplateRegion() {
        this.regionType = RegionType.BARCODE.name();
        this.enabled = true;
        this.sortOrder = 0;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    public TemplateRegion(String name, long templateId, RegionType type) {
        this();
        this.name = name;
        this.templateId = templateId;
        this.regionType = type.name();
    }

    // Getters and Setters
    
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(long templateId) {
        this.templateId = templateId;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }

    public RegionType getRegionType() {
        try {
            return RegionType.valueOf(regionType);
        } catch (Exception e) {
            return RegionType.BARCODE;
        }
    }

    public void setRegionType(RegionType type) {
        this.regionType = type.name();
    }

    public float getBoundLeft() {
        return boundLeft;
    }

    public void setBoundLeft(float boundLeft) {
        this.boundLeft = boundLeft;
    }

    public float getBoundTop() {
        return boundTop;
    }

    public void setBoundTop(float boundTop) {
        this.boundTop = boundTop;
    }

    public float getBoundRight() {
        return boundRight;
    }

    public void setBoundRight(float boundRight) {
        this.boundRight = boundRight;
    }

    public float getBoundBottom() {
        return boundBottom;
    }

    public void setBoundBottom(float boundBottom) {
        this.boundBottom = boundBottom;
    }

    public String getCornerPointsJson() {
        return cornerPointsJson;
    }

    public void setCornerPointsJson(String cornerPointsJson) {
        this.cornerPointsJson = cornerPointsJson;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * 设置边界框
     */
    public void setBoundingBox(RectF rect) {
        if (rect != null) {
            this.boundLeft = rect.left;
            this.boundTop = rect.top;
            this.boundRight = rect.right;
            this.boundBottom = rect.bottom;
        }
    }

    /**
     * 获取边界框
     */
    public RectF getBoundingBox() {
        return new RectF(boundLeft, boundTop, boundRight, boundBottom);
    }

    /**
     * 设置四角点
     */
    public void setCornerPoints(PointF[] points) {
        if (points != null && points.length == 4) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) sb.append(";");
                sb.append(points[i].x).append(",").append(points[i].y);
            }
            this.cornerPointsJson = sb.toString();
        }
    }

    /**
     * 获取四角点
     */
    public PointF[] getCornerPoints() {
        if (cornerPointsJson == null || cornerPointsJson.isEmpty()) {
            return new PointF[]{
                    new PointF(boundLeft, boundTop),
                    new PointF(boundRight, boundTop),
                    new PointF(boundRight, boundBottom),
                    new PointF(boundLeft, boundBottom)
            };
        }
        
        String[] parts = cornerPointsJson.split(";");
        if (parts.length != 4) {
            return null;
        }
        
        PointF[] points = new PointF[4];
        for (int i = 0; i < 4; i++) {
            String[] xy = parts[i].split(",");
            if (xy.length != 2) {
                return null;
            }
            points[i] = new PointF(Float.parseFloat(xy[0]), Float.parseFloat(xy[1]));
        }
        return points;
    }

    /**
     * 检查区域数据是否有效
     */
    public boolean isValid() {
        return boundRight > boundLeft && boundBottom > boundTop;
    }

    @Override
    public String toString() {
        return "TemplateRegion{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", templateId=" + templateId +
                ", regionType=" + regionType +
                ", bounds=[" + boundLeft + "," + boundTop + "," + boundRight + "," + boundBottom + "]" +
                '}';
    }
}
