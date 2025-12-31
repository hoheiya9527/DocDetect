package com.urovo.templatedetector.matcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.model.TemplateCategory;
import com.urovo.templatedetector.model.TemplateRegion;

import org.litepal.LitePal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 模板数据仓库
 * 负责模板的 CRUD 操作和文件管理
 */
public class TemplateRepository {

    private static final String TAG = "TemplateRepository";

    /** 模板文件存储目录名 */
    private static final String TEMPLATE_DIR = "templates";
    
    /** 图片子目录 */
    private static final String IMAGE_DIR = "images";
    
    /** 特征子目录 */
    private static final String FEATURE_DIR = "features";

    private final Context context;
    private final File templateBaseDir;
    private final File imageDir;
    private final File featureDir;

    private static volatile TemplateRepository instance;

    private TemplateRepository(Context context) {
        this.context = context.getApplicationContext();
        
        // 初始化存储目录
        this.templateBaseDir = new File(context.getFilesDir(), TEMPLATE_DIR);
        this.imageDir = new File(templateBaseDir, IMAGE_DIR);
        this.featureDir = new File(templateBaseDir, FEATURE_DIR);
        
        ensureDirectories();
    }

    public static TemplateRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (TemplateRepository.class) {
                if (instance == null) {
                    instance = new TemplateRepository(context);
                }
            }
        }
        return instance;
    }

    /**
     * 确保存储目录存在
     */
    private void ensureDirectories() {
        if (!templateBaseDir.exists()) {
            templateBaseDir.mkdirs();
        }
        if (!imageDir.exists()) {
            imageDir.mkdirs();
        }
        if (!featureDir.exists()) {
            featureDir.mkdirs();
        }
    }

    // ==================== 分类操作 ====================

    /**
     * 获取所有分类
     */
    public List<TemplateCategory> getAllCategories() {
        return LitePal.where("enabled = ?", "1")
                .order("sortOrder desc, createTime asc")
                .find(TemplateCategory.class);
    }

    /**
     * 根据ID获取分类
     */
    public TemplateCategory getCategoryById(long id) {
        return LitePal.find(TemplateCategory.class, id);
    }

    /**
     * 保存分类
     */
    public boolean saveCategory(TemplateCategory category) {
        if (category == null) {
            return false;
        }
        category.setUpdateTime(System.currentTimeMillis());
        return category.save();
    }

    /**
     * 删除分类（同时删除该分类下的所有模板）
     */
    public boolean deleteCategory(long categoryId) {
        // 先删除该分类下的所有模板
        List<Template> templates = getTemplatesByCategory(categoryId);
        for (Template template : templates) {
            deleteTemplate(template.getId());
        }
        
        // 删除分类
        return LitePal.delete(TemplateCategory.class, categoryId) > 0;
    }

    // ==================== 模板操作 ====================

    /**
     * 获取所有模板
     */
    public List<Template> getAllTemplates() {
        return LitePal.where("enabled = ?", "1")
                .order("usageCount desc, updateTime desc")
                .find(Template.class);
    }

    /**
     * 根据分类获取模板
     */
    public List<Template> getTemplatesByCategory(long categoryId) {
        return LitePal.where("categoryId = ? and enabled = ?", String.valueOf(categoryId), "1")
                .order("usageCount desc, updateTime desc")
                .find(Template.class);
    }

    /**
     * 根据ID获取模板
     */
    public Template getTemplateById(long id) {
        return LitePal.find(Template.class, id);
    }

    /**
     * 获取模板及其区域
     */
    public Template getTemplateWithRegions(long id) {
        Template template = LitePal.find(Template.class, id);
        if (template != null) {
            List<TemplateRegion> regions = getRegionsByTemplate(id);
            template.setRegions(regions);
        }
        return template;
    }

    /**
     * 创建新模板
     * @param name 模板名称
     * @param categoryId 分类ID
     * @param image 模板图片
     * @param featureData 特征数据
     * @return 创建的模板，失败返回 null
     */
    public Template createTemplate(String name, long categoryId, 
                                   Bitmap image, FeatureExtractor.FeatureData featureData) {
        if (name == null || image == null || featureData == null || !featureData.isValid()) {
            Log.e(TAG, "createTemplate: invalid parameters");
            return null;
        }

        String uuid = UUID.randomUUID().toString();
        
        // 保存图片
        String imagePath = saveImage(uuid, image);
        if (imagePath == null) {
            Log.e(TAG, "createTemplate: failed to save image");
            return null;
        }

        // 保存特征
        String descriptorsPath = new File(featureDir, uuid + "_desc.bin").getAbsolutePath();
        String keypointsPath = new File(featureDir, uuid + "_kp.bin").getAbsolutePath();

        if (!FeatureSerializer.saveDescriptors(featureData.getDescriptors(), descriptorsPath)) {
            Log.e(TAG, "createTemplate: failed to save descriptors");
            deleteFile(imagePath);
            return null;
        }

        if (!FeatureSerializer.saveKeypoints(featureData.getKeypoints(), keypointsPath)) {
            Log.e(TAG, "createTemplate: failed to save keypoints");
            deleteFile(imagePath);
            deleteFile(descriptorsPath);
            return null;
        }

        // 创建模板记录
        Template template = new Template(name, categoryId);
        template.setImagePath(imagePath);
        template.setImageWidth(image.getWidth());
        template.setImageHeight(image.getHeight());
        template.setDescriptorsPath(descriptorsPath);
        template.setKeypointsPath(keypointsPath);
        template.setKeypointCount(featureData.getCount());

        if (!template.save()) {
            Log.e(TAG, "createTemplate: failed to save template to database");
            deleteFile(imagePath);
            deleteFile(descriptorsPath);
            deleteFile(keypointsPath);
            return null;
        }

        Log.d(TAG, "createTemplate: success, id=" + template.getId() + ", name=" + name);
        return template;
    }

    /**
     * 更新模板
     */
    public boolean updateTemplate(Template template) {
        if (template == null) {
            return false;
        }
        template.setUpdateTime(System.currentTimeMillis());
        return template.save();
    }

    /**
     * 增加模板使用次数
     */
    public void incrementTemplateUsage(long templateId) {
        Template template = getTemplateById(templateId);
        if (template != null) {
            template.incrementUsageCount();
            template.save();
        }
    }

    /**
     * 删除模板
     */
    public boolean deleteTemplate(long templateId) {
        Template template = getTemplateById(templateId);
        if (template == null) {
            return false;
        }

        // 删除关联的区域
        LitePal.deleteAll(TemplateRegion.class, "templateId = ?", String.valueOf(templateId));

        // 删除文件
        deleteFile(template.getImagePath());
        deleteFile(template.getDescriptorsPath());
        deleteFile(template.getKeypointsPath());

        // 删除数据库记录
        return LitePal.delete(Template.class, templateId) > 0;
    }

    // ==================== 区域操作 ====================

    /**
     * 获取模板的所有区域
     */
    public List<TemplateRegion> getRegionsByTemplate(long templateId) {
        return LitePal.where("templateId = ? and enabled = ?", String.valueOf(templateId), "1")
                .order("sortOrder asc, createTime asc")
                .find(TemplateRegion.class);
    }

    /**
     * 保存区域
     */
    public boolean saveRegion(TemplateRegion region) {
        if (region == null) {
            return false;
        }
        region.setUpdateTime(System.currentTimeMillis());
        return region.save();
    }

    /**
     * 批量保存区域
     */
    public boolean saveRegions(List<TemplateRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            return true;
        }
        
        LitePal.beginTransaction();
        try {
            for (TemplateRegion region : regions) {
                region.setUpdateTime(System.currentTimeMillis());
                if (!region.save()) {
                    LitePal.endTransaction();
                    return false;
                }
            }
            LitePal.setTransactionSuccessful();
            return true;
        } finally {
            LitePal.endTransaction();
        }
    }

    /**
     * 删除区域
     */
    public boolean deleteRegion(long regionId) {
        return LitePal.delete(TemplateRegion.class, regionId) > 0;
    }

    /**
     * 删除模板的所有区域
     */
    public int deleteRegionsByTemplate(long templateId) {
        return LitePal.deleteAll(TemplateRegion.class, "templateId = ?", String.valueOf(templateId));
    }

    // ==================== 文件操作 ====================

    /**
     * 保存图片
     */
    private String saveImage(String uuid, Bitmap bitmap) {
        File imageFile = new File(imageDir, uuid + ".jpg");
        
        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            return imageFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "saveImage failed", e);
            return null;
        }
    }

    /**
     * 删除文件
     */
    private void deleteFile(String path) {
        if (path != null) {
            File file = new File(path);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    /**
     * 获取存储目录大小（字节）
     */
    public long getStorageSize() {
        return getDirSize(templateBaseDir);
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        size += getDirSize(file);
                    } else {
                        size += file.length();
                    }
                }
            }
        }
        return size;
    }

    /**
     * 清理所有数据
     */
    public void clearAll() {
        // 删除数据库记录
        LitePal.deleteAll(TemplateRegion.class);
        LitePal.deleteAll(Template.class);
        LitePal.deleteAll(TemplateCategory.class);
        
        // 删除文件
        deleteDir(imageDir);
        deleteDir(featureDir);
        
        // 重新创建目录
        ensureDirectories();
        
        Log.d(TAG, "clearAll: all data cleared");
    }

    private void deleteDir(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDir(file);
                    } else {
                        file.delete();
                    }
                }
            }
            dir.delete();
        }
    }
}
