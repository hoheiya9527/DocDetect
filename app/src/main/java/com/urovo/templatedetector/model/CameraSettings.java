package com.urovo.templatedetector.model;

import android.util.Size;

/**
 * 相机设置
 */
public class CameraSettings {

    /**
     * 对焦模式
     */
    public enum FocusMode {
        AUTO,        // 自动对焦
        CONTINUOUS,  // 连续对焦
        MANUAL       // 手动对焦
    }

    /**
     * 图像增强模式
     */
    public enum EnhanceMode {
        DISABLED,    // 禁用增强
        LIGHT,       // 轻量增强（检测阶段）
        FULL,        // 完整增强（检测+识别阶段）
        ADAPTIVE     // 自适应增强（根据图像质量动态调整）
    }

    /**
     * 图像增强配置
     */
    public static class EnhanceConfig {
        private boolean enableDetectionEnhance = false;      // 检测阶段增强开关
        private boolean enableRecognitionEnhance = true;     // 识别阶段增强开关
        private float lightEnhanceStrength = 0.2f;           // 轻量增强强度 (0.0-1.0)
        private float claheClipLimit = 2.0f;                 // CLAHE限制 (1.0-4.0)
        private int claheTileSize = 16;                      // CLAHE网格大小 (4-32)
        private float sharpenStrength = 0.2f;                // 锐化强度 (0.0-1.0)
        private double sharpnessThreshold = 100.0;           // 清晰度阈值
        private double contrastThreshold = 0.3;              // 对比度阈值

        public boolean isEnableDetectionEnhance() { return enableDetectionEnhance; }
        public void setEnableDetectionEnhance(boolean enable) { this.enableDetectionEnhance = enable; }

        public boolean isEnableRecognitionEnhance() { return enableRecognitionEnhance; }
        public void setEnableRecognitionEnhance(boolean enable) { this.enableRecognitionEnhance = enable; }

        public float getLightEnhanceStrength() { return lightEnhanceStrength; }
        public void setLightEnhanceStrength(float strength) { 
            this.lightEnhanceStrength = Math.max(0.0f, Math.min(1.0f, strength)); 
        }

        public float getClaheClipLimit() { return claheClipLimit; }
        public void setClaheClipLimit(float limit) { 
            this.claheClipLimit = Math.max(1.0f, Math.min(4.0f, limit)); 
        }

        public int getClaheTileSize() { return claheTileSize; }
        public void setClaheTileSize(int size) { 
            this.claheTileSize = Math.max(4, Math.min(32, size)); 
        }

        public float getSharpenStrength() { return sharpenStrength; }
        public void setSharpenStrength(float strength) { 
            this.sharpenStrength = Math.max(0.0f, Math.min(1.0f, strength)); 
        }

        public double getSharpnessThreshold() { return sharpnessThreshold; }
        public void setSharpnessThreshold(double threshold) { 
            this.sharpnessThreshold = Math.max(50.0, Math.min(300.0, threshold)); 
        }

        public double getContrastThreshold() { return contrastThreshold; }
        public void setContrastThreshold(double threshold) { 
            this.contrastThreshold = Math.max(0.1, Math.min(1.0, threshold)); 
        }

        public EnhanceConfig copy() {
            EnhanceConfig copy = new EnhanceConfig();
            copy.enableDetectionEnhance = this.enableDetectionEnhance;
            copy.enableRecognitionEnhance = this.enableRecognitionEnhance;
            copy.lightEnhanceStrength = this.lightEnhanceStrength;
            copy.claheClipLimit = this.claheClipLimit;
            copy.claheTileSize = this.claheTileSize;
            copy.sharpenStrength = this.sharpenStrength;
            copy.sharpnessThreshold = this.sharpnessThreshold;
            copy.contrastThreshold = this.contrastThreshold;
            return copy;
        }
    }

    private Size previewResolution;  // 预览分辨率
    private Size analysisResolution; // 分析分辨率
    private FocusMode focusMode;
    private int exposureCompensation;
    private int iso;
    private float zoomRatio;
    private EnhanceConfig enhanceConfig;
    private double confidenceThreshold; // 置信度阈值
    private boolean autoCapture; // 自动捕获开关
    private double autoCaptureThreshold; // 自动捕获置信度阈值

    public CameraSettings() {
        // 默认值
        this.previewResolution = new Size(1280, 720);  // 预览使用720P
        this.analysisResolution = new Size(1920, 1080); // 分析使用1080P
        this.focusMode = FocusMode.CONTINUOUS;
        this.exposureCompensation = 0;
        this.iso = 0; // 0表示自动
        this.zoomRatio = 1.0f;
        this.enhanceConfig = new EnhanceConfig();
        this.confidenceThreshold = 0.99; // 默认置信度阈值99%
        this.autoCapture = false; // 默认关闭自动捕获
        this.autoCaptureThreshold = 0.998; // 默认自动捕获阈值99.8%
    }

    public Size getPreviewResolution() {
        return previewResolution;
    }

    public void setPreviewResolution(Size previewResolution) {
        this.previewResolution = previewResolution;
    }

    public Size getAnalysisResolution() {
        return analysisResolution;
    }

    public void setAnalysisResolution(Size analysisResolution) {
        this.analysisResolution = analysisResolution;
    }

    /**
     * @deprecated 使用 getAnalysisResolution() 替代
     */
    @Deprecated
    public Size getResolution() {
        return analysisResolution;
    }

    /**
     * @deprecated 使用 setAnalysisResolution() 替代
     */
    @Deprecated
    public void setResolution(Size resolution) {
        this.analysisResolution = resolution;
    }

    public FocusMode getFocusMode() {
        return focusMode;
    }

    public void setFocusMode(FocusMode focusMode) {
        this.focusMode = focusMode;
    }

    public int getExposureCompensation() {
        return exposureCompensation;
    }

    public void setExposureCompensation(int exposureCompensation) {
        this.exposureCompensation = Math.max(-2, Math.min(2, exposureCompensation));
    }

    public int getIso() {
        return iso;
    }

    public void setIso(int iso) {
        this.iso = Math.max(0, iso);
    }

    public float getZoomRatio() {
        return zoomRatio;
    }

    public void setZoomRatio(float zoomRatio) {
        this.zoomRatio = Math.max(1.0f, zoomRatio);
    }

    public EnhanceConfig getEnhanceConfig() {
        return enhanceConfig;
    }

    public void setEnhanceConfig(EnhanceConfig enhanceConfig) {
        this.enhanceConfig = enhanceConfig != null ? enhanceConfig : new EnhanceConfig();
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = Math.max(0.1, Math.min(1.0, confidenceThreshold));
    }

    public boolean isAutoCapture() {
        return autoCapture;
    }

    public void setAutoCapture(boolean autoCapture) {
        this.autoCapture = autoCapture;
    }

    public double getAutoCaptureThreshold() {
        return autoCaptureThreshold;
    }

    public void setAutoCaptureThreshold(double autoCaptureThreshold) {
        this.autoCaptureThreshold = Math.max(0.1, Math.min(1.0, autoCaptureThreshold));
    }

    /**
     * 创建默认设置
     */
    public static CameraSettings createDefault() {
        return new CameraSettings();
    }

    /**
     * 复制设置
     */
    public CameraSettings copy() {
        CameraSettings copy = new CameraSettings();
        copy.previewResolution = this.previewResolution;
        copy.analysisResolution = this.analysisResolution;
        copy.focusMode = this.focusMode;
        copy.exposureCompensation = this.exposureCompensation;
        copy.iso = this.iso;
        copy.zoomRatio = this.zoomRatio;
        copy.enhanceConfig = this.enhanceConfig.copy();
        copy.confidenceThreshold = this.confidenceThreshold;
        copy.autoCapture = this.autoCapture;
        copy.autoCaptureThreshold = this.autoCaptureThreshold;
        return copy;
    }

    /**
     * 获取支持的分析分辨率列表（720P到4K）
     */
    public static Size[] getSupportedAnalysisResolutions() {
        return new Size[]{
                new Size(1280, 720),   // 720P
                new Size(1920, 1080),  // 1080P
                new Size(2560, 1440),  // 1440P
                new Size(3840, 2160)   // 4K
        };
    }

    /**
     * 获取分辨率显示名称
     */
    public static String getResolutionDisplayName(Size resolution) {
        if (resolution.equals(new Size(1280, 720))) return "720P";
        if (resolution.equals(new Size(1920, 1080))) return "1080P";
        if (resolution.equals(new Size(2560, 1440))) return "2K";
        if (resolution.equals(new Size(3840, 2160))) return "4K";
        return resolution.getWidth() + "x" + resolution.getHeight();
    }
}
