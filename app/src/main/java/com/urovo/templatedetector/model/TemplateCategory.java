package com.urovo.templatedetector.model;

import org.litepal.crud.LitePalSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板分类实体
 * 用于对模板进行分组管理（如：顺丰、京东、圆通等快递公司）
 */
public class TemplateCategory extends LitePalSupport {

    private long id;
    
    /** 分类名称 */
    private String name;
    
    /** 分类描述 */
    private String description;
    
    /** 分类图标路径（可选） */
    private String iconPath;
    
    /** 排序权重（数值越大越靠前） */
    private int sortOrder;
    
    /** 是否启用 */
    private boolean enabled;
    
    /** 创建时间 */
    private long createTime;
    
    /** 更新时间 */
    private long updateTime;
    
    /** 该分类下的模板列表（一对多关系） */
    private List<Template> templates = new ArrayList<>();

    public TemplateCategory() {
        this.enabled = true;
        this.sortOrder = 0;
        this.createTime = System.currentTimeMillis();
        this.updateTime = this.createTime;
    }

    public TemplateCategory(String name) {
        this();
        this.name = name;
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

    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
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

    public List<Template> getTemplates() {
        return templates;
    }

    public void setTemplates(List<Template> templates) {
        this.templates = templates;
    }

    @Override
    public String toString() {
        return "TemplateCategory{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
