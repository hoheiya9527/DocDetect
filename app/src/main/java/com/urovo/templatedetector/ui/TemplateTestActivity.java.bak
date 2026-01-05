package com.urovo.templatedetector.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.urovo.templatedetector.R;
import com.urovo.templatedetector.camera.CameraController;
import com.urovo.templatedetector.detector.LabelDetector;
import com.urovo.templatedetector.init.AppInitializer;
import com.urovo.templatedetector.matcher.RegionContentRecognizer;
import com.urovo.templatedetector.matcher.TemplateMatchingService;
import com.urovo.templatedetector.matcher.TemplateRepository;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.model.DetectionResult;
import com.urovo.templatedetector.model.MatchResult;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.util.CameraConfigManager;
import com.urovo.templatedetector.util.ImageEnhancer;
import com.urovo.templatedetector.view.CameraPreviewLayout;
import com.urovo.templatedetector.view.CameraSettingsDialog;
import com.urovo.templatedetector.view.OverlayView;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模板测试界面
 * 支持两种匹配模式：标签检测模式、粗定位模式
 * 统一输入输出流程，差异仅在校正策略
 */
public class TemplateTestActivity extends AppCompatActivity {

    private static final String TAG = "TemplateTestActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    public static final String EXTRA_TEMPLATE_ID = "template_id";

    // 匹配配置
    private static final int MATCH_MAX_DIMENSION = 1024;
    private static final int STABLE_FRAME_THRESHOLD = 2;
    private static final int LOST_FRAME_THRESHOLD = 3;  // 连续多少帧检测失败才隐藏检测框
    private static final long MATCH_COOLDOWN_MS = 100;

    // ==================== UI 组件 ====================
    private CameraPreviewLayout cameraPreviewLayout;
    private ImageView imageViewStatus;
    private TextView textViewTemplateName;
    private Chip chipConfidence;
    private RecyclerView recyclerViewRegions;

    // ==================== 核心服务 ====================
    private CameraController cameraController;
    private LabelDetector labelDetector;
    private TemplateMatchingService matchingService;
    private TemplateRepository repository;
    private RegionContentRecognizer contentRecognizer;

    // ==================== 状态管理 ====================
    private long templateId = -1;
    private boolean isMultiTemplateMode = false;

    private volatile boolean isProcessingFrame = false;
    private volatile boolean isMatching = false;
    private int stableFrameCounter = 0;
    private int lostFrameCounter = 0;  // 检测丢失帧计数
    private double autoMatchThreshold = 0.95;

    // ==================== 帧数据缓冲 ====================
    private byte[] rgbaBuffer;
    private int rgbaBufferSize;

    // ==================== 匹配结果 ====================
    private MatchResult currentMatchResult;
    private Bitmap matchedBitmap;
    private Mat inverseMatrix;
    private int lastRotatedWidth, lastRotatedHeight;
    private DetectionResult lastDetectionResult;
    private RegionContentAdapter regionAdapter;

    // ==================== 匹配模式 ====================
    private enum MatchMode {
        COARSE,           // 粗定位模式：特征匹配 + Homography
        LABEL_DETECTION   // 标签检测模式：语义分割 + 透视校正
    }

    private volatile MatchMode currentMatchMode = MatchMode.COARSE;

    /**
     * 统一的校正结果
     */
    private static class CorrectionData {
        Mat correctedMat;
        Mat inverseMatrix;
        PointF[] templateCornersInImage;  // 模板在原图中的四角坐标（用于显示检测框）

        CorrectionData(Mat correctedMat, Mat inverseMatrix, PointF[] templateCornersInImage) {
            this.correctedMat = correctedMat;
            this.inverseMatrix = inverseMatrix;
            this.templateCornersInImage = templateCornersInImage;
        }

        void release() {
            if (correctedMat != null) correctedMat.release();
            if (inverseMatrix != null) inverseMatrix.release();
        }
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_template_test);

        templateId = getIntent().getLongExtra(EXTRA_TEMPLATE_ID, -1);

        if (!initServices()) return;
        initViews();
        setupWindowInsets();
        loadTemplate();

        if (checkCameraPermission()) {
            initCamera();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraController != null) cameraController.startPreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraController != null) cameraController.stopPreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraController != null) cameraController.release();
        releaseMatchResult();
    }

    // ==================== 初始化 ====================

    private boolean initServices() {
        AppInitializer initializer = AppInitializer.getInstance(this);
        if (!initializer.isInitialized()) {
            Toast.makeText(this, R.string.initializing, Toast.LENGTH_LONG).show();
            finish();
            return false;
        }

        labelDetector = initializer.getLabelDetector();
        matchingService = initializer.getTemplateMatchingService();
        repository = TemplateRepository.getInstance(this);
        contentRecognizer = new RegionContentRecognizer(initializer.getOcrEngine(), initializer.getBarcodeDecoder());
        return true;
    }

    private void initViews() {
        cameraPreviewLayout = findViewById(R.id.cameraPreviewLayout);
        imageViewStatus = findViewById(R.id.imageViewStatus);
        textViewTemplateName = findViewById(R.id.textViewTemplateName);
        chipConfidence = findViewById(R.id.chipConfidence);
        recyclerViewRegions = findViewById(R.id.recyclerViewRegions);
        ChipGroup chipGroupMatchMode = findViewById(R.id.chipGroupMatchMode);
        
        // 恢复保存的匹配模式
        String savedMode = CameraConfigManager.getInstance(this).loadMatchMode(MatchMode.COARSE.name());
        currentMatchMode = MatchMode.valueOf(savedMode);
        
        // 同步 UI 选中状态
        if (currentMatchMode == MatchMode.LABEL_DETECTION) {
            chipGroupMatchMode.check(R.id.chipModeLabelDetection);
        } else {
            chipGroupMatchMode.check(R.id.chipModeCoarse);
        }
        
        // 模式切换监听
        chipGroupMatchMode.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chipModeCoarse)) {
                switchMatchMode(MatchMode.COARSE);
            } else if (checkedIds.contains(R.id.chipModeLabelDetection)) {
                switchMatchMode(MatchMode.LABEL_DETECTION);
            }
        });

        regionAdapter = new RegionContentAdapter();
        recyclerViewRegions.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewRegions.setAdapter(regionAdapter);

        cameraPreviewLayout.setConfidenceIndicatorVisible(true);
        cameraPreviewLayout.setOnSettingsClickListener(this::showSettingsDialog);

        showNoMatchState();
    }

    private void setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void loadTemplate() {
        isMultiTemplateMode = (templateId <= 0);

        if (isMultiTemplateMode) {
            textViewTemplateName.setText(R.string.content_recognition);
        } else {
            Template template = repository.getTemplateWithRegions(templateId);
            if (template == null) {
                Toast.makeText(this, R.string.template_not_found, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            textViewTemplateName.setText(template.getName());
        }
    }

    private void initCamera() {
        cameraController = new CameraController(this);
        cameraController.setFrameCallback(this::processFrame);
        cameraPreviewLayout.setCameraController(cameraController, this);

        CameraSettings settings = cameraController.getCurrentSettings();
        if (settings != null) {
            autoMatchThreshold = settings.getConfidenceThreshold();
            contentRecognizer.setEnableEnhance(settings.getEnhanceConfig().isEnableEnhance());
        }
    }

    // ==================== 模式切换 ====================

    private void switchMatchMode(MatchMode mode) {
        if (currentMatchMode == mode) return;

        Log.d(TAG, ">> Switch match mode: " + currentMatchMode + " -> " + mode);
        currentMatchMode = mode;
        
        // 持久化保存
        CameraConfigManager.getInstance(this).saveMatchMode(mode.name());

        // 重置状态
        stableFrameCounter = 0;
        lostFrameCounter = 0;
        isMatching = false;
        lastDetectionResult = null;

        releaseMatchResult();
        runOnUiThread(() -> {
            cameraPreviewLayout.hideDetectionBox();
            cameraPreviewLayout.clearContentRegions();
            showNoMatchState();
        });
    }

    // ==================== 统一帧处理入口 ====================

    private void processFrame(androidx.camera.core.ImageProxy image, int rotationDegrees) {
        if (isProcessingFrame || isMatching) {
            image.close();
            return;
        }

        isProcessingFrame = true;

        try (image) {
            int width = image.getWidth();
            int height = image.getHeight();
            int rotatedWidth = (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;
            int rotatedHeight = (rotationDegrees == 90 || rotationDegrees == 270) ? width : height;

            // 提取 RGBA 数据到复用缓冲区
            if (!extractRgbaToBuffer(image)) {
                isProcessingFrame = false;
                return;
            }

            // 根据模式判断触发条件
            boolean shouldTriggerMatch = evaluateTriggerCondition(image, rotationDegrees, rotatedWidth, rotatedHeight);
            if (!shouldTriggerMatch) {
                lostFrameCounter++;
                // 连续多帧检测失败才隐藏检测框和ROI，避免闪烁
                if (lostFrameCounter >= LOST_FRAME_THRESHOLD) {
                    lastDetectionResult = null;
                    runOnUiThread(this::showNoMatchState);
                }
            } else {
                lostFrameCounter = 0;  // 重置丢失计数
            }
            if (shouldTriggerMatch && !isMatching) {
                // 复制帧数据给异步线程
                byte[] frameData = new byte[width * height * 4];
                System.arraycopy(rgbaBuffer, 0, frameData, 0, frameData.length);
                triggerMatch(frameData, width, height, rotationDegrees);
            }

        } catch (Exception e) {
            Log.e(TAG, ">> Frame processing failed", e);
        } finally {
            isProcessingFrame = false;
        }
    }

    /**
     * 评估是否满足匹配触发条件
     * 统一入口，根据模式执行不同的预检测逻辑
     */
    private boolean evaluateTriggerCondition(androidx.camera.core.ImageProxy image, int rotationDegrees, int rotatedWidth, int rotatedHeight) {

        if (currentMatchMode == MatchMode.LABEL_DETECTION) {
            // 标签检测模式：需要检测到标签且置信度足够
            DetectionResult result = labelDetector.detect(image, rotationDegrees);

            if (result != null && result.isDetected() && result.getConfidence() > 0.5) {
                lastDetectionResult = result;
                double confidence = result.getConfidence();

                // 更新 UI：显示检测框和置信度
                final int modelW = result.getModelWidth();
                final int modelH = result.getModelHeight();
                final RectF box = result.getBoundingBox();
                final PointF[] corners = result.getCornerPoints();
                runOnUiThread(() -> {
                    cameraPreviewLayout.setFillCenterCoordinates(modelW, modelH, rotatedWidth, rotatedHeight);
                    cameraPreviewLayout.showDetectionBox(box, corners);
                    cameraPreviewLayout.updateConfidence(confidence);
                });

                // 置信度足够时累计稳定帧
                if (confidence >= autoMatchThreshold) {
                    stableFrameCounter++;
                    return stableFrameCounter >= STABLE_FRAME_THRESHOLD;
                }
            }
            // 未检测到，累计丢失帧
            stableFrameCounter = 0;
            return false;

        } else {
            // 全图匹配模式：按帧间隔触发，保持已有的检测框显示
            stableFrameCounter++;
            return stableFrameCounter >= STABLE_FRAME_THRESHOLD;
        }
    }

    // ==================== 统一匹配流程 ====================

    /**
     * 统一的匹配触发入口
     * 差异仅在 performCorrection 内部
     */
    private void triggerMatch(byte[] rgbaData, int width, int height, int rotationDegrees) {
        if (isMatching) return;
        isMatching = true;
        stableFrameCounter = 0;

//        final boolean isFirstMatch = (currentMatchResult == null);
//        if (isFirstMatch) {
//            runOnUiThread(this::showNoMatchState);
//        }

        final int rotatedW = (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;
        final int rotatedH = (rotationDegrees == 90 || rotationDegrees == 270) ? width : height;

        new Thread(() -> {
            Mat colorMat = null;
            Mat rotatedMat = null;
            CorrectionData correction = null;
            Bitmap newBitmap = null;
            MatchResult newResult = null;

            try {
                // 1. 统一的图像预处理
                colorMat = ImageEnhancer.rgbaToColorMat(rgbaData, width, height);
                if (colorMat == null) {
                    handleMatchFailure();
                    return;
                }

                rotatedMat = ImageEnhancer.rotateMat(colorMat, rotationDegrees);

                // 2. 根据模式执行校正（差异点）
                correction = performCorrection(rotatedMat);
                if (correction == null || correction.correctedMat == null) {
                    handleMatchFailure();
                    return;
                }

                // 3. 统一的模板匹配
                newResult = isMultiTemplateMode ? matchingService.matchAllFromMat(correction.correctedMat) : matchingService.matchTemplateFromMat(correction.correctedMat, templateId);

                if (!newResult.isSuccess()) {
                    Log.d(TAG, ">> Template match failed: " + newResult.getErrorMessage());
                    handleMatchFailure();
                    return;
                }

                Log.d(TAG, ">> Template match success: confidence=" + String.format(Locale.US, "%.2f", newResult.getConfidence()));

                // 4. 统一的内容识别
                if (newResult.getTransformedRegions() != null && !newResult.getTransformedRegions().isEmpty()) {
                    newBitmap = ImageEnhancer.matToBitmap(correction.correctedMat);
                    if (newBitmap != null) {
                        contentRecognizer.recognizeAll(newBitmap, newResult.getTransformedRegions());
                    }
                }

                // 5. 统一的结果更新
                final MatchResult resultToShow = newResult;
                final Bitmap bitmapToKeep = newBitmap;
                final Mat inverseToKeep = correction.inverseMatrix;
                final PointF[] templateCorners = correction.templateCornersInImage;
                correction.inverseMatrix = null; // 防止 finally 释放

                runOnUiThread(() -> {
                    releaseMatchResult();
                    currentMatchResult = resultToShow;
                    matchedBitmap = bitmapToKeep;
                    inverseMatrix = inverseToKeep;
                    lastRotatedWidth = rotatedW;
                    lastRotatedHeight = rotatedH;

                    // COARSE 模式：设置坐标系统并显示检测框
                    // LABEL_DETECTION 模式：保持 evaluateTriggerCondition 设置的坐标系统，避免检测框闪烁
                    if (currentMatchMode == MatchMode.COARSE) {
                        cameraPreviewLayout.setFillCenterCoordinates(rotatedW, rotatedH, rotatedW, rotatedH);
                        if (templateCorners != null) {
                            RectF bounds = calculateBoundsFromCorners(templateCorners);
                            cameraPreviewLayout.showDetectionBox(bounds, templateCorners);
                            cameraPreviewLayout.updateConfidence(resultToShow.getConfidence());
                        }
                    }
                    showMatchResult();
                });

                newBitmap = null;
                newResult = null;

                // 冷却时间
                recyclerViewRegions.postDelayed(() -> isMatching = false, MATCH_COOLDOWN_MS);

            } catch (Exception e) {
                Log.e(TAG, ">> Match failed", e);
                handleMatchFailure();
            } finally {
                if (colorMat != null) colorMat.release();
                if (rotatedMat != null && rotatedMat != colorMat) rotatedMat.release();
                if (correction != null) correction.release();
                if (newBitmap != null && !newBitmap.isRecycled()) newBitmap.recycle();
                if (newResult != null) newResult.release();
            }
        }).start();
    }

    private void handleMatchFailure() {
        runOnUiThread(this::showNoMatchState);
        isMatching = false;
    }

    // ==================== 校正策略（差异点） ====================

    /**
     * 根据模式执行图像校正
     * 这是两种模式的唯一差异点
     */
    private CorrectionData performCorrection(Mat rotatedMat) {
        if (currentMatchMode == MatchMode.LABEL_DETECTION) {
            return performLabelDetectionCorrection(rotatedMat);
        } else {
            return performHomographyCorrection(rotatedMat);
        }
    }

    /**
     * 标签检测模式：直接使用已有的检测结果进行透视校正
     * 检测已在 evaluateTriggerCondition 中完成，这里只做校正
     */
    private CorrectionData performLabelDetectionCorrection(Mat rotatedMat) {
        if (lastDetectionResult == null || !lastDetectionResult.isDetected()) {
            return null;
        }

        try {
            // 直接使用已有的检测结果进行透视校正，不再重复检测
            LabelDetector.CorrectionResult result = labelDetector.extractAndCorrectMatWithTransform(rotatedMat, lastDetectionResult);

            if (result == null || result.correctedMat == null) return null;

            Mat inverse = result.perspectiveMatrix.inv();
            Mat corrected = result.correctedMat.clone();

            result.correctedMat.release();
            result.perspectiveMatrix.release();

            // 标签检测模式的检测框由 lastDetectionResult 提供，这里不需要额外计算
            return new CorrectionData(corrected, inverse, null);

        } catch (Exception e) {
            Log.e(TAG, ">> Label detection correction failed", e);
            return null;
        }
    }

    /**
     * 粗定位模式：使用特征匹配 + Homography 校正
     * 同时计算模板在原图中的四角坐标用于显示检测框
     */
    private CorrectionData performHomographyCorrection(Mat rotatedMat) {
        // 缩放以提高性能
        int maxDim = Math.max(rotatedMat.cols(), rotatedMat.rows());
        float scale = maxDim > MATCH_MAX_DIMENSION ? (float) MATCH_MAX_DIMENSION / maxDim : 1f;

        Mat scaledMat = rotatedMat;
        if (scale < 1f) {
            scaledMat = new Mat();
            Imgproc.resize(rotatedMat, scaledMat, new Size(rotatedMat.cols() * scale, rotatedMat.rows() * scale));
        }

        try {
            // 特征匹配获取 Homography
            MatchResult matchResult = isMultiTemplateMode ? matchingService.matchAllFromMat(scaledMat) : matchingService.matchTemplateFromMat(scaledMat, templateId);

            if (!matchResult.isSuccess()) {
                if (scaledMat != rotatedMat) scaledMat.release();
                matchResult.release();
                return null;
            }

            Mat homography = matchResult.getHomography();
            Template matchedTemplate = matchResult.getTemplate();

            if (homography == null || homography.empty() || matchedTemplate == null) {
                if (scaledMat != rotatedMat) scaledMat.release();
                matchResult.release();
                return null;
            }

            int dstWidth = matchedTemplate.getImageWidth();
            int dstHeight = matchedTemplate.getImageHeight();

            // 调整 Homography 以适应原始分辨率
            Mat adjustedHomography = homography;
            if (scale < 1f) {
                Mat scaleMatrix = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F);
                scaleMatrix.put(0, 0, scale);
                scaleMatrix.put(1, 1, scale);
                adjustedHomography = new Mat();
                Core.gemm(homography, scaleMatrix, 1, new Mat(), 0, adjustedHomography);
                scaleMatrix.release();
            }

            // 计算模板四角在原图中的位置（用于显示检测框）
            PointF[] templateCornersInImage = calculateTemplateCornersInImage(adjustedHomography, dstWidth, dstHeight);

            // 透视校正
            Mat correctedMat = new Mat();
            Imgproc.warpPerspective(rotatedMat, correctedMat, adjustedHomography, new Size(dstWidth, dstHeight));

            // 计算逆矩阵
            Mat inverse = adjustedHomography.inv();

            if (scale < 1f) {
                adjustedHomography.release();
                scaledMat.release();
            }
            matchResult.release();

            return new CorrectionData(correctedMat, inverse, templateCornersInImage);

        } catch (Exception e) {
            Log.e(TAG, ">> Homography correction failed", e);
            if (scaledMat != rotatedMat) scaledMat.release();
            return null;
        }
    }

    /**
     * 通过 Homography 逆矩阵计算模板四角在原图中的位置
     * Homography 将原图坐标映射到模板坐标，所以需要用逆矩阵将模板四角映射回原图
     */
    private PointF[] calculateTemplateCornersInImage(Mat homography, int templateWidth, int templateHeight) {
        // 模板四角点（顺时针：左上、右上、右下、左下）
        PointF[] templateCorners = new PointF[]{new PointF(0, 0), new PointF(templateWidth, 0), new PointF(templateWidth, templateHeight), new PointF(0, templateHeight)};

        // Homography 的逆矩阵将模板坐标映射到原图坐标
        Mat inverseH = homography.inv();
        PointF[] result = transformPointsWithMatrix(templateCorners, inverseH);
        inverseH.release();

        return result;
    }

    // ==================== 统一输出 ====================

    private void showNoMatchState() {
        imageViewStatus.setImageResource(R.drawable.ic_warning);
        imageViewStatus.setColorFilter(getColor(R.color.confidence_low));
        chipConfidence.setText(R.string.template_no_match);
        chipConfidence.setChipBackgroundColorResource(R.color.confidence_low);
        chipConfidence.setVisibility(View.VISIBLE);
        recyclerViewRegions.setVisibility(View.GONE);
        cameraPreviewLayout.clearContentRegions();
        cameraPreviewLayout.updateConfidence(0);
    }

    private void showMatchResult() {
        recyclerViewRegions.setVisibility(View.VISIBLE);
        Log.d(TAG, ">> showMatchResult isSuccess：" + currentMatchResult.isSuccess());
        if (currentMatchResult.isSuccess()) {
            imageViewStatus.setImageResource(R.drawable.ic_check);
            imageViewStatus.setColorFilter(getColor(R.color.confidence_high));

            if (isMultiTemplateMode && currentMatchResult.getTemplate() != null) {
                textViewTemplateName.setText(currentMatchResult.getTemplate().getName());
            }

            float confidence = currentMatchResult.getConfidence();
            chipConfidence.setText(String.format(Locale.getDefault(), "%.1f%%", confidence * 100));
            chipConfidence.setChipBackgroundColorResource(confidence > 0.7f ? R.color.confidence_high : confidence > 0.5f ? R.color.confidence_medium : R.color.confidence_low);
            chipConfidence.setVisibility(View.VISIBLE);

            regionAdapter.setRegions(currentMatchResult.getTransformedRegions());
            updateContentRegionsOverlay(currentMatchResult.getTransformedRegions());
        } else {
            imageViewStatus.setImageResource(R.drawable.ic_warning);
            imageViewStatus.setColorFilter(getColor(R.color.confidence_low));
//            if (isMultiTemplateMode) {
//                textViewTemplateName.setText(R.string.content_recognition);
//            }
            chipConfidence.setText(R.string.template_no_match);
            chipConfidence.setChipBackgroundColorResource(R.color.confidence_low);
            chipConfidence.setVisibility(View.VISIBLE);
            regionAdapter.setRegions(null);
            cameraPreviewLayout.clearContentRegions();
        }
    }

    /**
     * 更新预览中的内容区域蓝框
     */
    private void updateContentRegionsOverlay(List<MatchResult.TransformedRegion> regions) {
        if (regions == null || regions.isEmpty() || inverseMatrix == null) {
            Log.d(TAG, ">> updateContentRegionsOverlay inverseMatrix = null");
            cameraPreviewLayout.clearContentRegions();
            return;
        }

        DetectionResult detection = lastDetectionResult;
        if (detection == null && currentMatchMode == MatchMode.LABEL_DETECTION) {
            Log.d(TAG, ">> updateContentRegionsOverlay detection = null");
            cameraPreviewLayout.clearContentRegions();
            return;
        }

        // 计算坐标缩放比例
        int modelWidth, modelHeight;
        if (detection != null) {
            modelWidth = detection.getModelWidth();
            modelHeight = detection.getModelHeight();
        } else {
            modelWidth = lastRotatedWidth;
            modelHeight = lastRotatedHeight;
        }

        float scaleX = (float) modelWidth / lastRotatedWidth;
        float scaleY = (float) modelHeight / lastRotatedHeight;

        List<OverlayView.ContentRegion> overlayRegions = new ArrayList<>();
        for (MatchResult.TransformedRegion region : regions) {
            PointF[] corners = region.getTransformedCorners();
            if (corners == null || corners.length != 4) continue;

            PointF[] imageCorners = transformPointsWithMatrix(corners, inverseMatrix);
            if (imageCorners == null) continue;

            PointF[] modelCorners = new PointF[4];
            for (int i = 0; i < 4; i++) {
                modelCorners[i] = new PointF(imageCorners[i].x * scaleX, imageCorners[i].y * scaleY);
            }

            RectF modelBounds = calculateBoundsFromCorners(modelCorners);

            overlayRegions.add(new OverlayView.ContentRegion(String.valueOf(region.getRegion().getId()), modelBounds, modelCorners, region.getRegion().getName(), region.hasContent()));
        }
        cameraPreviewLayout.setContentRegions(overlayRegions);
    }

    // ==================== 工具方法 ====================

    private boolean extractRgbaToBuffer(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy == null) return false;

        try {
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            int requiredSize = width * height * 4;

            if (rgbaBuffer == null || rgbaBufferSize < requiredSize) {
                rgbaBuffer = new byte[requiredSize];
                rgbaBufferSize = requiredSize;
                Log.d(TAG, ">> Allocated RGBA buffer: " + (requiredSize / 1024 / 1024) + "MB");
            }

            androidx.camera.core.ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes.length < 1) return false;

            java.nio.ByteBuffer srcBuffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();

            if (rowStride == width * pixelStride) {
                srcBuffer.rewind();
                srcBuffer.get(rgbaBuffer, 0, width * height * 4);
            } else {
                for (int row = 0; row < height; row++) {
                    srcBuffer.position(row * rowStride);
                    srcBuffer.get(rgbaBuffer, row * width * 4, width * 4);
                }
            }
            return true;

        } catch (Exception e) {
            Log.e(TAG, ">> extractRgbaToBuffer failed", e);
            return false;
        }
    }

    private PointF[] transformPointsWithMatrix(PointF[] points, Mat matrix) {
        if (points == null || matrix == null || matrix.empty()) return null;

        try {
            MatOfPoint2f srcPoints = new MatOfPoint2f();
            Point[] cvPoints = new Point[points.length];
            for (int i = 0; i < points.length; i++) {
                cvPoints[i] = new Point(points[i].x, points[i].y);
            }
            srcPoints.fromArray(cvPoints);

            MatOfPoint2f dstPoints = new MatOfPoint2f();
            Core.perspectiveTransform(srcPoints, dstPoints, matrix);

            Point[] transformedPoints = dstPoints.toArray();
            PointF[] result = new PointF[transformedPoints.length];
            for (int i = 0; i < transformedPoints.length; i++) {
                result[i] = new PointF((float) transformedPoints[i].x, (float) transformedPoints[i].y);
            }

            srcPoints.release();
            dstPoints.release();
            return result;

        } catch (Exception e) {
            Log.e(TAG, ">> transformPointsWithMatrix failed", e);
            return null;
        }
    }

    private RectF calculateBoundsFromCorners(PointF[] corners) {
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

        for (PointF corner : corners) {
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
        }
        return new RectF(minX, minY, maxX, maxY);
    }

    private void releaseMatchResult() {
        if (currentMatchResult != null) {
            currentMatchResult.release();
            currentMatchResult = null;
        }
        if (matchedBitmap != null && !matchedBitmap.isRecycled()) {
            matchedBitmap.recycle();
            matchedBitmap = null;
        }
        if (inverseMatrix != null) {
            inverseMatrix.release();
            inverseMatrix = null;
        }
    }

    private void showSettingsDialog(CameraSettings currentSettings) {
        CameraSettingsDialog dialog = CameraSettingsDialog.newInstance(currentSettings);
        dialog.setOnSettingsChangedListener(settings -> {
            cameraPreviewLayout.applySettings(settings);
            contentRecognizer.setEnableEnhance(settings.getEnhanceConfig().isEnableEnhance());
        });
        dialog.show(getSupportFragmentManager(), "camera_settings");
    }

    // ==================== 权限处理 ====================

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCamera();
            } else {
                Toast.makeText(this, R.string.error_camera_permission, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    // ==================== 内部适配器 ====================

    private class RegionContentAdapter extends RecyclerView.Adapter<RegionContentAdapter.ViewHolder> {
        private List<MatchResult.TransformedRegion> regions = new ArrayList<>();

        void setRegions(List<MatchResult.TransformedRegion> regions) {
            this.regions = regions != null ? regions : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_region_content, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(regions.get(position));
        }

        @Override
        public int getItemCount() {
            return regions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textViewRegionName, textViewRegionType, textViewContent;
            ImageView imageViewStatus;

            ViewHolder(View itemView) {
                super(itemView);
                textViewRegionName = itemView.findViewById(R.id.textViewRegionName);
                textViewRegionType = itemView.findViewById(R.id.textViewRegionType);
                textViewContent = itemView.findViewById(R.id.textViewContent);
                imageViewStatus = itemView.findViewById(R.id.imageViewStatus);
            }

            void bind(MatchResult.TransformedRegion region) {
                textViewRegionName.setText(region.getRegion().getName());
                textViewRegionType.setVisibility(View.GONE);

                if (region.hasContent()) {
                    textViewContent.setText(region.getContent());
                    textViewContent.setTextColor(getColor(R.color.content_recognized));
                    imageViewStatus.setImageResource(R.drawable.ic_check);
                    imageViewStatus.setColorFilter(getColor(R.color.confidence_high));
                } else {
                    textViewContent.setText(R.string.content_not_recognized);
                    textViewContent.setTextColor(getColor(R.color.content_not_recognized));
                    imageViewStatus.setImageResource(R.drawable.ic_warning);
                    imageViewStatus.setColorFilter(getColor(R.color.confidence_low));
                }
            }
        }
    }
}
