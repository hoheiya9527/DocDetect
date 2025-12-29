package com.urovo.templatedetector.presenter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;
import android.util.Size;

import androidx.camera.core.ImageProxy;

import com.urovo.templatedetector.camera.CameraController;
import com.urovo.templatedetector.detector.LabelDetector;
import com.urovo.templatedetector.extractor.ContentExtractor;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.model.ContentRegion;
import com.urovo.templatedetector.model.DetectionResult;
import com.urovo.templatedetector.util.AdaptiveEnhancer;
import com.urovo.templatedetector.util.CameraConfigManager;
import com.urovo.templatedetector.util.ImageEnhancer;
import com.urovo.templatedetector.util.PerformanceTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 主界面Presenter
 * 实现MVP架构中的业务逻辑层
 */
public class MainPresenter implements IMainPresenter {

    private static final String TAG = "MainPresenter";

    // 模糊检测阈值
    private static final double SHARPNESS_THRESHOLD = 100.0;
    
    // 相机启动稳定所需的帧数
    private static final int CAMERA_WARMUP_FRAMES = 5;

    /**
     * 应用状态
     */
    public enum AppState {
        PREVIEW,      // 相机预览中
        DETECTING,    // 检测中
        CAPTURED,     // 已捕获
        EXTRACTING,   // 提取内容中
        SELECTING     // 选择内容中
    }

    private IMainView view;
    private final Context context;
    private final CameraController cameraController;
    private final LabelDetector labelDetector;
    private final ContentExtractor contentExtractor;
    private final CameraConfigManager configManager;

    private AppState currentState = AppState.PREVIEW;
    private DetectionResult lastDetectionResult;
    private Bitmap capturedImage;
    private Bitmap correctedImage;
    private List<ContentRegion> contentRegions = new ArrayList<>();
    private Set<String> selectedRegionIds = new HashSet<>();

    // 帧处理控制 - 简单的处理状态控制
    private volatile boolean isProcessingFrame = false;
    
    // 相机预热计数器（跳过前几帧，等待自动曝光/白平衡稳定）
    private volatile int warmupFrameCount = 0;

    // 置信度过滤系统
    private ConfidenceFilter confidenceFilter = new ConfidenceFilter();
    
    // 绿框状态管理
    private boolean isDetectionBoxVisible = false;
    private static final int LOW_CONFIDENCE_BUFFER_COUNT = 3; // 连续3次低置信度才隐藏绿框
    private int lowConfidenceCount = 0;

    public MainPresenter(Context context, CameraController cameraController,
                         LabelDetector labelDetector, ContentExtractor contentExtractor) {
        this.context = context;
        this.cameraController = cameraController;
        this.labelDetector = labelDetector;
        this.contentExtractor = contentExtractor;
        this.configManager = CameraConfigManager.getInstance(context);
    }

    @Override
    public void attachView(IMainView view) {
        this.view = view;
    }

    @Override
    public void detachView() {
        this.view = null;
    }

    @Override
    public void onCreate() {
        // 组件已在 AppInitializer 中初始化，这里不需要再初始化
        Log.d(TAG, "MainPresenter onCreate - components already initialized");
    }

    @Override
    public void onResume() {
        if (currentState == AppState.PREVIEW) {
            // 重置预热计数器，等待相机稳定
            warmupFrameCount = 0;
            cameraController.startPreview();
            if (view != null) {
                view.showGuidanceText(getGuidanceText());
            }
        }
    }

    @Override
    public void onPause() {
        if (currentState == AppState.PREVIEW || currentState == AppState.DETECTING) {
            cameraController.stopPreview();
        }
    }

    @Override
    public void onDestroy() {
        // 只释放 Presenter 自己创建的组件
        // labelDetector 和 barcodeDecoder 由 AppInitializer 管理，不在这里释放
        cameraController.release();
        contentExtractor.release();

        if (capturedImage != null && !capturedImage.isRecycled()) {
            capturedImage.recycle();
        }
        if (correctedImage != null && !correctedImage.isRecycled()) {
            correctedImage.recycle();
        }
    }

    @Override
    public void onFrameAvailable(ImageProxy image, int rotationDegrees) {
        Log.d(TAG, "onFrameAvailable called, view=" + (view != null) + ", state=" + currentState);
        
        // 只有在 PREVIEW 状态下才处理帧，CAPTURED/EXTRACTING/SELECTING 状态下跳过
        if (view == null || (currentState != AppState.PREVIEW && currentState != AppState.DETECTING)) {
            Log.d(TAG, "Skipping frame: view=" + (view != null) + ", state=" + currentState);
            image.close();
            return;
        }
        
        // 相机预热：跳过前几帧，等待自动曝光/白平衡稳定
        if (warmupFrameCount < CAMERA_WARMUP_FRAMES) {
            warmupFrameCount++;
            Log.d(TAG, "Camera warmup: frame " + warmupFrameCount + "/" + CAMERA_WARMUP_FRAMES);
            image.close();
            return;
        }

        // 自然帧率控制：如果上一帧还在处理，直接丢弃新帧
        if (isProcessingFrame) {
            Log.d(TAG, "Skipping frame: already processing");
            image.close();
            return;
        }

        isProcessingFrame = true;
        Log.d(TAG, "Processing frame...");

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.FRAME_PROCESSING)) {
            
            // 跳过质量评估以提高性能
            // AdaptiveEnhancer.QualityMetrics quality = AdaptiveEnhancer.assessQuality(image);
            // Log.d(TAG, "Frame quality: " + quality);

            // 获取当前相机设置
            CameraSettings settings = cameraController.getCurrentSettings();
            CameraSettings.EnhanceConfig enhanceConfig = settings.getEnhanceConfig();

            // 跳过模糊检测以提高性能
            // byte[] yuvData = extractYuvData(image);
            // if (yuvData != null) {
            //     double sharpness = ImageEnhancer.calculateSharpness(
            //             yuvData, image.getWidth(), image.getHeight());
            //     if (sharpness < SHARPNESS_THRESHOLD) {
            //         Log.d(TAG, "Frame too blurry: " + sharpness);
            //         image.close();
            //         isProcessingFrame = false;
            //         return;
            //     }
            // }

            // 直接进行图像转换
            Bitmap frameBitmap = imageProxyToBitmap(image, rotationDegrees);
            if (frameBitmap == null) {
                Log.w(TAG, "Failed to convert frame to bitmap");
                image.close();
                isProcessingFrame = false;
                return;
            }

            // 快速降采样准备检测
            Bitmap detectionBitmap = prepareDetectionBitmap(frameBitmap, enhanceConfig);
            
            // 标签检测（使用降采样后的图像）
            Log.d(TAG, "Starting detection on " + detectionBitmap.getWidth() + "x" + detectionBitmap.getHeight() + " image...");
            currentState = AppState.DETECTING;
            DetectionResult result = labelDetector.detect(detectionBitmap);
            
            // 坐标映射（如果使用了降采样）
            if (detectionBitmap != frameBitmap && result != null && result.isDetected()) {
                result = mapDetectionResult(result, frameBitmap, detectionBitmap);
            }
            
            // 清理检测用的图像（如果不同于原始图像）
            if (detectionBitmap != frameBitmap) {
                detectionBitmap.recycle();
            }

            // 检测完成后，检查状态是否已变为CAPTURED
            // 如果已变为CAPTURED，则忽略这次检测结果，不更新UI
            if (currentState == AppState.CAPTURED) {
                Log.d(TAG, "State is CAPTURED, ignoring detection result");
                // 清理检测用的图像（如果不同于原始图像）
                if (detectionBitmap != frameBitmap) {
                    detectionBitmap.recycle();
                }
                frameBitmap.recycle();
                image.close();
                isProcessingFrame = false;
                return;
            }

            // 检测完成后，再次检查状态
            // 如果用户已经点击了确认（状态变为 CAPTURED），则不更新结果，避免覆盖
            if (currentState != AppState.DETECTING) {
                Log.d(TAG, "State changed during detection, discarding result. state=" + currentState);
                // 清理检测用的图像（如果不同于原始图像）
                if (detectionBitmap != frameBitmap) {
                    detectionBitmap.recycle();
                }
                frameBitmap.recycle();
                image.close();
                isProcessingFrame = false;
                return;
            }

            if (result != null && result.isDetected()) {
                Log.d(TAG, "Detection successful, corners=" + java.util.Arrays.toString(result.getCornerPoints()));

                // 置信度过滤
                DetectionResult filteredResult = confidenceFilter.filter(result);
                if (filteredResult == null) {
                    Log.d(TAG, "Detection result filtered by confidence filter");
                    
                    // 增加低置信度计数
                    lowConfidenceCount++;
                    
                    // 只有连续多次低置信度才隐藏绿框
                    if (isDetectionBoxVisible && lowConfidenceCount >= LOW_CONFIDENCE_BUFFER_COUNT) {
                        if (view != null && currentState == AppState.DETECTING) {
                            view.hideDetectionBox();
                            isDetectionBoxVisible = false;
                            Log.d(TAG, "Hiding detection box after " + lowConfidenceCount + " low confidence detections");
                        }
                    }
                    
                    // 显示真实置信度值，即使低于阈值
                    if (view != null && currentState == AppState.DETECTING) {
                        view.updateConfidenceDisplay(result.getConfidence());
                        currentState = AppState.PREVIEW;
                    }
                    
                    // 清理检测用的图像（如果不同于原始图像）
                    if (detectionBitmap != frameBitmap) {
                        detectionBitmap.recycle();
                    }
                    frameBitmap.recycle();
                    image.close();
                    isProcessingFrame = false;
                    return;
                }

                lastDetectionResult = filteredResult;
                Log.d(TAG, "Confidence filtered result, updating UI");

                // 重置低置信度计数，因为检测成功
                lowConfidenceCount = 0;

                // 检查是否需要自动捕获
                if (settings.isAutoCapture() && 
                    filteredResult.getConfidence() >= settings.getAutoCaptureThreshold()) {
                    Log.d(TAG, "Auto capture triggered, confidence=" + filteredResult.getConfidence() + 
                          ", threshold=" + settings.getAutoCaptureThreshold());
                    
                    // 延迟一点执行自动捕获，让用户看到绿框
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (currentState == AppState.PREVIEW && lastDetectionResult != null) {
                            onConfirmCapture();
                        }
                    }, 200); // 200ms延迟
                }

                // 缓存原始帧用于后续处理
                setCapturedImage(frameBitmap);

                // 只有在预览状态下才显示检测框，避免捕获后仍然显示
                if (view != null && currentState == AppState.DETECTING) {
                    // 设置源图像尺寸用于坐标转换
                    // 旋转后的图像尺寸：如果旋转90或270度，宽高已经交换
                    int sourceWidth, sourceHeight;
                    if (rotationDegrees == 90 || rotationDegrees == 270) {
                        sourceWidth = image.getHeight();
                        sourceHeight = image.getWidth();
                    } else {
                        sourceWidth = image.getWidth();
                        sourceHeight = image.getHeight();
                    }
                    view.setSourceSize(sourceWidth, sourceHeight);
                    view.showDetectionBox(filteredResult.getBoundingBox(), filteredResult.getCornerPoints());
                    isDetectionBoxVisible = true; // 标记绿框已显示
                    
                    // 更新置信度显示 - 显示真实值
                    view.updateConfidenceDisplay(filteredResult.getConfidence());
                    
                    currentState = AppState.PREVIEW;
                }
            } else {
                Log.d(TAG, "Detection failed or no result");
                lastDetectionResult = null;
                frameBitmap.recycle();
                if (view != null && currentState == AppState.DETECTING) {
                    view.hideDetectionBox();
                    isDetectionBoxVisible = false; // 标记绿框已隐藏
                    // 显示真实置信度值，即使低于阈值
                    view.updateConfidenceDisplay(result != null ? result.getConfidence() : 0.0);
                    currentState = AppState.PREVIEW;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Frame processing failed", e);
            currentState = AppState.PREVIEW;
        } finally {
            image.close();
            isProcessingFrame = false;
        }
    }

    @Override
    public void onZoomChanged(float ratio) {
        // 直接设置变焦比例，不再使用缩放因子
        cameraController.setZoomRatio(ratio);
    }

    @Override
    public void onConfirmCapture() {
        if (view == null || lastDetectionResult == null || !lastDetectionResult.isDetected()) {
            if (view != null) {
                view.showToast("请先对准标签");
            }
            return;
        }

        // 立即设置状态为 CAPTURED，后续检测结果将被忽略
        currentState = AppState.CAPTURED;
        
        // 停止预览
        cameraController.stopPreview();

        if (view != null) {
            view.hideDetectionBox();  // 隐藏检测框
            view.stopCameraPreview();
            view.showLoading("正在处理...");
        }

        // 清除检测结果，防止重新显示
        DetectionResult capturedResult = lastDetectionResult;
        lastDetectionResult = null;

        // 异步处理
        new Thread(() -> {
            try {
                Log.d(TAG, "onConfirmCapture: starting extraction, capturedImage=" + (capturedImage != null));
                
                // 1. 提取并校正图像
                if (capturedImage != null) {
                    Log.d(TAG, "onConfirmCapture: capturedImage size=" + capturedImage.getWidth() + "x" + capturedImage.getHeight());
                    Log.d(TAG, "onConfirmCapture: capturedResult corners=" + java.util.Arrays.toString(capturedResult.getCornerPoints()));
                    
                    correctedImage = labelDetector.extractAndCorrect(capturedImage, capturedResult);
                    Log.d(TAG, "onConfirmCapture: correctedImage=" + (correctedImage != null));

                    if (correctedImage != null) {
                        Log.d(TAG, "onConfirmCapture: correctedImage size=" + correctedImage.getWidth() + "x" + correctedImage.getHeight());
                        
                        // 2. 显示校正后的图像
                        postToMain(() -> {
                            if (view != null) {
                                view.showCorrectedImage(correctedImage);
                            }
                        });

                        // 4. 提取内容
                        currentState = AppState.EXTRACTING;
                        contentExtractor.extract(correctedImage, new ContentExtractor.ExtractionCallback() {
                            @Override
                            public void onProgress(int current, int total) {
                                postToMain(() -> {
                                    if (view != null) {
                                        view.showLoading("识别中 " + current + "/" + total);
                                    }
                                });
                            }

                            @Override
                            public void onComplete(List<ContentRegion> regions) {
                                contentRegions = regions;
                                currentState = AppState.SELECTING;
                                postToMain(() -> {
                                    if (view != null) {
                                        view.hideLoading();
                                        view.showContentRegions(regions);
                                        view.showGuidanceText(getGuidanceText());
                                    }
                                });
                            }

                            @Override
                            public void onError(Exception e) {
                                Log.e(TAG, "Content extraction failed", e);
                                postToMain(() -> {
                                    if (view != null) {
                                        view.hideLoading();
                                        view.showToast("内容识别失败");
                                    }
                                });
                            }
                        });
                    } else {
                        postToMain(() -> {
                            if (view != null) {
                                view.hideLoading();
                                view.showToast("图像校正失败");
                            }
                        });
                    }
                } else {
                    postToMain(() -> {
                        if (view != null) {
                            view.hideLoading();
                            view.showToast("未捕获图像");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Capture processing failed", e);
                postToMain(() -> {
                    if (view != null) {
                        view.hideLoading();
                        view.showToast("处理失败: " + e.getMessage());
                    }
                });
            }
        }).start();
    }

    @Override
    public void onRegionClick(String regionId) {
        if (currentState != AppState.SELECTING || view == null) {
            return;
        }

        // Toggle选择状态
        boolean nowSelected;
        if (selectedRegionIds.contains(regionId)) {
            selectedRegionIds.remove(regionId);
            nowSelected = false;
        } else {
            selectedRegionIds.add(regionId);
            nowSelected = true;
        }

        // 更新区域选择状态
        for (ContentRegion region : contentRegions) {
            if (region.getId().equals(regionId)) {
                region.setSelected(nowSelected);
                break;
            }
        }

        // 更新UI
        view.updateRegionSelection(regionId, nowSelected);
        updateSelectedContentDisplay();
    }

    @Override
    public void onComplete() {
        if (view == null) {
            return;
        }

        List<ContentRegion> selected = getSelectedRegions();
        if (selected.isEmpty()) {
            view.showToast("请至少选择一个内容区域");
            return;
        }

        view.finishWithResult(selected);
    }

    @Override
    public void onCancel() {
        resetState();
        
        // 重置预热计数器
        warmupFrameCount = 0;

        if (view != null) {
            view.clearContentRegions();  // 这会清理所有覆盖层并重新显示相机预览
            view.showGuidanceText(getGuidanceText());
        }

        cameraController.startPreview();
    }

    @Override
    public void onSettingsClick() {
        // 由View处理设置对话框显示
    }

    /**
     * 设置捕获的图像
     */
    public void setCapturedImage(Bitmap bitmap) {
        if (capturedImage != null && !capturedImage.isRecycled()) {
            capturedImage.recycle();
        }
        this.capturedImage = bitmap;
    }

    /**
     * 获取当前状态
     */
    public AppState getCurrentState() {
        return currentState;
    }

    /**
     * 获取已选区域
     */
    public List<ContentRegion> getSelectedRegions() {
        return contentRegions.stream()
                .filter(ContentRegion::isSelected)
                .collect(Collectors.toList());
    }

    /**
     * 重置状态
     */
    private void resetState() {
        currentState = AppState.PREVIEW;
        lastDetectionResult = null;
        contentRegions.clear();
        selectedRegionIds.clear();
        
        // 重置帧处理状态
        isProcessingFrame = false;
        warmupFrameCount = 0;
        
        // 重置绿框状态和缓冲计数
        isDetectionBoxVisible = false;
        lowConfidenceCount = 0;
        
        // 重置置信度过滤器
        confidenceFilter.reset();

        if (capturedImage != null && !capturedImage.isRecycled()) {
            capturedImage.recycle();
            capturedImage = null;
        }
        
        if (correctedImage != null && !correctedImage.isRecycled()) {
            correctedImage.recycle();
            correctedImage = null;
        }
    }

    /**
     * 更新已选内容显示
     */
    private void updateSelectedContentDisplay() {
        if (view != null) {
            view.showSelectedContent(getSelectedRegions());
        }
    }

    /**
     * 获取引导文本
     */
    private String getGuidanceText() {
        switch (currentState) {
            case PREVIEW:
            case DETECTING:
                return "请将相机对准面单标签";
            case CAPTURED:
            case EXTRACTING:
                return "正在识别内容...";
            case SELECTING:
                if (contentRegions.isEmpty()) {
                    return "未检测到内容";
                } else {
                    return "点击选择需要提取的内容";
                }
            default:
                return "";
        }
    }

    /**
     * 提取YUV数据
     */
    private byte[] extractYuvData(ImageProxy image) {
        try {
            // 简化实现，实际需要完整的YUV提取
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将ImageProxy转换为Bitmap
     * 使用 CameraX 内置的 toBitmap() 方法
     */
    private Bitmap imageProxyToBitmap(ImageProxy image, int rotationDegrees) {
        try {
            // 使用 CameraX 1.3+ 提供的 toBitmap() 方法
            Bitmap bitmap = image.toBitmap();
            
            if (bitmap == null) {
                Log.e(TAG, "toBitmap() returned null");
                return null;
            }

            // 应用旋转
            if (rotationDegrees != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotationDegrees);
                Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle();
                }
                return rotatedBitmap;
            }

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap", e);
            return null;
        }
    }

    /**
     * 发送到主线程
     */
    private void postToMain(Runnable runnable) {
        if (view != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
        }
    }

    /**
     * 置信度过滤器
     * 基于置信度阈值进行简单过滤
     */
    private class ConfidenceFilter {
        private static final long MIN_UPDATE_INTERVAL = 100; // 最小更新间隔（毫秒）
        
        private long lastUpdateTime = 0;
        private double lastConfidence = 0.0;
        
        /**
         * 过滤检测结果
         */
        public DetectionResult filter(DetectionResult newResult) {
            if (newResult == null || !newResult.isDetected()) {
                return null;
            }
            
            long currentTime = System.currentTimeMillis();
            double confidence = newResult.getConfidence();
            
            // 获取置信度阈值配置
            double confidenceThreshold = getConfidenceThreshold();
            
            // 时间间隔检查
            long timeSinceLastUpdate = currentTime - lastUpdateTime;
            if (timeSinceLastUpdate < MIN_UPDATE_INTERVAL) {
                Log.d(TAG, String.format("Confidence Filter: Update too frequent, skipping (interval=%dms)", 
                        timeSinceLastUpdate));
                return null;
            }
            
            // 置信度检查
            if (confidence < confidenceThreshold) {
                Log.d(TAG, String.format("Confidence Filter: Low confidence (%.3f < %.3f), skipping", 
                        confidence, confidenceThreshold));
                return null;
            }
            
            // 通过过滤
            lastUpdateTime = currentTime;
            lastConfidence = confidence;
            
            Log.d(TAG, String.format("Confidence Filter: Accepted (%.3f >= %.3f)", 
                    confidence, confidenceThreshold));
            
            return newResult;
        }
        
        /**
         * 重置过滤器状态
         */
        public void reset() {
            lastUpdateTime = 0;
            lastConfidence = 0.0;
            Log.d(TAG, "Confidence Filter: Reset");
        }
        
        /**
         * 获取置信度阈值配置
         */
        private double getConfidenceThreshold() {
            // 从相机设置中获取置信度阈值
            CameraSettings settings = cameraController.getCurrentSettings();
            return settings.getConfidenceThreshold();
        }
    }

    /**
     * 为检测准备优化的图像（降采样+增强）- 性能优化版本
     */
    private Bitmap prepareDetectionBitmap(Bitmap originalBitmap, CameraSettings.EnhanceConfig enhanceConfig) {
        Bitmap workingBitmap = originalBitmap;
        
        // 检测阶段的智能降采样：4K降到1080P，保持宽高比
        int originalWidth = originalBitmap.getWidth();
        int originalHeight = originalBitmap.getHeight();
        int pixels = originalWidth * originalHeight;
        
        // 4K及以上分辨率进行降采样
        if (pixels > 1920 * 1080) {
            // 使用更快的降采样算法：直接计算目标尺寸
            double targetPixels = 1920.0 * 1080.0;
            double scale = Math.sqrt(targetPixels / pixels);
            
            int newWidth = (int) (originalWidth * scale);
            int newHeight = (int) (originalHeight * scale);
            
            // 确保尺寸是偶数（某些算法要求）
            newWidth = (newWidth / 2) * 2;
            newHeight = (newHeight / 2) * 2;
            
            Log.d(TAG, "Fast downsampling: " + originalWidth + "x" + originalHeight + 
                  " -> " + newWidth + "x" + newHeight + " (scale=" + scale + ")");
            
            // 使用FILTER_LINEAR获得更好的性能
            workingBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, false);
        }
        
        // 跳过增强处理以提高性能，直接使用降采样后的图像
        // 在检测阶段，速度比质量更重要
        Log.d(TAG, "Skipping enhancement for better performance");
        
        return workingBitmap;
    }

    /**
     * 将检测结果坐标映射回原始图像尺寸
     */
    private DetectionResult mapDetectionResult(DetectionResult result, Bitmap originalBitmap, Bitmap detectionBitmap) {
        if (result == null || !result.isDetected()) {
            return result;
        }
        
        float scaleX = (float) originalBitmap.getWidth() / detectionBitmap.getWidth();
        float scaleY = (float) originalBitmap.getHeight() / detectionBitmap.getHeight();
        
        Log.d(TAG, "Mapping detection result: scaleX=" + scaleX + ", scaleY=" + scaleY);
        
        // 映射边界框
        android.graphics.RectF originalBox = result.getBoundingBox();
        android.graphics.RectF mappedBox = null;
        if (originalBox != null) {
            mappedBox = new android.graphics.RectF(
                originalBox.left * scaleX,
                originalBox.top * scaleY,
                originalBox.right * scaleX,
                originalBox.bottom * scaleY
            );
        }
        
        // 映射角点
        PointF[] originalCorners = result.getCornerPoints();
        PointF[] mappedCorners = null;
        if (originalCorners != null) {
            mappedCorners = new PointF[originalCorners.length];
            for (int i = 0; i < originalCorners.length; i++) {
                mappedCorners[i] = new PointF(
                    originalCorners[i].x * scaleX,
                    originalCorners[i].y * scaleY
                );
            }
        }
        
        // 映射原始角点
        PointF[] originalOriginalCorners = result.getOriginalCornerPoints();
        PointF[] mappedOriginalCorners = null;
        if (originalOriginalCorners != null) {
            mappedOriginalCorners = new PointF[originalOriginalCorners.length];
            for (int i = 0; i < originalOriginalCorners.length; i++) {
                mappedOriginalCorners[i] = new PointF(
                    originalOriginalCorners[i].x * scaleX,
                    originalOriginalCorners[i].y * scaleY
                );
            }
        }
        
        // 创建新的DetectionResult对象
        return new DetectionResult.Builder()
                .setDetected(true)
                .setCornerPoints(mappedCorners)
                .setOriginalCornerPoints(mappedOriginalCorners)
                .setBoundingBox(mappedBox)
                .setConfidence(result.getConfidence())
                .setRotationAngle(result.getRotationAngle())
                .setSegmentationMask(result.getSegmentationMask())
                .build();
    }

    /**
     * 检查帧是否应该处理（基于清晰度阈值）
     * 用于属性测试
     */
    public boolean shouldProcessFrame(double sharpness, double threshold) {
        return sharpness >= threshold;
    }

    /**
     * 获取CameraController（供Activity使用）
     */
    public CameraController getCameraController() {
        return cameraController;
    }
}
