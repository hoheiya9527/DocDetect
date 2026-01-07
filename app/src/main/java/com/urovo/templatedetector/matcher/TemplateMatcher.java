package com.urovo.templatedetector.matcher;

import android.content.Context;
import android.graphics.Bitmap;

import com.urovo.templatedetector.model.MatchResult;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.util.MLog;

import org.opencv.core.Mat;

import java.util.List;

/**
 * 模板匹配器 - 基于ORB算法的高性能实现
 * 
 * SimpleTemplateMatcher的包装器，保持API兼容性
 */
public class TemplateMatcher {

    private static final String TAG = "TemplateMatcher";

    private final SimpleTemplateMatcher simpleMatcher;
    private volatile boolean initialized = false;

    public TemplateMatcher(Context context) {
        try {
            this.simpleMatcher = new SimpleTemplateMatcher(context);
            this.initialized = true;
        } catch (Exception e) {
            MLog.e(TAG, ">> Failed to initialize TemplateMatcher", e);
            throw new RuntimeException("TemplateMatcher initialization failed", e);
        }
    }

    /**
     * 匹配单个模板
     */
    public MatchResult match(Bitmap inputBitmap, Template template) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }

        return simpleMatcher.matchTemplate(inputBitmap, template);
    }

    /**
     * 从 Mat 匹配单个模板（避免 Mat->Bitmap 转换）
     */
    public MatchResult matchFromMat(Mat inputMat, Template template) {
        return matchFromMat(inputMat, template, false);
    }

    /**
     * 从 Mat 匹配单个模板（支持标签检测模式）
     */
    public MatchResult matchFromMat(Mat inputMat, Template template, boolean isLabelDetectionMode) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }

        return simpleMatcher.matchTemplateFromMat(inputMat, template, isLabelDetectionMode);
    }

    /**
     * 匹配多个模板，返回最佳匹配
     */
    public MatchResult matchBest(Bitmap inputBitmap, List<Template> templates) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }

        return simpleMatcher.matchBestTemplate(inputBitmap, templates);
    }

    /**
     * 从 Mat 匹配多个模板，返回最佳匹配（避免 Mat->Bitmap 转换）
     */
    public MatchResult matchBestFromMat(Mat inputMat, List<Template> templates) {
        return matchBestFromMat(inputMat, templates, false);
    }

    /**
     * 从 Mat 匹配多个模板，返回最佳匹配（支持标签检测模式）
     */
    public MatchResult matchBestFromMat(Mat inputMat, List<Template> templates, boolean isLabelDetectionMode) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }

        return simpleMatcher.matchBestTemplateFromMat(inputMat, templates, isLabelDetectionMode);
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized && simpleMatcher.isInitialized();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (initialized) {
            simpleMatcher.release();
            initialized = false;
        }
    }
}