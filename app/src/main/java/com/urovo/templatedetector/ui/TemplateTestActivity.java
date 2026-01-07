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
import com.urovo.templatedetector.model.TemplateRegion;
import com.urovo.templatedetector.util.CameraConfigManager;
import com.urovo.templatedetector.util.ImageEnhancer;
import com.urovo.templatedetector.util.MLog;
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
    private static final int MATCH_MAX_DIMENSION = 1920;
    private static final int STABLE_FRAME_THRESHOLD = 1;    // 增加到3帧，减少匹配频率
    private static final int LOST_FRAME_THRESHOLD = 10;      // 增加到5帧，减少检测框闪烁
    private static final long MATCH_COOLDOWN_MS = 0;      // 增加到300ms，给特征缓存更多时间

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
        MatchResult cachedMatchResult;    // 缓存的匹配结果，避免重复匹配

        CorrectionData(Mat correctedMat, Mat inverseMatrix, PointF[] templateCornersInImage) {
            this.correctedMat = correctedMat;
            this.inverseMatrix = inverseMatrix;
            this.templateCornersInImage = templateCornersInImage;
            this.cachedMatchResult = null;
        }

        CorrectionData(Mat correctedMat, Mat inverseMatrix, PointF[] templateCornersInImage, MatchResult cachedMatchResult) {
            this.correctedMat = correctedMat;
            this.inverseMatrix = inverseMatrix;
            this.templateCornersInImage = templateCornersInImage;
            this.cachedMatchResult = cachedMatchResult;
        }

        void release() {
            if (correctedMat != null) correctedMat.release();
            if (inverseMatrix != null) inverseMatrix.release();
            if (cachedMatchResult != null) cachedMatchResult.release();
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

        currentMatchMode = mode;

        // 持久化保存
        CameraConfigManager.getInstance(this).saveMatchMode(mode.name());

        // 关键修正：完全重置状态，避免模式间的状态污染
        stableFrameCounter = 0;
        lostFrameCounter = 0;
        isMatching = false;
        lastDetectionResult = null;

        // 完全清理之前的匹配结果和相关状态
        releaseMatchResult();

        runOnUiThread(() -> {
            cameraPreviewLayout.hideDetectionBox();
            cameraPreviewLayout.clearContentRegions();

            // 关键修正：根据模式正确设置坐标系
            if (lastRotatedWidth > 0 && lastRotatedHeight > 0) {
                if (mode == MatchMode.COARSE) {
                    // 全图模式：使用原图坐标系
                    cameraPreviewLayout.setFillCenterCoordinates(lastRotatedWidth, lastRotatedHeight, lastRotatedWidth, lastRotatedHeight);
                } else {
                    // 标签检测模式：坐标系会在检测时动态设置，这里先清理
                }
            }

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

        final int rotatedW = (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;
        final int rotatedH = (rotationDegrees == 90 || rotationDegrees == 270) ? width : height;

        MLog.d(TAG, String.format(">> 开始匹配: 模式=%s, 尺寸=%dx%d, 旋转=%d°",
                currentMatchMode.name(), rotatedW, rotatedH, rotationDegrees));

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            Mat colorMat = null;
            Mat rotatedMat = null;
            CorrectionData correction = null;
            Bitmap newBitmap = null;
            MatchResult newResult = null;

            try {
                // 1. 统一的图像预处理
                long preprocessStart = System.currentTimeMillis();
                colorMat = ImageEnhancer.rgbaToColorMat(rgbaData, width, height);
                if (colorMat == null) {
                    MLog.w(TAG, ">> 图像预处理失败: rgbaToColorMat返回null");
                    handleMatchFailure();
                    return;
                }

                rotatedMat = ImageEnhancer.rotateMat(colorMat, rotationDegrees);
                long preprocessTime = System.currentTimeMillis() - preprocessStart;
                MLog.d(TAG, String.format(">> 图像预处理耗时: %dms", preprocessTime));

                // 2. 根据模式执行校正（差异点）
                long correctionStart = System.currentTimeMillis();
                correction = performCorrection(rotatedMat);
                long correctionTime = System.currentTimeMillis() - correctionStart;

                if (correction == null || correction.correctedMat == null) {
                    MLog.w(TAG, String.format(">> 图像校正或匹配失败: 模式=%s, 耗时=%dms",
                            currentMatchMode.name(), correctionTime));
                    handleMatchFailure();
                    return;
                }
                MLog.d(TAG, String.format(">> 图像校正或匹配成功: 模式=%s, 耗时=%dms",
                        currentMatchMode.name(), correctionTime));

                // 3. 统一的模板匹配（优化：使用缓存结果避免重复匹配）
                long matchStart = System.currentTimeMillis();
                if (correction.cachedMatchResult != null) {
                    // 使用缓存的匹配结果，避免重复匹配
                    newResult = correction.cachedMatchResult;
                    correction.cachedMatchResult = null; // 防止重复释放
                    MLog.d(TAG, ">> 使用缓存匹配结果，跳过重复匹配");
                } else {
                    // 执行实际匹配（主要用于标签检测模式）
                    newResult = isMultiTemplateMode ?
                            matchingService.matchAllFromMat(correction.correctedMat) :
                            matchingService.matchTemplateFromMat(correction.correctedMat, templateId);
                }
                long matchTime = System.currentTimeMillis() - matchStart;

                if (!newResult.isSuccess()) {
                    MLog.w(TAG, String.format(">> 模板匹配失败: %s, 耗时=%dms",
                            newResult.getErrorMessage(), matchTime));
                    handleMatchFailure();
                    return;
                }

                MLog.d(TAG, String.format(">> 模板匹配成功: confidence=%.3f, 耗时=%dms",
                        newResult.getConfidence(), matchTime));

                // 关键修复：对于全图模式，重新计算正确的transformedRegions
                if (currentMatchMode == MatchMode.COARSE && correction.inverseMatrix != null) {
                    correctionStart = System.currentTimeMillis();
                    List<MatchResult.TransformedRegion> correctedRegions =
                            recalculateTransformedRegions(newResult.getTemplate(), correction.inverseMatrix, rotatedW, rotatedH);

                    if (!correctedRegions.isEmpty()) {
                        // 创建修正后的MatchResult
                        MatchResult correctedResult = new MatchResult.Builder()
                                .setSuccess(true)
                                .setTemplate(newResult.getTemplate())
                                .setConfidence(newResult.getConfidence())
                                .setInlierRatio(newResult.getInlierRatio())
                                .setMatchCount(newResult.getMatchCount())
                                .setAvgDistance(newResult.getAvgDistance())
                                .setHomography(newResult.getHomography() != null ? newResult.getHomography().clone() : null)
                                .setTransformedRegions(correctedRegions)
                                .setTemplateCornersInImage(newResult.getTemplateCornersInImage())
                                .setTemplateBoundsInImage(newResult.getTemplateBoundsInImage())
                                .setMatchTimeMs(newResult.getMatchTimeMs())
                                .build();

                        newResult.release();
                        newResult = correctedResult;

                        correctionTime = System.currentTimeMillis() - correctionStart;
                        MLog.d(TAG, String.format(">> ROI坐标修正完成: 区域数=%d, 耗时=%dms",
                                correctedRegions.size(), correctionTime));
                    }
                }

                // 4. 统一的内容识别
                long recognitionStart = System.currentTimeMillis();
                if (newResult.getTransformedRegions() != null && !newResult.getTransformedRegions().isEmpty()) {
                    newBitmap = ImageEnhancer.matToBitmap(correction.correctedMat);
                    if (newBitmap != null) {
                        contentRecognizer.recognizeAll(newBitmap, newResult.getTransformedRegions());
                    }
                }
                long recognitionTime = System.currentTimeMillis() - recognitionStart;
                MLog.d(TAG, String.format(">> 内容识别耗时: %dms", recognitionTime));

                // 5. 统一的结果更新
                final MatchResult resultToShow = newResult;
                final Bitmap bitmapToKeep = newBitmap;
                final Mat inverseToKeep = correction.inverseMatrix;
                final PointF[] templateCorners = correction.templateCornersInImage;
                correction.inverseMatrix = null; // 防止 finally 释放

                long totalTime = System.currentTimeMillis() - startTime;
                MLog.d(TAG, String.format(">> 匹配流程完成: 总耗时=%dms (预处理=%d, 校正=%d, 匹配=%d, 识别=%d)",
                        totalTime, preprocessTime, correctionTime, matchTime, recognitionTime));

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
                long totalTime = System.currentTimeMillis() - startTime;
                MLog.e(TAG, String.format(">> 匹配异常: 耗时=%dms", totalTime), e);
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

    /**
     * 重新计算正确的transformedRegions（修复全图模式下的坐标变换问题）
     */
    private List<MatchResult.TransformedRegion> recalculateTransformedRegions(Template template, Mat inverseMatrix, int imageWidth, int imageHeight) {
        List<MatchResult.TransformedRegion> correctedRegions = new ArrayList<>();

        if (template == null || template.getRegions() == null || inverseMatrix == null) {
            return correctedRegions;
        }

        try {
            for (TemplateRegion region : template.getRegions()) {
                // 使用正确的逆变换矩阵计算ROI坐标
                MatOfPoint2f srcCorners = new MatOfPoint2f(
                        new Point(region.getBoundLeft(), region.getBoundTop()),
                        new Point(region.getBoundRight(), region.getBoundTop()),
                        new Point(region.getBoundRight(), region.getBoundBottom()),
                        new Point(region.getBoundLeft(), region.getBoundBottom())
                );

                MatOfPoint2f dstCorners = new MatOfPoint2f();
                Core.perspectiveTransform(srcCorners, dstCorners, inverseMatrix);

                Point[] corners = dstCorners.toArray();
                if (corners.length == 4) {
                    PointF[] transformedCorners = new PointF[4];
                    for (int i = 0; i < 4; i++) {
                        transformedCorners[i] = new PointF((float) corners[i].x, (float) corners[i].y);
                    }

                    RectF bounds = calculateBoundsFromCorners(transformedCorners);

                    // 验证变换后的区域
                    if (isValidTransformedRegion(bounds, transformedCorners, imageWidth, imageHeight)) {
                        MatchResult.TransformedRegion transformedRegion =
                                new MatchResult.TransformedRegion(region, bounds, transformedCorners);
                        correctedRegions.add(transformedRegion);

                        MLog.d(TAG, String.format(">> ROI坐标修正: %s -> (%.1f,%.1f,%.1f,%.1f)",
                                region.getName(), bounds.left, bounds.top, bounds.right, bounds.bottom));
                    } else {
                        MLog.w(TAG, String.format(">> ROI区域变换失败: %s, 边界[%.1f,%.1f,%.1f,%.1f]",
                                region.getName(), bounds.left, bounds.top, bounds.right, bounds.bottom));
                    }
                }

                srcCorners.release();
                dstCorners.release();
            }
        } catch (Exception e) {
            MLog.e(TAG, ">> recalculateTransformedRegions异常", e);
        }

        return correctedRegions;
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
     * 标签检测模式：优化版本，使用正确的坐标变换
     * 直接使用已有的检测结果，计算正确的逆变换矩阵
     */
    private CorrectionData performLabelDetectionCorrection(Mat rotatedMat) {
        if (lastDetectionResult == null || !lastDetectionResult.isDetected()) {
            MLog.w(TAG, ">> >> 标签检测模式: 无有效检测结果");
            return null;
        }

        try {
            MLog.d(TAG, String.format(">> >> 标签检测模式: 使用已有检测结果, 置信度=%.3f",
                    lastDetectionResult.getConfidence()));

            // 获取检测结果的角点信息
            PointF[] corners = lastDetectionResult.getOriginalCornerPoints();
            if (corners == null || corners.length != 4) {
                MLog.w(TAG, ">> >> 标签检测模式: 检测结果缺少有效角点");
                return null;
            }

            // 计算透视变换矩阵（用于正确的坐标变换）
            // 从检测到的四边形到标准矩形的变换
            int modelWidth = lastDetectionResult.getModelWidth();
            int modelHeight = lastDetectionResult.getModelHeight();

            MatOfPoint2f srcPoints = new MatOfPoint2f(
                    new org.opencv.core.Point(corners[0].x, corners[0].y),  // topLeft
                    new org.opencv.core.Point(corners[1].x, corners[1].y),  // topRight
                    new org.opencv.core.Point(corners[2].x, corners[2].y),  // bottomRight
                    new org.opencv.core.Point(corners[3].x, corners[3].y)   // bottomLeft
            );

            MatOfPoint2f dstPoints = new MatOfPoint2f(
                    new org.opencv.core.Point(0, 0),
                    new org.opencv.core.Point(modelWidth, 0),
                    new org.opencv.core.Point(modelWidth, modelHeight),
                    new org.opencv.core.Point(0, modelHeight)
            );

            Mat perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
            Mat inverse = perspectiveMatrix.inv();

            srcPoints.release();
            dstPoints.release();
            perspectiveMatrix.release();

            // 克隆原图作为"校正"结果
            Mat originalClone = rotatedMat.clone();

            MLog.d(TAG, ">> >> 标签检测模式: 坐标变换矩阵计算完成");

            // 返回原图和正确的变换信息
            return new CorrectionData(originalClone, inverse, null);

        } catch (Exception e) {
            MLog.e(TAG, ">> >> 标签检测校正异常", e);
            return null;
        }
    }

    /**
     * 粗定位模式：使用特征匹配 + Homography 校正
     * 混合策略：缩放图匹配（性能）+ 原图坐标（精度）
     */
    private CorrectionData performHomographyCorrection(Mat rotatedMat) {
        // 恢复缩放以提高性能
        int maxDim = Math.max(rotatedMat.cols(), rotatedMat.rows());
        float scale = maxDim > MATCH_MAX_DIMENSION ? (float) MATCH_MAX_DIMENSION / maxDim : 1f;

        MLog.d(TAG, String.format(">> >> Homography校正: 原始尺寸=%dx%d, 缩放比例=%.3f",
                rotatedMat.cols(), rotatedMat.rows(), scale));

        Mat scaledMat = rotatedMat;
        if (scale < 1f) {
            scaledMat = new Mat();
            Imgproc.resize(rotatedMat, scaledMat, new Size(rotatedMat.cols() * scale, rotatedMat.rows() * scale));
            MLog.d(TAG, String.format(">> >> 图像缩放: %dx%d -> %dx%d",
                    rotatedMat.cols(), rotatedMat.rows(), scaledMat.cols(), scaledMat.rows()));
        }

        try {
            // 在缩放图上进行特征匹配（快速）
            long matchStart = System.currentTimeMillis();
            MatchResult matchResult = isMultiTemplateMode ?
                    matchingService.matchAllFromMat(scaledMat) :
                    matchingService.matchTemplateFromMat(scaledMat, templateId);
            long matchTime = System.currentTimeMillis() - matchStart;

            if (!matchResult.isSuccess()) {
                MLog.w(TAG, String.format(">> >> 特征匹配失败: %s, 耗时=%dms",
                        matchResult.getErrorMessage(), matchTime));
                if (scaledMat != rotatedMat) scaledMat.release();
                matchResult.release();
                return null;
            }

            Mat homography = matchResult.getHomography();
            Template matchedTemplate = matchResult.getTemplate();

            if (homography == null || homography.empty() || matchedTemplate == null) {
                MLog.w(TAG, ">> >> Homography或模板为空");
                if (scaledMat != rotatedMat) scaledMat.release();
                matchResult.release();
                return null;
            }

            int dstWidth = matchedTemplate.getImageWidth();
            int dstHeight = matchedTemplate.getImageHeight();
            MLog.d(TAG, String.format(">> >> 匹配成功: 模板=%s, 尺寸=%dx%d, 置信度=%.3f, 耗时=%dms",
                    matchedTemplate.getName(), dstWidth, dstHeight, matchResult.getConfidence(), matchTime));

            // 调整Homography到原图坐标系（修复：正确的坐标变换逻辑）
            Mat adjustedHomography = homography;
            if (scale < 1f) {
                // 原理：
                // homography: 缩放图坐标 → 模板坐标
                // 我们需要：原图坐标 → 模板坐标
                // 
                // 设 S = 缩放变换矩阵（原图 → 缩放图）
                // 则 adjustedHomography = homography * S
                // 因为：原图坐标 --S--> 缩放图坐标 --homography--> 模板坐标

                Mat scaleMatrix = Mat.eye(3, 3, org.opencv.core.CvType.CV_64F);
                scaleMatrix.put(0, 0, scale);  // 原图到缩放图的缩放因子
                scaleMatrix.put(1, 1, scale);

                adjustedHomography = new Mat();
                // 正确的运算：homography * scaleMatrix
                Core.gemm(homography, scaleMatrix, 1, new Mat(), 0, adjustedHomography);
                scaleMatrix.release();
                MLog.d(TAG, String.format(">> >> Homography已调整到原图坐标系 (缩放=%.3f)", scale));
            }

            // 计算模板四角在原图中的位置（用于显示检测框）
            PointF[] templateCornersInImage = calculateTemplateCornersInImage(adjustedHomography, dstWidth, dstHeight);

            // 几何合理性验证
            if (!isValidTemplateQuadrilateral(templateCornersInImage, matchedTemplate, rotatedMat.cols(), rotatedMat.rows())) {
                MLog.w(TAG, ">> >> Homography产生的四边形不合理，拒绝匹配结果");
                if (scaledMat != rotatedMat) scaledMat.release();
                if (scale < 1f) adjustedHomography.release();
                matchResult.release();
                return null;
            }

            // 计算逆矩阵（用于ROI坐标变换）
            Mat inverse = adjustedHomography.inv();

            MLog.d(TAG, ">> >> 几何验证通过，使用混合策略优化");

            // 关键修复：重新计算ROI坐标，确保使用原图坐标系
            Mat originalClone = rotatedMat.clone();

            // 清理资源
            if (scaledMat != rotatedMat) scaledMat.release();

            // 返回结果，不使用缓存的matchResult，让后续流程重新计算transformedRegions
            // 这样可以确保使用正确的adjustedHomography进行坐标变换
            return new CorrectionData(originalClone, inverse, templateCornersInImage);

        } catch (Exception e) {
            MLog.e(TAG, ">> >> Homography校正异常", e);
            if (scaledMat != rotatedMat) scaledMat.release();
            return null;
        }
    }

    /**
     * 验证变换后的区域是否合理
     */
    private boolean isValidTransformedRegion(RectF bounds, PointF[] corners, int imageWidth, int imageHeight) {
        if (bounds == null || corners == null || corners.length != 4) {
            return false;
        }

        // 基本尺寸检查
        float width = bounds.width();
        float height = bounds.height();

        if (width <= 10 || height <= 10) {
            return false;
        }

        // 检查是否过大
        float maxSize = Math.max(imageWidth, imageHeight) * 2.0f;
        if (width > maxSize || height > maxSize) {
            return false;
        }

        return true;
    }

    /**
     * 验证模板四边形的几何合理性
     * 基于模板原始尺寸进行约束检查，避免离谱的变形
     */
    private boolean isValidTemplateQuadrilateral(PointF[] corners, Template template, int imageWidth, int imageHeight) {
        if (corners == null || corners.length != 4 || template == null) {
            return false;
        }

        try {
//            // 1. 检查四边形是否在图像范围内（允许适当超出边界）
//            float margin = Math.min(imageWidth, imageHeight) * 0.1f; // 10%的边界容忍度
//            for (PointF corner : corners) {
//                if (corner.x < -margin || corner.x > imageWidth + margin ||
//                        corner.y < -margin || corner.y > imageHeight + margin) {
//                    MLog.d(TAG, ">> >> 四边形超出图像边界过多");
//                    return false;
//                }
//            }

            // 2. 检查四边形面积是否合理（相对于图像面积）
            double quadArea = calculateQuadrilateralArea(corners);
            double imageArea = imageWidth * imageHeight;
            double areaRatio = quadArea / imageArea;

            if (areaRatio < 0.01 || areaRatio > 0.95) { // 面积应在1%-80%之间
                MLog.d(TAG, String.format(">> >> 四边形面积比例不合理: %.3f", areaRatio));
                return false;
            }

            // 3. 检查长宽比是否合理（基于模板原始比例）
            double templateAspectRatio = (double) template.getImageWidth() / template.getImageHeight();
            double quadAspectRatio = calculateQuadrilateralAspectRatio(corners);
            double aspectRatioDeviation = Math.abs(quadAspectRatio - templateAspectRatio) / templateAspectRatio;

            if (aspectRatioDeviation > 0.5) { // 长宽比偏差不超过50%
                MLog.d(TAG, String.format(">> >> 长宽比偏差过大: 模板=%.2f, 检测=%.2f, 偏差=%.1f%%",
                        templateAspectRatio, quadAspectRatio, aspectRatioDeviation * 100));
                return false;
            }

            // 4. 检查四边形的凸性（避免自相交）
            if (!isConvexQuadrilateral(corners)) {
                MLog.d(TAG, ">> >> 四边形非凸或自相交");
                return false;
            }

            // 5. 检查角度合理性（避免过度扭曲）
            if (!hasReasonableAngles(corners)) {
                MLog.d(TAG, ">> >> 四边形角度过度扭曲");
                return false;
            }

            // 6. 检查边长比例（避免极端拉伸）
            if (!hasReasonableSideRatios(corners)) {
                MLog.d(TAG, ">> >> 四边形边长比例异常");
                return false;
            }

            return true;

        } catch (Exception e) {
            MLog.e(TAG, ">> 四边形验证异常", e);
            return false;
        }
    }

    /**
     * 计算四边形面积（使用鞋带公式）
     */
    private double calculateQuadrilateralArea(PointF[] corners) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += corners[i].x * corners[j].y;
            area -= corners[j].x * corners[i].y;
        }
        return Math.abs(area) / 2.0;
    }

    /**
     * 计算四边形的等效长宽比
     */
    private double calculateQuadrilateralAspectRatio(PointF[] corners) {
        // 计算四边形的最小外接矩形
        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

        for (PointF corner : corners) {
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
        }

        double width = maxX - minX;
        double height = maxY - minY;

        return height > 0 ? width / height : 1.0;
    }

    /**
     * 检查四边形是否为凸四边形（避免自相交）
     */
    private boolean isConvexQuadrilateral(PointF[] corners) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            PointF a = corners[i];
            PointF b = corners[(i + 1) % 4];
            PointF c = corners[(i + 2) % 4];

            double cross = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);

            if (Math.abs(cross) < 1e-6) continue; // 共线，跳过

            int currentSign = cross > 0 ? 1 : -1;
            if (sign == 0) {
                sign = currentSign;
            } else if (sign != currentSign) {
                return false; // 不是凸四边形
            }
        }
        return true;
    }

    /**
     * 检查四边形角度是否合理（避免过度扭曲）
     */
    private boolean hasReasonableAngles(PointF[] corners) {
        for (int i = 0; i < 4; i++) {
            PointF a = corners[(i + 3) % 4];
            PointF b = corners[i];
            PointF c = corners[(i + 1) % 4];

            double angle = calculateAngleDegrees(a, b, c);

            // 角度应在30°-150°之间，避免过于尖锐或平直
            if (angle < 30 || angle > 150) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查四边形边长比例是否合理（避免极端拉伸）
     */
    private boolean hasReasonableSideRatios(PointF[] corners) {
        double[] sideLengths = new double[4];
        for (int i = 0; i < 4; i++) {
            PointF a = corners[i];
            PointF b = corners[(i + 1) % 4];
            sideLengths[i] = Math.sqrt(Math.pow(b.x - a.x, 2) + Math.pow(b.y - a.y, 2));
        }

        // 找出最长和最短边
        double minSide = Double.MAX_VALUE;
        double maxSide = Double.MIN_VALUE;
        for (double length : sideLengths) {
            minSide = Math.min(minSide, length);
            maxSide = Math.max(maxSide, length);
        }

        // 最长边与最短边的比例不应超过5:1
        double ratio = maxSide / minSide;
        return ratio <= 5.0;
    }

    /**
     * 计算三点构成的角度（度数）
     */
    private double calculateAngleDegrees(PointF a, PointF b, PointF c) {
        double v1x = a.x - b.x;
        double v1y = a.y - b.y;
        double v2x = c.x - b.x;
        double v2y = c.y - b.y;

        double norm1 = Math.sqrt(v1x * v1x + v1y * v1y) + 1e-9;
        double norm2 = Math.sqrt(v2x * v2x + v2y * v2y) + 1e-9;

        double dot = (v1x * v2x + v1y * v2y) / (norm1 * norm2);
        dot = Math.max(-1.0, Math.min(1.0, dot));

        return Math.toDegrees(Math.acos(dot));
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
        cameraPreviewLayout.hideDetectionBox();  // 隐藏检测框
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
     * 修复：避免重复坐标变换
     */
    private void updateContentRegionsOverlay(List<MatchResult.TransformedRegion> regions) {
        if (regions == null || regions.isEmpty()) {
            cameraPreviewLayout.clearContentRegions();
            return;
        }

        // 获取当前状态快照，防止多线程竞态条件
        final DetectionResult currentDetection = lastDetectionResult;
        final MatchMode currentMode = currentMatchMode;

        // 根据模式检查必要条件
        if (currentMode == MatchMode.LABEL_DETECTION && currentDetection == null) {
            cameraPreviewLayout.clearContentRegions();
            return;
        }

        List<OverlayView.ContentRegion> overlayRegions = new ArrayList<>();
        for (MatchResult.TransformedRegion region : regions) {
            PointF[] corners = region.getTransformedCorners();
            if (corners == null || corners.length != 4) continue;

            PointF[] displayCorners;

            if (currentMode == MatchMode.COARSE) {
                // 全图模式：region.getTransformedCorners()已经是变换到原图坐标系的结果
                // 直接使用，无需再次变换
                displayCorners = corners;

                MLog.d(TAG, String.format(">> 全图模式ROI: 使用已变换坐标(%.1f,%.1f)",
                        corners[0].x, corners[0].y));

            } else {
                // 标签检测模式：需要将模板坐标变换到检测坐标系
                int modelWidth = currentDetection.getModelWidth();
                int modelHeight = currentDetection.getModelHeight();

                // 计算从模板坐标到检测坐标的缩放比例
                // 检测结果的坐标系是基于检测模型的输出尺寸
                float scaleX = (float) modelWidth / lastRotatedWidth;
                float scaleY = (float) modelHeight / lastRotatedHeight;

                displayCorners = new PointF[4];
                for (int i = 0; i < 4; i++) {
                    displayCorners[i] = new PointF(corners[i].x * scaleX, corners[i].y * scaleY);
                }

                MLog.d(TAG, String.format(">> 标签检测模式ROI: 模板坐标(%.1f,%.1f) -> 检测坐标(%.1f,%.1f), 缩放(%.3f,%.3f)",
                        corners[0].x, corners[0].y, displayCorners[0].x, displayCorners[0].y, scaleX, scaleY));
            }

            RectF displayBounds = calculateBoundsFromCorners(displayCorners);
            overlayRegions.add(new OverlayView.ContentRegion(
                    String.valueOf(region.getRegion().getId()),
                    displayBounds,
                    displayCorners,
                    region.getRegion().getName(),
                    region.hasContent()));
        }

        cameraPreviewLayout.setContentRegions(overlayRegions);
        MLog.d(TAG, String.format(">> 更新ROI显示: 模式=%s, 区域数=%d", currentMode.name(), overlayRegions.size()));
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
        if (points == null || matrix == null || matrix.empty()) {
            return null;
        }

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

            // 调试：验证变换结果的合理性
            boolean hasInvalidCoords = false;
            for (PointF p : result) {
                if (Float.isNaN(p.x) || Float.isNaN(p.y) || Float.isInfinite(p.x) || Float.isInfinite(p.y)) {
                    hasInvalidCoords = true;
                    break;
                }
            }

            if (hasInvalidCoords) {
                MLog.w(TAG, ">> transformPointsWithMatrix: produced invalid coordinates");
                return null;
            }

            return result;

        } catch (Exception e) {
            MLog.e(TAG, ">> transformPointsWithMatrix failed", e);
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
