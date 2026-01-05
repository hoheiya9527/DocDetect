package com.urovo.templatedetector.matcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.urovo.templatedetector.model.MatchResult;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.model.TemplateCategory;
import com.urovo.templatedetector.model.TemplateRegion;

import org.opencv.core.Mat;

import java.util.List;

/**
 * 模板匹配服务
 * 提供模板匹配的高层 API，整合分类管理、模板匹配和内容识别
 */
public class TemplateMatchingService {

    private static final String TAG = "TemplateMatchingService";

    /** 最低可接受置信度 */
    private static final float MIN_ACCEPTABLE_CONFIDENCE = 0.4f;

    private final Context context;
    private final TemplateRepository repository;
    private final TemplateMatcher matcher;
    private final FeatureExtractor featureExtractor;

    private boolean initialized = false;

    private static volatile TemplateMatchingService instance;

    private TemplateMatchingService(Context context) {
        this.context = context.getApplicationContext();
        this.repository = TemplateRepository.getInstance(context);
        this.matcher = new TemplateMatcher(context);
        this.featureExtractor = new FeatureExtractor();
        this.initialized = true;
        Log.d(TAG, "TemplateMatchingService initialized");
    }

    public static TemplateMatchingService getInstance(Context context) {
        if (instance == null) {
            synchronized (TemplateMatchingService.class) {
                if (instance == null) {
                    instance = new TemplateMatchingService(context);
                }
            }
        }
        return instance;
    }

    // ==================== 分类管理 ====================

    /**
     * 获取所有分类
     */
    public List<TemplateCategory> getAllCategories() {
        return repository.getAllCategories();
    }

    /**
     * 创建分类
     */
    public TemplateCategory createCategory(String name) {
        TemplateCategory category = new TemplateCategory(name);
        if (repository.saveCategory(category)) {
            return category;
        }
        return null;
    }

    /**
     * 更新分类
     */
    public boolean updateCategory(TemplateCategory category) {
        return repository.saveCategory(category);
    }

    /**
     * 删除分类
     */
    public boolean deleteCategory(long categoryId) {
        return repository.deleteCategory(categoryId);
    }

    // ==================== 模板管理 ====================

    /**
     * 获取分类下的所有模板
     */
    public List<Template> getTemplatesByCategory(long categoryId) {
        return repository.getTemplatesByCategory(categoryId);
    }

    /**
     * 获取所有模板
     */
    public List<Template> getAllTemplates() {
        return repository.getAllTemplates();
    }

    /**
     * 获取模板详情（包含区域）
     */
    public Template getTemplateWithRegions(long templateId) {
        return repository.getTemplateWithRegions(templateId);
    }

    /**
     * 从图片创建模板
     * @param name 模板名称
     * @param categoryId 分类ID
     * @param image 校正后的模板图片
     * @return 创建的模板，失败返回 null
     */
    public Template createTemplate(String name, long categoryId, Bitmap image) {
        if (image == null) {
            Log.e(TAG, "createTemplate: image is null");
            return null;
        }

        // 提取特征
        FeatureExtractor.FeatureData featureData = featureExtractor.extract(image);
        if (featureData == null || !featureData.isValid()) {
            Log.e(TAG, "createTemplate: failed to extract features");
            return null;
        }

        // 创建模板
        Template template = repository.createTemplate(name, categoryId, image, featureData);
        
        // 释放特征数据
        featureData.release();

        return template;
    }

    /**
     * 为模板添加区域
     */
    public boolean addRegionToTemplate(long templateId, TemplateRegion region) {
        if (region == null) {
            return false;
        }
        region.setTemplateId(templateId);
        return repository.saveRegion(region);
    }

    /**
     * 批量添加区域到模板
     */
    public boolean addRegionsToTemplate(long templateId, List<TemplateRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return true;
        }
        for (TemplateRegion region : regions) {
            region.setTemplateId(templateId);
        }
        return repository.saveRegions(regions);
    }

    /**
     * 更新模板
     */
    public boolean updateTemplate(Template template) {
        return repository.updateTemplate(template);
    }

    /**
     * 删除模板
     */
    public boolean deleteTemplate(long templateId) {
        return repository.deleteTemplate(templateId);
    }

    // ==================== 模板匹配 ====================

    /**
     * 匹配指定模板
     * @param image 输入图像
     * @param templateId 模板ID
     * @return 匹配结果
     */
    public MatchResult matchTemplate(Bitmap image, long templateId) {
        if (!initialized) {
            return MatchResult.failure("Service not initialized");
        }

        Template template = repository.getTemplateWithRegions(templateId);
        if (template == null) {
            return MatchResult.failure("Template not found: " + templateId);
        }

        MatchResult result = matcher.match(image, template);
        
        // 更新使用统计
        if (result.isSuccess()) {
            repository.incrementTemplateUsage(templateId);
        }

        return result;
    }

    /**
     * 从 Mat 匹配指定模板（避免格式转换）
     * @param inputMat 输入图像 Mat
     * @param templateId 模板ID
     * @return 匹配结果
     */
    public MatchResult matchTemplateFromMat(Mat inputMat, long templateId) {
        return matchTemplateFromMat(inputMat, templateId, false);
    }

    /**
     * 从 Mat 匹配指定模板（支持标签检测模式）
     * @param inputMat 输入图像 Mat
     * @param templateId 模板ID
     * @param isLabelDetectionMode 是否为标签检测模式
     * @return 匹配结果
     */
    public MatchResult matchTemplateFromMat(Mat inputMat, long templateId, boolean isLabelDetectionMode) {
        if (!initialized) {
            return MatchResult.failure("Service not initialized");
        }

        Template template = repository.getTemplateWithRegions(templateId);
        if (template == null) {
            return MatchResult.failure("Template not found: " + templateId);
        }

        MatchResult result = matcher.matchFromMat(inputMat, template, isLabelDetectionMode);
        
        if (result.isSuccess()) {
            repository.incrementTemplateUsage(templateId);
        }

        return result;
    }

    /**
     * 在指定分类中匹配最佳模板
     * @param image 输入图像
     * @param categoryId 分类ID
     * @return 最佳匹配结果
     */
    public MatchResult matchInCategory(Bitmap image, long categoryId) {
        if (!initialized) {
            return MatchResult.failure("Service not initialized");
        }

        List<Template> templates = repository.getTemplatesByCategory(categoryId);
        if (templates.isEmpty()) {
            return MatchResult.failure("No templates in category: " + categoryId);
        }

        // 加载每个模板的区域
        for (Template template : templates) {
            List<TemplateRegion> regions = repository.getRegionsByTemplate(template.getId());
            template.setRegions(regions);
        }

        MatchResult result = matcher.matchBest(image, templates);
        
        // 更新使用统计
        if (result.isSuccess() && result.getTemplate() != null) {
            repository.incrementTemplateUsage(result.getTemplate().getId());
        }

        return result;
    }

    /**
     * 在所有模板中匹配最佳模板
     * @param image 输入图像
     * @return 最佳匹配结果
     */
    public MatchResult matchAll(Bitmap image) {
        if (!initialized) {
            return MatchResult.failure("Service not initialized");
        }

        List<Template> templates = repository.getAllTemplates();
        if (templates.isEmpty()) {
            return MatchResult.failure("No templates available");
        }

        // 加载每个模板的区域
        for (Template template : templates) {
            List<TemplateRegion> regions = repository.getRegionsByTemplate(template.getId());
            template.setRegions(regions);
        }

        MatchResult result = matcher.matchBest(image, templates);
        
        // 更新使用统计
        if (result.isSuccess() && result.getTemplate() != null) {
            repository.incrementTemplateUsage(result.getTemplate().getId());
        }

        return result;
    }

    /**
     * 从 Mat 在所有模板中匹配最佳模板（避免格式转换）
     * @param inputMat 输入图像 Mat
     * @return 最佳匹配结果
     */
    public MatchResult matchAllFromMat(Mat inputMat) {
        return matchAllFromMat(inputMat, false);
    }

    /**
     * 从 Mat 在所有模板中匹配最佳模板（支持标签检测模式）
     * @param inputMat 输入图像 Mat
     * @param isLabelDetectionMode 是否为标签检测模式
     * @return 最佳匹配结果
     */
    public MatchResult matchAllFromMat(Mat inputMat, boolean isLabelDetectionMode) {
        if (!initialized) {
            return MatchResult.failure("Service not initialized");
        }

        List<Template> templates = repository.getAllTemplates();
        if (templates.isEmpty()) {
            return MatchResult.failure("No templates available");
        }

        // 加载每个模板的区域
        for (Template template : templates) {
            List<TemplateRegion> regions = repository.getRegionsByTemplate(template.getId());
            template.setRegions(regions);
        }

        MatchResult result = matcher.matchBestFromMat(inputMat, templates, isLabelDetectionMode);
        
        if (result.isSuccess() && result.getTemplate() != null) {
            repository.incrementTemplateUsage(result.getTemplate().getId());
        }

        return result;
    }

    /**
     * 按分类优先级匹配
     * 先尝试指定分类，如果失败则尝试所有分类
     * @param image 输入图像
     * @param preferredCategoryId 优先分类ID，-1 表示无优先
     * @return 最佳匹配结果
     */
    public MatchResult matchWithPriority(Bitmap image, long preferredCategoryId) {
        if (!initialized) {
            return MatchResult.failure("Service not initialized");
        }

        // 先尝试优先分类
        if (preferredCategoryId > 0) {
            MatchResult result = matchInCategory(image, preferredCategoryId);
            if (result.isSuccess() && result.getConfidence() >= MIN_ACCEPTABLE_CONFIDENCE) {
                return result;
            }
            result.release();
        }

        // 尝试所有模板
        return matchAll(image);
    }

    // ==================== 统计信息 ====================

    /**
     * 获取模板数量
     */
    public int getTemplateCount() {
        return repository.getAllTemplates().size();
    }

    /**
     * 获取分类数量
     */
    public int getCategoryCount() {
        return repository.getAllCategories().size();
    }

    /**
     * 获取存储大小（字节）
     */
    public long getStorageSize() {
        return repository.getStorageSize();
    }

    /**
     * 清理所有数据
     */
    public void clearAll() {
        repository.clearAll();
    }

    // ==================== 生命周期 ====================

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 释放资源
     */
    public void release() {
        if (matcher != null) {
            matcher.release();
        }
        if (featureExtractor != null) {
            featureExtractor.release();
        }
        initialized = false;
        instance = null;
        Log.d(TAG, "TemplateMatchingService released");
    }
}
