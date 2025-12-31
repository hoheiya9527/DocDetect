package com.urovo.templatedetector.model;

import android.graphics.PointF;
import android.graphics.RectF;

import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板匹配结果
 * 包含匹配置信度、变换矩阵和提取的区域内容
 */
public class MatchResult {

    /** 匹配是否成功 */
    private final boolean success;
    
    /** 匹配的模板 */
    private final Template template;
    
    /** 综合置信度 (0.0 ~ 1.0) */
    private final float confidence;
    
    /** 内点比例 */
    private final float inlierRatio;
    
    /** 匹配点数量 */
    private final int matchCount;
    
    /** 平均匹配距离 */
    private final float avgDistance;
    
    /** 单应性变换矩阵 */
    private final Mat homography;
    
    /** 变换后的区域列表 */
    private final List<TransformedRegion> transformedRegions;
    
    /** 匹配耗时（毫秒） */
    private final long matchTimeMs;
    
    /** 错误信息（匹配失败时） */
    private final String errorMessage;

    private MatchResult(Builder builder) {
        this.success = builder.success;
        this.template = builder.template;
        this.confidence = builder.confidence;
        this.inlierRatio = builder.inlierRatio;
        this.matchCount = builder.matchCount;
        this.avgDistance = builder.avgDistance;
        this.homography = builder.homography;
        this.transformedRegions = builder.transformedRegions;
        this.matchTimeMs = builder.matchTimeMs;
        this.errorMessage = builder.errorMessage;
    }

    /**
     * 创建失败结果
     */
    public static MatchResult failure(String errorMessage) {
        return new Builder()
                .setSuccess(false)
                .setErrorMessage(errorMessage)
                .build();
    }

    /**
     * 创建无匹配结果
     */
    public static MatchResult noMatch() {
        return new Builder()
                .setSuccess(false)
                .setErrorMessage("No matching template found")
                .build();
    }

    // Getters
    
    public boolean isSuccess() {
        return success;
    }

    public Template getTemplate() {
        return template;
    }

    public float getConfidence() {
        return confidence;
    }

    public float getInlierRatio() {
        return inlierRatio;
    }

    public int getMatchCount() {
        return matchCount;
    }

    public float getAvgDistance() {
        return avgDistance;
    }

    public Mat getHomography() {
        return homography;
    }

    public List<TransformedRegion> getTransformedRegions() {
        return transformedRegions;
    }

    public long getMatchTimeMs() {
        return matchTimeMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 判断匹配是否可靠
     * 置信度 > 0.6 且 内点比例 > 0.5
     */
    public boolean isReliable() {
        return success && confidence > 0.6f && inlierRatio > 0.5f;
    }

    /**
     * 获取置信度等级描述
     */
    public String getConfidenceLevel(boolean isChineseLocale) {
        if (confidence > 0.8f) {
            return isChineseLocale ? "优秀" : "Excellent";
        } else if (confidence > 0.6f) {
            return isChineseLocale ? "良好" : "Good";
        } else if (confidence > 0.4f) {
            return isChineseLocale ? "一般" : "Fair";
        } else {
            return isChineseLocale ? "差" : "Poor";
        }
    }

    /**
     * 获取区域内容映射
     * @return Map<区域名称, 识别内容>
     */
    public Map<String, String> getRegionContents() {
        Map<String, String> contents = new HashMap<>();
        if (transformedRegions != null) {
            for (TransformedRegion region : transformedRegions) {
                if (region.getContent() != null) {
                    contents.put(region.getRegion().getName(), region.getContent());
                }
            }
        }
        return contents;
    }

    /**
     * 释放资源
     */
    public void release() {
        if (homography != null && !homography.empty()) {
            homography.release();
        }
    }

    @Override
    public String toString() {
        return "MatchResult{" +
                "success=" + success +
                ", template=" + (template != null ? template.getName() : "null") +
                ", confidence=" + confidence +
                ", inlierRatio=" + inlierRatio +
                ", matchCount=" + matchCount +
                ", matchTimeMs=" + matchTimeMs +
                '}';
    }

    /**
     * 变换后的区域
     */
    public static class TransformedRegion {
        
        /** 原始区域定义 */
        private final TemplateRegion region;
        
        /** 变换后的边界框 */
        private final RectF transformedBounds;
        
        /** 变换后的四角点 */
        private final PointF[] transformedCorners;
        
        /** 识别到的内容 */
        private String content;
        
        /** 内容格式（条码类型或TEXT） */
        private String format;
        
        /** 识别置信度 */
        private float recognitionConfidence;

        public TransformedRegion(TemplateRegion region, RectF transformedBounds, PointF[] transformedCorners) {
            this.region = region;
            this.transformedBounds = transformedBounds;
            this.transformedCorners = transformedCorners;
        }

        public TemplateRegion getRegion() {
            return region;
        }

        public RectF getTransformedBounds() {
            return transformedBounds;
        }

        public PointF[] getTransformedCorners() {
            return transformedCorners;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public float getRecognitionConfidence() {
            return recognitionConfidence;
        }

        public void setRecognitionConfidence(float recognitionConfidence) {
            this.recognitionConfidence = recognitionConfidence;
        }

        /**
         * 是否已识别到内容
         */
        public boolean hasContent() {
            return content != null && !content.isEmpty();
        }
    }

    /**
     * Builder模式构建器
     */
    public static class Builder {
        private boolean success = false;
        private Template template;
        private float confidence = 0f;
        private float inlierRatio = 0f;
        private int matchCount = 0;
        private float avgDistance = Float.MAX_VALUE;
        private Mat homography;
        private List<TransformedRegion> transformedRegions = new ArrayList<>();
        private long matchTimeMs = 0;
        private String errorMessage;

        public Builder setSuccess(boolean success) {
            this.success = success;
            return this;
        }

        public Builder setTemplate(Template template) {
            this.template = template;
            return this;
        }

        public Builder setConfidence(float confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder setInlierRatio(float inlierRatio) {
            this.inlierRatio = inlierRatio;
            return this;
        }

        public Builder setMatchCount(int matchCount) {
            this.matchCount = matchCount;
            return this;
        }

        public Builder setAvgDistance(float avgDistance) {
            this.avgDistance = avgDistance;
            return this;
        }

        public Builder setHomography(Mat homography) {
            this.homography = homography;
            return this;
        }

        public Builder setTransformedRegions(List<TransformedRegion> transformedRegions) {
            this.transformedRegions = transformedRegions;
            return this;
        }

        public Builder setMatchTimeMs(long matchTimeMs) {
            this.matchTimeMs = matchTimeMs;
            return this;
        }

        public Builder setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public MatchResult build() {
            return new MatchResult(this);
        }
    }
}
