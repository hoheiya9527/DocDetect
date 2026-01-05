package com.urovo.templatedetector.matcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import com.urovo.templatedetector.model.MatchResult;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.model.TemplateRegion;
import com.urovo.templatedetector.utils.MLog;

import org.opencv.android.Utils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的模板匹配器 - 基于ORB算法
 * <p>
 * 提供完整的模板匹配功能：
 * - 特征提取
 * - 模板匹配
 * - 几何验证
 */
public class SimpleTemplateMatcher {

    private static final String TAG = "SimpleTemplateMatcher";

    // ORB参数 - 针对条码大尺度变化优化
    private static final int MAX_FEATURES = 5000;  // 适当增加特征点
    private static final float SCALE_FACTOR = 1.1f;  // 更密集的尺度采样
    private static final int PYRAMID_LEVELS = 20;  // 增加到15层覆盖更大尺度范围

    // 匹配参数 - 提高匹配质量
    private static final int MIN_MATCH_COUNT = 3;      // 提高到6个确保质量
    private static final float LOWE_RATIO = 0.8f;     // 收紧到0.75提高质量
    private static final double RANSAC_THRESHOLD = 7.0; // 收紧RANSAC阈值
    private static final float MIN_CONFIDENCE = 0.3f;   // 提高置信度要求

    private final ORB orbDetector;
    private final BFMatcher matcher;
    private boolean initialized = false;

    public SimpleTemplateMatcher(Context context) {
        try {
            // 针对条码优化的ORB参数
            this.orbDetector = ORB.create(
                    MAX_FEATURES,     // nfeatures: 2500
                    SCALE_FACTOR,     // scaleFactor: 1.1 (更密集采样)
                    PYRAMID_LEVELS,   // nlevels: 15 (覆盖更大尺度范围)
                    31,               // edgeThreshold: 31
                    0,                // firstLevel: 0
                    2,                // WTA_K: 2
                    ORB.HARRIS_SCORE, // scoreType: HARRIS_SCORE
                    31,               // patchSize: 31
                    3                 // fastThreshold: 进一步降低到3
            );
            this.matcher = BFMatcher.create(BFMatcher.BRUTEFORCE_HAMMING, false);
            this.initialized = true;
        } catch (Exception e) {
            MLog.e(TAG, "Failed to initialize SimpleTemplateMatcher", e);
            throw new RuntimeException("Initialization failed", e);
        }
    }

    // ==================== 特征提取 ====================

    /**
     * 特征数据结构
     */
    public static class FeatureData {
        private final MatOfKeyPoint keypoints;
        private final Mat descriptors;
        private final int count;
        private final long extractionTimeMs;

        public FeatureData(MatOfKeyPoint keypoints, Mat descriptors, long extractionTimeMs) {
            this.keypoints = keypoints;
            this.descriptors = descriptors;
            this.count = keypoints != null ? (int) keypoints.total() : 0;
            this.extractionTimeMs = extractionTimeMs;
        }

        public MatOfKeyPoint getKeypoints() {
            return keypoints;
        }

        public Mat getDescriptors() {
            return descriptors;
        }

        public int getCount() {
            return count;
        }

        public long getExtractionTimeMs() {
            return extractionTimeMs;
        }

        public boolean isValid() {
            return keypoints != null && descriptors != null &&
                    !keypoints.empty() && !descriptors.empty() && count > 0;
        }

        public void release() {
            if (keypoints != null) keypoints.release();
            if (descriptors != null) descriptors.release();
        }
    }

    /**
     * 从Bitmap提取特征
     */
    public FeatureData extractFeatures(Bitmap bitmap) {
        if (!initialized || bitmap == null) {
            return null;
        }

        long startTime = System.currentTimeMillis();
        Mat colorMat = null;
        Mat grayMat = null;

        try {
            // Bitmap -> Mat
            colorMat = new Mat();
            Utils.bitmapToMat(bitmap, colorMat);

            // 转灰度
            grayMat = new Mat();
            if (colorMat.channels() == 4) {
                Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGBA2GRAY);
            } else if (colorMat.channels() == 3) {
                Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGB2GRAY);
            } else {
                grayMat = colorMat.clone();
            }

            // 提取特征
            MatOfKeyPoint keypoints = new MatOfKeyPoint();
            Mat descriptors = new Mat();
            orbDetector.detectAndCompute(grayMat, new Mat(), keypoints, descriptors);

            long extractionTime = System.currentTimeMillis() - startTime;
            int count = (int) keypoints.total();

            if (count == 0) {
                keypoints.release();
                descriptors.release();
                return null;
            }

            return new FeatureData(keypoints, descriptors, extractionTime);

        } catch (Exception e) {
            MLog.e(TAG, ">> extractFeatures failed", e);
            return null;
        } finally {
            if (colorMat != null) colorMat.release();
            if (grayMat != null) grayMat.release();
        }
    }

    /**
     * 从彩色Mat提取特征
     */
    public FeatureData extractFeaturesFromMat(Mat colorMat) {
        if (!initialized || colorMat == null || colorMat.empty()) {
            return null;
        }

        long startTime = System.currentTimeMillis();
        Mat grayMat = null;

        try {
            // 转灰度
            grayMat = new Mat();
            if (colorMat.channels() == 4) {
                Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGBA2GRAY);
            } else if (colorMat.channels() == 3) {
                Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGB2GRAY);
            } else {
                grayMat = colorMat.clone();
            }

            // 提取特征
            MatOfKeyPoint keypoints = new MatOfKeyPoint();
            Mat descriptors = new Mat();
            orbDetector.detectAndCompute(grayMat, new Mat(), keypoints, descriptors);

            long extractionTime = System.currentTimeMillis() - startTime;
            int count = (int) keypoints.total();

            if (count == 0) {
                keypoints.release();
                descriptors.release();
                return null;
            }

            return new FeatureData(keypoints, descriptors, extractionTime);

        } catch (Exception e) {
            MLog.e(TAG, ">> extractFeaturesFromMat failed", e);
            return null;
        } finally {
            if (grayMat != null) grayMat.release();
        }
    }

    // ==================== 模板匹配 ====================

    /**
     * 匹配单个模板
     */
    public MatchResult matchTemplate(Bitmap inputImage, Template template) {
        if (!initialized || inputImage == null || template == null) {
            return MatchResult.failure("Invalid input");
        }

        long startTime = System.currentTimeMillis();

        try {
            // 提取输入图像特征
            FeatureData inputFeatures = extractFeatures(inputImage);
            if (inputFeatures == null) {
                return MatchResult.failure("Failed to extract input features");
            }

            // 加载模板特征
            FeatureData templateFeatures = loadTemplateFeatures(template);
            if (templateFeatures == null) {
                inputFeatures.release();
                return MatchResult.failure("Failed to load template features");
            }

            // 执行匹配
            MatchResult result = performMatching(inputFeatures, templateFeatures, template,
                    inputImage.getWidth(), inputImage.getHeight(), startTime);

            inputFeatures.release();
            templateFeatures.release();

            return result;

        } catch (Exception e) {
            MLog.e(TAG, ">> matchTemplate failed", e);
            return MatchResult.failure("Match error: " + e.getMessage());
        }
    }

    /**
     * 从Mat匹配单个模板
     */
    public MatchResult matchTemplateFromMat(Mat inputMat, Template template) {
        return matchTemplateFromMat(inputMat, template, false);
    }

    /**
     * 从Mat匹配单个模板（支持标签检测模式）
     *
     * @param inputMat             输入图像Mat
     * @param template             模板
     * @param isLabelDetectionMode 是否为标签检测模式
     */
    public MatchResult matchTemplateFromMat(Mat inputMat, Template template, boolean isLabelDetectionMode) {
        if (!initialized || inputMat == null || inputMat.empty() || template == null) {
            return MatchResult.failure("Invalid input");
        }

        long startTime = System.currentTimeMillis();

        try {
            // 提取输入图像特征
            FeatureData inputFeatures = extractFeaturesFromMat(inputMat);
            if (inputFeatures == null) {
                return MatchResult.failure("Failed to extract input features");
            }

            // 加载模板特征
            FeatureData templateFeatures = loadTemplateFeatures(template);
            if (templateFeatures == null) {
                inputFeatures.release();
                return MatchResult.failure("Failed to load template features");
            }

            // 执行匹配
            MatchResult result = performMatching(inputFeatures, templateFeatures, template,
                    inputMat.cols(), inputMat.rows(), startTime, isLabelDetectionMode);

            inputFeatures.release();
            templateFeatures.release();

            return result;

        } catch (Exception e) {
            MLog.e(TAG, ">> matchTemplateFromMat failed", e);
            return MatchResult.failure("Match error: " + e.getMessage());
        }
    }

    /**
     * 匹配多个模板，返回最佳匹配
     */
    public MatchResult matchBestTemplate(Bitmap inputImage, List<Template> templates) {
        if (templates == null || templates.isEmpty()) {
            return MatchResult.failure("No templates provided");
        }

        MatchResult bestResult = null;
        float bestConfidence = 0;

        for (Template template : templates) {
            if (!template.isEnabled() || !template.isValid()) {
                continue;
            }

            MatchResult result = matchTemplate(inputImage, template);
            if (result.isSuccess() && result.getConfidence() > bestConfidence) {
                if (bestResult != null) {
                    bestResult.release();
                }
                bestResult = result;
                bestConfidence = result.getConfidence();
            } else {
                result.release();
            }
        }

        return bestResult != null ? bestResult : MatchResult.noMatch();
    }

    /**
     * 从Mat匹配多个模板，返回最佳匹配
     */
    public MatchResult matchBestTemplateFromMat(Mat inputMat, List<Template> templates) {
        return matchBestTemplateFromMat(inputMat, templates, false);
    }

    /**
     * 从Mat匹配多个模板，返回最佳匹配（支持标签检测模式）
     */
    public MatchResult matchBestTemplateFromMat(Mat inputMat, List<Template> templates, boolean isLabelDetectionMode) {
        if (templates == null || templates.isEmpty()) {
            return MatchResult.failure("No templates provided");
        }

        MatchResult bestResult = null;
        float bestConfidence = 0;

        for (Template template : templates) {
            if (!template.isEnabled() || !template.isValid()) {
                continue;
            }

            MatchResult result = matchTemplateFromMat(inputMat, template, isLabelDetectionMode);
            if (result.isSuccess() && result.getConfidence() > bestConfidence) {
                if (bestResult != null) {
                    bestResult.release();
                }
                bestResult = result;
                bestConfidence = result.getConfidence();
            } else {
                result.release();
            }
        }

        return bestResult != null ? bestResult : MatchResult.noMatch();
    }

    // ==================== 内部实现 ====================

    /**
     * 加载模板特征
     */
    private FeatureData loadTemplateFeatures(Template template) {
        try {
            MatOfKeyPoint keypoints = FeatureSerializer.loadKeypoints(template.getKeypointsPath());
            Mat descriptors = FeatureSerializer.loadDescriptors(template.getDescriptorsPath());

            if (keypoints == null || descriptors == null) {
                if (keypoints != null) keypoints.release();
                if (descriptors != null) descriptors.release();
                return null;
            }

            return new FeatureData(keypoints, descriptors, 0);

        } catch (Exception e) {
            MLog.e(TAG, ">> loadTemplateFeatures failed", e);
            return null;
        }
    }

    /**
     * 执行匹配
     */
    private MatchResult performMatching(FeatureData inputFeatures, FeatureData templateFeatures,
                                        Template template, int imageWidth, int imageHeight, long startTime) {
        return performMatching(inputFeatures, templateFeatures, template, imageWidth, imageHeight, startTime, false);
    }

    /**
     * 执行匹配（支持标签检测模式）
     */
    private MatchResult performMatching(FeatureData inputFeatures, FeatureData templateFeatures,
                                        Template template, int imageWidth, int imageHeight, long startTime, boolean isLabelDetectionMode) {
        try {
            // 特征匹配
            List<MatOfDMatch> knnMatches = new ArrayList<>();
            matcher.knnMatch(inputFeatures.descriptors, templateFeatures.descriptors, knnMatches, 2);

            // Lowe's ratio test + 距离筛选
            List<DMatch> goodMatches = new ArrayList<>();
            for (MatOfDMatch matOfDMatch : knnMatches) {
                DMatch[] matches = matOfDMatch.toArray();
                if (matches.length >= 2) {
                    DMatch bestMatch = matches[0];
                    DMatch secondMatch = matches[1];

                    // Lowe's ratio test
                    if (bestMatch.distance < LOWE_RATIO * secondMatch.distance) {
                        // 额外的距离筛选：过滤掉距离过大的匹配
                        if (bestMatch.distance < 250.0f) {  // 距离阈值
                            goodMatches.add(bestMatch);
                        }
                    }
                }
            }

            // 释放临时数据
            for (MatOfDMatch matOfDMatch : knnMatches) {
                matOfDMatch.release();
            }

            if (goodMatches.size() < MIN_MATCH_COUNT) {
                MLog.w(TAG, String.format(">> Insufficient matches: %d < %d (required)", goodMatches.size(), MIN_MATCH_COUNT));
                return MatchResult.failure("Insufficient matches: " + goodMatches.size());
            }

            // 几何验证
            return performGeometryValidation(inputFeatures, templateFeatures, goodMatches,
                    template, imageWidth, imageHeight, startTime, isLabelDetectionMode);

        } catch (Exception e) {
            MLog.e(TAG, ">> performMatching failed", e);
            return MatchResult.failure("Matching error: " + e.getMessage());
        }
    }

    /**
     * 几何验证
     */
    private MatchResult performGeometryValidation(FeatureData inputFeatures, FeatureData templateFeatures,
                                                  List<DMatch> goodMatches, Template template,
                                                  int imageWidth, int imageHeight, long startTime) {
        return performGeometryValidation(inputFeatures, templateFeatures, goodMatches, template,
                imageWidth, imageHeight, startTime, false);
    }

    /**
     * 几何验证（支持标签检测模式）
     */
    private MatchResult performGeometryValidation(FeatureData inputFeatures, FeatureData templateFeatures,
                                                  List<DMatch> goodMatches, Template template,
                                                  int imageWidth, int imageHeight, long startTime, boolean isLabelDetectionMode) {
        Mat homography = null;
        Mat inlierMask = null;

        try {
            // 提取匹配点
            List<Point> srcPoints = new ArrayList<>();
            List<Point> dstPoints = new ArrayList<>();

            org.opencv.core.KeyPoint[] inputKp = inputFeatures.keypoints.toArray();
            org.opencv.core.KeyPoint[] templateKp = templateFeatures.keypoints.toArray();

            float totalDistance = 0;
            for (DMatch match : goodMatches) {
                srcPoints.add(inputKp[match.queryIdx].pt);
                dstPoints.add(templateKp[match.trainIdx].pt);
                totalDistance += match.distance;
            }
            float avgDistance = totalDistance / goodMatches.size();

            // 计算置信度（基于特征匹配质量）
            float inlierRatio = 1.0f; // 默认值，在标签检测模式下不使用RANSAC
            float confidence;

            if (isLabelDetectionMode) {
                // 标签检测模式：跳过Homography计算，直接使用原始区域坐标

                // 基于特征匹配质量计算置信度，不依赖几何验证
                confidence = calculateConfidence(goodMatches.size(), 0.85f, avgDistance); // 假设较高的内点比例

                // 直接使用模板的原始区域坐标（不进行Homography变换）
                List<MatchResult.TransformedRegion> transformedRegions =
                        transformRegions(template.getRegions(), null, imageWidth, imageHeight);

                // 检查是否有有效的变换区域
                if (transformedRegions.isEmpty()) {
                    return MatchResult.failure("No valid regions after geometric transformation");
                }

                long matchTime = System.currentTimeMillis() - startTime;

                return new MatchResult.Builder()
                        .setSuccess(true)
                        .setTemplate(template)
                        .setConfidence(confidence)
                        .setInlierRatio(0.85f) // 标签检测模式下的假设值
                        .setMatchCount(goodMatches.size())
                        .setAvgDistance(avgDistance)
                        .setHomography(null) // 标签检测模式下不使用Homography
                        .setTransformedRegions(transformedRegions)
                        .setTemplateCornersInImage(new PointF[4]) // 空数组，不需要模板角点
                        .setTemplateBoundsInImage(new RectF()) // 空边界框
                        .setMatchTimeMs(matchTime)
                        .build();

            } else {
                // 全图模式：使用传统的Homography几何验证
                MatOfPoint2f srcPointsMat = new MatOfPoint2f();
                srcPointsMat.fromList(srcPoints);
                MatOfPoint2f dstPointsMat = new MatOfPoint2f();
                dstPointsMat.fromList(dstPoints);

                // 计算Homography
                inlierMask = new Mat();
                homography = Calib3d.findHomography(srcPointsMat, dstPointsMat,
                        Calib3d.RANSAC, RANSAC_THRESHOLD, inlierMask);

                srcPointsMat.release();
                dstPointsMat.release();

                if (homography == null || homography.empty()) {
                    return MatchResult.failure("Failed to compute homography");
                }

                // 计算置信度
                int inlierCount = Core.countNonZero(inlierMask);
                inlierRatio = (float) inlierCount / goodMatches.size();
                confidence = calculateConfidence(goodMatches.size(), inlierRatio, avgDistance);

                if (confidence < MIN_CONFIDENCE) {
                    return MatchResult.failure("Confidence too low: " + String.format("%.2f", confidence));
                }

                // 变换区域 - 使用Homography变换
                List<MatchResult.TransformedRegion> transformedRegions =
                        transformRegions(template.getRegions(), homography, imageWidth, imageHeight);

                // 检查是否有有效的变换区域
                if (transformedRegions.isEmpty()) {
                    MLog.w(TAG, ">> No valid regions after transformation - marking match as failed");
                    return MatchResult.failure("No valid regions after geometric transformation");
                }

                // 计算模板位置
                PointF[] templateCorners = computeTemplateCorners(template, homography, imageWidth, imageHeight);
                RectF templateBounds = computeBounds(templateCorners);

                long matchTime = System.currentTimeMillis() - startTime;

                return new MatchResult.Builder()
                        .setSuccess(true)
                        .setTemplate(template)
                        .setConfidence(confidence)
                        .setInlierRatio(inlierRatio)
                        .setMatchCount(goodMatches.size())
                        .setAvgDistance(avgDistance)
                        .setHomography(homography.clone())
                        .setTransformedRegions(transformedRegions)
                        .setTemplateCornersInImage(templateCorners)
                        .setTemplateBoundsInImage(templateBounds)
                        .setMatchTimeMs(matchTime)
                        .build();
            }

        } finally {
            if (inlierMask != null) inlierMask.release();
            if (homography != null) homography.release();
        }
    }

    /**
     * 计算置信度 - 重视匹配质量版本
     */
    private float calculateConfidence(int matchCount, float inlierRatio, float avgDistance) {
        // 匹配数量评分：适中期望
        float matchScore = Math.min(matchCount / 12.0f, 1.0f);

        // 内点比例评分：这是最重要的质量指标
        float inlierScore = Math.min(inlierRatio / 0.4f, 1.0f);

        // 距离评分：好的匹配应该有较小的距离
        float distanceScore = Math.max(0, 1.0f - avgDistance / 250.0f);

        // 基础置信度：只有高质量匹配才给基础分
        float baseConfidence = 0.0f;
        if (matchCount >= 15 && inlierRatio >= 0.5f) {
            baseConfidence = 0.2f;  // 高质量匹配
        } else if (matchCount >= 8 && inlierRatio >= 0.3f) {
            baseConfidence = 0.1f;  // 中等质量匹配
        }

        // 权重分配：重视内点比例和距离
        float confidence = 0.3f * matchScore + 0.5f * inlierScore + 0.2f * distanceScore + baseConfidence;

        // 质量惩罚：内点比例过低时大幅降低置信度
        if (inlierRatio < 0.2f) {
            confidence *= 0.5f;  // 惩罚低质量匹配
        }

        confidence = Math.min(confidence, 1.0f);

        return confidence;
    }

    // ==================== 辅助方法 ====================

    private List<MatchResult.TransformedRegion> transformRegions(List<TemplateRegion> regions,
                                                                 Mat homography, int imageWidth, int imageHeight) {
        List<MatchResult.TransformedRegion> transformedRegions = new ArrayList<>();

        if (regions == null || regions.isEmpty()) {
            return transformedRegions;
        }

        try {
            for (TemplateRegion region : regions) {
                PointF[] transformedCorners;
                RectF bounds;

                // 检查是否有有效的 Homography 矩阵
                if (homography != null && !homography.empty()) {
                    // 使用 Homography 变换区域的四个角点
                    MatOfPoint2f srcCorners = new MatOfPoint2f(
                            new Point(region.getBoundLeft(), region.getBoundTop()),
                            new Point(region.getBoundRight(), region.getBoundTop()),
                            new Point(region.getBoundRight(), region.getBoundBottom()),
                            new Point(region.getBoundLeft(), region.getBoundBottom())
                    );

                    MatOfPoint2f dstCorners = new MatOfPoint2f();

                    // 关键修正：计算逆变换（从模板坐标到输入图像坐标）
                    // 与AKAZE版本保持一致
                    Mat invHomography = homography.inv();
                    Core.perspectiveTransform(srcCorners, dstCorners, invHomography);
                    invHomography.release();

                    Point[] corners = dstCorners.toArray();
                    if (corners.length == 4) {
                        transformedCorners = new PointF[4];
                        for (int i = 0; i < 4; i++) {
                            transformedCorners[i] = new PointF((float) corners[i].x, (float) corners[i].y);
                        }
                        bounds = computeBounds(transformedCorners);
                    } else {
                        srcCorners.release();
                        dstCorners.release();
                        continue;
                    }

                    srcCorners.release();
                    dstCorners.release();
                } else {
                    // 没有 Homography 矩阵时，直接使用模板区域的原始坐标
                    // 这种情况通常发生在标签检测模式下，校正后的图像已经是标准视角

                    transformedCorners = new PointF[]{
                            new PointF(region.getBoundLeft(), region.getBoundTop()),
                            new PointF(region.getBoundRight(), region.getBoundTop()),
                            new PointF(region.getBoundRight(), region.getBoundBottom()),
                            new PointF(region.getBoundLeft(), region.getBoundBottom())
                    };
                    bounds = new RectF(region.getBoundLeft(), region.getBoundTop(),
                            region.getBoundRight(), region.getBoundBottom());
                }

                // 严格的几何验证，防止异常区域传递给解码器
                boolean isValidRegion = validateTransformedRegion(bounds, transformedCorners,
                        imageWidth, imageHeight, region.getName());

                if (isValidRegion) {
                    MatchResult.TransformedRegion transformedRegion =
                            new MatchResult.TransformedRegion(region, bounds, transformedCorners);
                    transformedRegions.add(transformedRegion);
                }
            }
        } catch (Exception e) {
            MLog.e(TAG, "transformRegions failed", e);
        }

        return transformedRegions;
    }

    /**
     * 验证变换后的区域是否合理，防止异常区域导致解码器崩溃
     */
    private boolean validateTransformedRegion(RectF bounds, PointF[] corners,
                                              int imageWidth, int imageHeight, String regionName) {
        // 1. 基本有效性检查
        if (bounds == null || corners == null || corners.length != 4) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> null bounds or corners", regionName));
            return false;
        }

        // 2. 检查坐标是否为有效数值
        if (Float.isNaN(bounds.left) || Float.isNaN(bounds.top) ||
                Float.isNaN(bounds.right) || Float.isNaN(bounds.bottom) ||
                Float.isInfinite(bounds.left) || Float.isInfinite(bounds.top) ||
                Float.isInfinite(bounds.right) || Float.isInfinite(bounds.bottom)) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> invalid coordinates (NaN/Infinite)", regionName));
            return false;
        }

        // 3. 检查区域尺寸
        float width = bounds.width();
        float height = bounds.height();

        if (width <= 0 || height <= 0) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> non-positive size %.1fx%.1f",
                    regionName, width, height));
            return false;
        }

        // 4. 防止解码器崩溃：设置最小尺寸限制
        final float MIN_SIZE = 10.0f;  // 降低到10x10像素，避免过度过滤
        if (width < MIN_SIZE || height < MIN_SIZE) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> too small %.1fx%.1f (min %.1f)",
                    regionName, width, height, MIN_SIZE));
            return false;
        }

        // 5. 防止内存溢出：设置最大尺寸限制
        final float MAX_SIZE = Math.max(imageWidth, imageHeight) * 5.0f;  // 放宽到3倍
        if (width > MAX_SIZE || height > MAX_SIZE) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> too large %.1fx%.1f (max %.1f)",
                    regionName, width, height, MAX_SIZE));
            return false;
        }

        // 6. 检查长宽比，避免过于扁平或细长的区域
        float aspectRatio = Math.max(width, height) / Math.min(width, height);
        final float MAX_ASPECT_RATIO = 50.0f;  // 放宽到50:1
        if (aspectRatio > MAX_ASPECT_RATIO) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> extreme aspect ratio %.1f (max %.1f)",
                    regionName, aspectRatio, MAX_ASPECT_RATIO));
            return false;
        }

        // 7. 检查区域面积
        float area = width * height;
        final float MIN_AREA = MIN_SIZE * MIN_SIZE;  // 最小面积
        final float MAX_AREA = MAX_SIZE * MAX_SIZE;  // 最大面积
        if (area < MIN_AREA || area > MAX_AREA) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> invalid area %.0f (range %.0f-%.0f)",
                    regionName, area, MIN_AREA, MAX_AREA));
            return false;
        }

        // 8. 检查是否有负坐标（可能的变换异常）
        if (bounds.left < -imageWidth || bounds.top < -imageHeight ||
                bounds.right < -imageWidth || bounds.bottom < -imageHeight) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> extreme negative coordinates [%.1f,%.1f,%.1f,%.1f]",
                    regionName, bounds.left, bounds.top, bounds.right, bounds.bottom));
            return false;
        }

        // 9. 检查是否超出合理范围（可能的变换异常）
        final float MAX_COORD = Math.max(imageWidth, imageHeight) * 3.0f;  // 允许超出图像3倍范围
        if (Math.abs(bounds.left) > MAX_COORD || Math.abs(bounds.top) > MAX_COORD ||
                Math.abs(bounds.right) > MAX_COORD || Math.abs(bounds.bottom) > MAX_COORD) {
            MLog.w(TAG, String.format(">> Region validation failed: %s -> coordinates too extreme [%.1f,%.1f,%.1f,%.1f] (max %.1f)",
                    regionName, bounds.left, bounds.top, bounds.right, bounds.bottom, MAX_COORD));
            return false;
        }

        // 10. 检查四角点的几何合理性
        if (!validateCornerGeometry(corners, regionName)) {
            return false;
        }

        return true;
    }

    /**
     * 验证四角点的几何合理性
     */
    private boolean validateCornerGeometry(PointF[] corners, String regionName) {
        try {
            // 检查是否有重复点
            for (int i = 0; i < corners.length; i++) {
                for (int j = i + 1; j < corners.length; j++) {
                    float dx = corners[i].x - corners[j].x;
                    float dy = corners[i].y - corners[j].y;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);

                    if (distance < 2.0f) {  // 降低到2像素，避免过度过滤
                        MLog.w(TAG, String.format(">> Corner validation failed: %s -> points too close (%.1f)",
                                regionName, distance));
                        return false;
                    }
                }
            }

            // 检查是否形成合理的四边形（简单检查：不能所有点共线）
            // 计算向量叉积，检查是否有面积
            float area = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                area += corners[i].x * corners[j].y - corners[j].x * corners[i].y;
            }
            area = Math.abs(area) / 2.0f;

            if (area < 25.0f) {  // 降低面积要求到25平方像素
                MLog.w(TAG, String.format(">> Corner validation failed: %s -> degenerate quadrilateral (area %.1f)",
                        regionName, area));
                return false;
            }

            return true;
        } catch (Exception e) {
            MLog.w(TAG, String.format(">> Corner validation failed: %s -> exception: %s", regionName, e.getMessage()));
            return false;
        }
    }

    private PointF[] computeTemplateCorners(Template template, Mat homography, int imageWidth, int imageHeight) {
        if (template == null || homography == null || homography.empty()) {
            return new PointF[4];
        }

        try {
            // 模板的四个角点
            MatOfPoint2f srcCorners = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(template.getImageWidth(), 0),
                    new Point(template.getImageWidth(), template.getImageHeight()),
                    new Point(0, template.getImageHeight())
            );

            MatOfPoint2f dstCorners = new MatOfPoint2f();
            Core.perspectiveTransform(srcCorners, dstCorners, homography);

            Point[] corners = dstCorners.toArray();
            PointF[] result = new PointF[4];

            for (int i = 0; i < Math.min(4, corners.length); i++) {
                result[i] = new PointF((float) corners[i].x, (float) corners[i].y);
            }

            srcCorners.release();
            dstCorners.release();

            return result;

        } catch (Exception e) {
            MLog.e(TAG, ">> computeTemplateCorners failed", e);
            return new PointF[4];
        }
    }

    private RectF computeBounds(PointF[] corners) {
        if (corners == null || corners.length == 0) {
            return new RectF();
        }

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        float maxY = Float.MIN_VALUE;

        for (PointF corner : corners) {
            if (corner != null && !Float.isNaN(corner.x) && !Float.isNaN(corner.y) &&
                    !Float.isInfinite(corner.x) && !Float.isInfinite(corner.y)) {
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
            }
        }

        // 检查计算结果是否有效
        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE ||
                maxX == Float.MIN_VALUE || maxY == Float.MIN_VALUE ||
                maxX <= minX || maxY <= minY) {
            MLog.w(TAG, ">> computeBounds: Invalid corner points, returning empty rect");
            return new RectF();
        }

        return new RectF(minX, minY, maxX, maxY);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void release() {
        initialized = false;
    }
}