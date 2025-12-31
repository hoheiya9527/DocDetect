package com.urovo.templatedetector.model;

import org.litepal.crud.LitePalSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板实体
 * 存储模板的基本信息和特征数据
 */
public class Template extends LitePalSupport {

    private long id;
    
    /** 模板名称 */
    private String name;
    
    /** 模板描述 */
    private String description;
    
    /** 所属分类ID */
    private long categoryId;
    
    /** 所属分类（多对一关系） */
    private TemplateCategory category;
    
    /** 模板图片路径（校正后的图片） */
    private String imagePath;
    
    /** 模板图片宽度 */
    private int imageWidth;
    
    /** 模板图片高度 */
    private int imageHeight;
    
    /** 特征描述符文件路径（序列化的Mat数据） */
    private String descriptorsPath;
    
    /** 特征点文件路径（序列化的KeyPoint列表） */
    private String keypointsPath;
    
    /** 特征点数量（用于快速筛选） */
    private int keypointCount;
    
    /** 是否启用 */
    private boolean enabled;
    
    /** 使用次数（用于统计和排序） */
    private int usageCount;
    
    /** 最后使用时间 */
    private long lastUsedTime;
    
    /** 创建时间 */
    private long createTime;
    
    /** 更新时间 */
    private long updateTime;
    
    /** 模板区域列表（一对多关系） */
    private List<TemplateRegion> regions = new ArrayList<>();

    public Template() {
        this.enabled = true;
        this.usageCount = 0;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    public Template(String name, long categoryId) {
        this();
        this.name = name;
        this.categoryId = categoryId;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(long categoryId) {
        this.categoryId = categoryId;
    }

    public TemplateCategory getCategory() {
        return category;
    }

    public void setCategory(TemplateCategory category) {
        this.category = category;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public int getImageWidth() {
        return imageWidth;
    }

    public void setImageWidth(int imageWidth) {
        this.imageWidth = imageWidth;
    }

    public int getImageHeight() {
        return imageHeight;
    }

    public void setImageHeight(int imageHeight) {
        this.imageHeight = imageHeight;
    }

    public String getDescriptorsPath() {
        return descriptorsPath;
    }

    public void setDescriptorsPath(String descriptorsPath) {
        this.descriptorsPath = descriptorsPath;
    }

    public String getKeypointsPath() {
        return keypointsPath;
    }

    public void setKeypointsPath(String keypointsPath) {
        this.keypointsPath = keypointsPath;
    }

    public int getKeypointCount() {
        return keypointCount;
    }

    public void setKeypointCount(int keypointCount) {
        this.keypointCount = keypointCount;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(int usageCount) {
        this.usageCount = usageCount;
    }

    public long getLastUsedTime() {
        return lastUsedTime;
    }

    public void setLastUsedTime(long lastUsedTime) {
        this.lastUsedTime = lastUsedTime;
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

    public List<TemplateRegion> getRegions() {
        return regions;
    }

    public void setRegions(List<TemplateRegion> regions) {
        this.regions = regions;
    }

    /**
     * 增加使用次数
     */
    public void incrementUsageCount() {
        this.usageCount++;
        this.lastUsedTime = System.currentTimeMillis();
    }

    /**
     * 检查模板数据是否完整
     */
    public boolean isValid() {
        return imagePath != null && !imagePath.isEmpty()
                && descriptorsPath != null && !descriptorsPath.isEmpty()
                && keypointsPath != null && !keypointsPath.isEmpty()
                && keypointCount > 0;
    }

    @Override
    public String toString() {
        return "Template{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", categoryId=" + categoryId +
                ", keypointCount=" + keypointCount +
                ", enabled=" + enabled +
                '}';
    }
}
