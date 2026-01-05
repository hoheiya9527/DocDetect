package com.urovo.templatedetector.matcher;

import android.graphics.Bitmap;

import com.urovo.templatedetector.utils.MLog;

import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;

/**
 * 特征提取器 - 基于ORB算法的高性能实现
 * 
 * SimpleTemplateMatcher特征提取功能的包装器，保持API兼容性
 */
public class FeatureExtractor {

    private static final String TAG = "FeatureExtractor";

    private final SimpleTemplateMatcher simpleMatcher;
    private volatile boolean initialized = false;

    /**
     * 特征提取结果（兼容原有接口）
     */
    public static class FeatureData {
        private final SimpleTemplateMatcher.FeatureData internalData;

        public FeatureData(SimpleTemplateMatcher.FeatureData internalData) {
            this.internalData = internalData;
        }

        public MatOfKeyPoint getKeypoints() {
            return internalData != null ? internalData.getKeypoints() : null;
        }

        public Mat getDescriptors() {
            return internalData != null ? internalData.getDescriptors() : null;
        }

        public int getCount() {
            return internalData != null ? internalData.getCount() : 0;
        }

        public boolean isValid() {
            return internalData != null && internalData.isValid();
        }

        /**
         * 释放资源
         */
        public void release() {
            if (internalData != null) {
                internalData.release();
            }
        }

        /**
         * 获取提取时间（新增功能）
         */
        public long getExtractionTimeMs() {
            return internalData != null ? internalData.getExtractionTimeMs() : 0;
        }
    }

    public FeatureExtractor() {
        try {
            this.simpleMatcher = new SimpleTemplateMatcher(null);
            this.initialized = true;
        } catch (Exception e) {
            MLog.e(TAG, ">> Failed to initialize FeatureExtractor", e);
            throw new RuntimeException("FeatureExtractor initialization failed", e);
        }
    }

    /**
     * 从 Bitmap 提取特征
     */
    public FeatureData extract(Bitmap bitmap) {
        if (!initialized) {
            MLog.e(TAG, ">> extract: FeatureExtractor not initialized");
            return null;
        }

        SimpleTemplateMatcher.FeatureData result = simpleMatcher.extractFeatures(bitmap);
        return result != null ? new FeatureData(result) : null;
    }

    /**
     * 从彩色 Mat 提取特征（自动转灰度）
     */
    public FeatureData extractFromColorMat(Mat colorMat) {
        if (!initialized) {
            MLog.e(TAG, ">> extractFromColorMat: FeatureExtractor not initialized");
            return null;
        }

        SimpleTemplateMatcher.FeatureData result = simpleMatcher.extractFeaturesFromMat(colorMat);
        return result != null ? new FeatureData(result) : null;
    }

    /**
     * 从灰度 Mat 提取特征
     */
    public FeatureData extractFromGray(Mat grayMat) {
        if (!initialized) {
            MLog.e(TAG, ">> extractFromGray: FeatureExtractor not initialized");
            return null;
        }

        SimpleTemplateMatcher.FeatureData result = simpleMatcher.extractFeaturesFromMat(grayMat);
        return result != null ? new FeatureData(result) : null;
    }

    /**
     * @deprecated 使用 {@link #extractFromGray(Mat)} 代替
     */
    @Deprecated
    public FeatureData extract(Mat grayMat) {
        MLog.w(TAG, ">> extract(Mat) is deprecated, use extractFromGray(Mat) instead");
        return extractFromGray(grayMat);
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
