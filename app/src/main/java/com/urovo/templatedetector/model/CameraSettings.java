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
        private boolean enableEnhance = true;  // 增强开关

        public boolean isEnableEnhance() { 
            return enableEnhance; 
        }
        
        public void setEnableEnhance(boolean enable) { 
            this.enableEnhance = enable; 
        }

        public EnhanceConfig copy() {
            EnhanceConfig copy = new EnhanceConfig();
            copy.enableEnhance = this.enableEnhance;
            return copy;
        }
    }

    private Size previewResolution;  // 预览分辨率
    private Size analysisResolution; // 分析分辨率
    private FocusMode focusMode;
    private int exposureCompensation;
    private boolean autoExposure; // 自动曝光开关
    private long shutterSpeed; // 快门速度（纳秒），0表示自动
    private int iso;
    private float zoomRatio;
    private EnhanceConfig enhanceConfig;
    private double confidenceThreshold; // 置信度阈值
    private boolean autoCapture; // 自动捕获开关
    private double autoCaptureThreshold; // 自动捕获置信度阈值

    // 常用快门速度值（纳秒）
    public static final long[] SHUTTER_SPEED_VALUES = {
            0L,                  // 自动
            1000000000L / 8000,  // 1/8000s
            1000000000L / 4000,  // 1/4000s
            1000000000L / 2000,  // 1/2000s
            1000000000L / 1000,  // 1/1000s
            1000000000L / 500,   // 1/500s
            1000000000L / 250,   // 1/250s
            1000000000L / 125,   // 1/125s
            1000000000L / 60,    // 1/60s
            1000000000L / 30,    // 1/30s
            1000000000L / 15     // 1/15s
    };

    public static final String[] SHUTTER_SPEED_LABELS = {
            "Auto", "1/8000", "1/4000", "1/2000", "1/1000",
            "1/500", "1/250", "1/125", "1/60", "1/30", "1/15"
    };

    public CameraSettings() {
        // 默认值
        this.previewResolution = new Size(1280, 720);  // 预览使用720P
        this.analysisResolution = new Size(1920, 1080); // 分析使用1080P
        this.focusMode = FocusMode.CONTINUOUS;
        this.exposureCompensation = 0;
        this.autoExposure = true; // 默认开启自动曝光
        this.shutterSpeed = 0; // 0表示自动
        this.iso = 0; // 0表示自动
        this.zoomRatio = 1.0f;
        this.enhanceConfig = new EnhanceConfig();
        this.confidenceThreshold = 0.99; // 默认置信度阈值99%
        this.autoCapture = true; // 默认开启自动捕获
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

    public boolean isAutoExposure() {
        return autoExposure;
    }

    public void setAutoExposure(boolean autoExposure) {
        this.autoExposure = autoExposure;
    }

    public long getShutterSpeed() {
        return shutterSpeed;
    }

    public void setShutterSpeed(long shutterSpeed) {
        this.shutterSpeed = Math.max(0, shutterSpeed);
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
        copy.autoExposure = this.autoExposure;
        copy.shutterSpeed = this.shutterSpeed;
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
