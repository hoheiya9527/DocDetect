package com.urovo.templatedetector.matcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;
import android.util.LruCache;

import com.urovo.templatedetector.model.MatchResult;
import com.urovo.templatedetector.model.Template;
import com.urovo.templatedetector.model.TemplateRegion;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.DescriptorMatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板匹配器
 * 使用 AKAZE 特征匹配 + Homography 变换实现模板匹配
 * 
 * 性能优化：
 * - 模板特征 LruCache 缓存
 * - 输入特征复用（多模板匹配时）
 * - 支持直接传入 Mat 避免格式转换
 */
public class TemplateMatcher {

    private static final String TAG = "TemplateMatcher";

    /** 最小匹配点数量（降低以支持远距离匹配） */
    private static final int MIN_MATCH_COUNT = 6;
    
    /** Lowe's ratio test 阈值（放宽以获取更多匹配点） */
    private static final float LOWE_RATIO = 0.80f;
    
    /** RANSAC 重投影误差阈值（放宽以容忍更多误差） */
    private static final double RANSAC_THRESHOLD = 8.0;
    
    /** 最低置信度阈值（低于此值判定为匹配失败） */
    private static final float MIN_CONFIDENCE_THRESHOLD = 0.40f;

    /** 置信度权重配置 */
    private static final float W_INLIER_RATIO = 0.5f;
    private static final float W_MATCH_COUNT = 0.3f;
    private static final float W_DISTANCE = 0.2f;

    /** 模板特征缓存大小 */
    private static final int FEATURE_CACHE_SIZE = 10;

    private final Context context;
    private final FeatureExtractor featureExtractor;
    private final BFMatcher matcher;
    
    /** 模板特征缓存 */
    private final LruCache<Long, CachedTemplateFeature> featureCache;

    private boolean initialized = false;

    /**
     * 缓存的模板特征
     */
    private static class CachedTemplateFeature {
        final Mat descriptors;
        final MatOfKeyPoint keypoints;
        final long lastModified;

        CachedTemplateFeature(Mat descriptors, MatOfKeyPoint keypoints, long lastModified) {
            this.descriptors = descriptors;
            this.keypoints = keypoints;
            this.lastModified = lastModified;
        }

        void release() {
            if (descriptors != null) descriptors.release();
            if (keypoints != null) keypoints.release();
        }
    }

    public TemplateMatcher(Context context) {
        this.context = context.getApplicationContext();
        this.featureExtractor = new FeatureExtractor();
        // AKAZE 使用 Hamming 距离
        this.matcher = BFMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING, false);
        // 初始化特征缓存
        this.featureCache = new LruCache<Long, CachedTemplateFeature>(FEATURE_CACHE_SIZE) {
            @Override
            protected void entryRemoved(boolean evicted, Long key, 
                    CachedTemplateFeature oldValue, CachedTemplateFeature newValue) {
                if (oldValue != null) {
                    oldValue.release();
                }
            }
        };
        this.initialized = true;
        Log.d(TAG, "TemplateMatcher initialized with feature cache size=" + FEATURE_CACHE_SIZE);
    }

    /**
     * 匹配单个模板
     * @param inputBitmap 输入图像
     * @param template 模板
     * @return 匹配结果
     */
    public MatchResult match(Bitmap inputBitmap, Template template) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }
        if (inputBitmap == null) {
            return MatchResult.failure("Input image is null");
        }
        if (template == null || !template.isValid()) {
            return MatchResult.failure("Invalid template");
        }

        // 提取输入图像特征
        FeatureExtractor.FeatureData inputFeatures = featureExtractor.extract(inputBitmap);
        if (inputFeatures == null || !inputFeatures.isValid()) {
            return MatchResult.failure("Failed to extract input features");
        }

        try {
            return matchWithFeatures(inputFeatures, template, 
                    inputBitmap.getWidth(), inputBitmap.getHeight());
        } finally {
            inputFeatures.release();
        }
    }

    /**
     * 从 Mat 匹配单个模板（避免 Mat->Bitmap 转换）
     * @param inputMat 输入图像 Mat（彩色或灰度）
     * @param template 模板
     * @return 匹配结果
     */
    public MatchResult matchFromMat(Mat inputMat, Template template) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }
        if (inputMat == null || inputMat.empty()) {
            return MatchResult.failure("Input Mat is null or empty");
        }
        if (template == null || !template.isValid()) {
            return MatchResult.failure("Invalid template");
        }

        // 提取输入图像特征
        FeatureExtractor.FeatureData inputFeatures = featureExtractor.extractFromColorMat(inputMat);
        if (inputFeatures == null || !inputFeatures.isValid()) {
            return MatchResult.failure("Failed to extract input features");
        }

        try {
            return matchWithFeatures(inputFeatures, template, inputMat.cols(), inputMat.rows());
        } finally {
            inputFeatures.release();
        }
    }

    /**
     * 使用已提取的特征匹配模板（用于特征复用）
     * @param inputFeatures 输入图像特征（调用方负责释放）
     * @param template 模板
     * @param imageWidth 输入图像宽度
     * @param imageHeight 输入图像高度
     * @return 匹配结果
     */
    public MatchResult matchWithFeatures(FeatureExtractor.FeatureData inputFeatures, 
            Template template, int imageWidth, int imageHeight) {
        if (!initialized) {
            return MatchResult.failure("Matcher not initialized");
        }
        if (inputFeatures == null || !inputFeatures.isValid()) {
            return MatchResult.failure("Invalid input features");
        }
        if (template == null || !template.isValid()) {
            return MatchResult.failure("Invalid template");
        }

        long startTime = System.currentTimeMillis();

        Mat homography = null;
        Mat inlierMask = null;

        try {
            MatOfKeyPoint inputKeypoints = inputFeatures.getKeypoints();
            Mat inputDescriptors = inputFeatures.getDescriptors();

            // 加载模板特征（使用缓存）
            CachedTemplateFeature templateFeature = getTemplateFeature(template);
            if (templateFeature == null) {
                return MatchResult.failure("Failed to load template features");
            }

            Mat templateDescriptors = templateFeature.descriptors;
            MatOfKeyPoint templateKeypoints = templateFeature.keypoints;

            // 特征匹配 (KNN, k=2)
            List<MatOfDMatch> knnMatches = new ArrayList<>();
            matcher.knnMatch(inputDescriptors, templateDescriptors, knnMatches, 2);

            // Lowe's ratio test 过滤
            List<DMatch> goodMatches = new ArrayList<>();
            for (MatOfDMatch matOfDMatch : knnMatches) {
                DMatch[] matches = matOfDMatch.toArray();
                if (matches.length >= 2) {
                    if (matches[0].distance < LOWE_RATIO * matches[1].distance) {
                        goodMatches.add(matches[0]);
                    }
                }
            }

            Log.d(TAG, ">> match: template=" + template.getName() + 
                    ", goodMatches=" + goodMatches.size() + "/" + knnMatches.size() + 
                    ", minRequired=" + MIN_MATCH_COUNT);

            // 检查匹配点数量
            if (goodMatches.size() < MIN_MATCH_COUNT) {
                return new MatchResult.Builder()
                        .setSuccess(false)
                        .setTemplate(template)
                        .setMatchCount(goodMatches.size())
                        .setMatchTimeMs(System.currentTimeMillis() - startTime)
                        .setErrorMessage("Insufficient matches: " + goodMatches.size() + " < " + MIN_MATCH_COUNT)
                        .build();
            }

            // 提取匹配点坐标
            List<Point> srcPointsList = new ArrayList<>();
            List<Point> dstPointsList = new ArrayList<>();
            
            org.opencv.core.KeyPoint[] inputKpArray = inputKeypoints.toArray();
            org.opencv.core.KeyPoint[] templateKpArray = templateKeypoints.toArray();

            float totalDistance = 0;
            for (DMatch match : goodMatches) {
                srcPointsList.add(inputKpArray[match.queryIdx].pt);
                dstPointsList.add(templateKpArray[match.trainIdx].pt);
                totalDistance += match.distance;
            }
            float avgDistance = totalDistance / goodMatches.size();

            MatOfPoint2f srcPoints = new MatOfPoint2f();
            srcPoints.fromList(srcPointsList);
            MatOfPoint2f dstPoints = new MatOfPoint2f();
            dstPoints.fromList(dstPointsList);

            // 计算 Homography (RANSAC)
            inlierMask = new Mat();
            homography = Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC, RANSAC_THRESHOLD, inlierMask);

            srcPoints.release();
            dstPoints.release();

            if (homography == null || homography.empty()) {
                return MatchResult.failure("Failed to compute homography");
            }

            // 验证 Homography 有效性
            if (!isValidHomography(homography)) {
                return MatchResult.failure("Invalid homography matrix");
            }

            // 计算内点比例
            int inlierCount = Core.countNonZero(inlierMask);
            float inlierRatio = (float) inlierCount / goodMatches.size();

            Log.d(TAG, ">> match: inlierCount=" + inlierCount + ", inlierRatio=" + String.format("%.2f", inlierRatio));

            // 计算综合置信度
            float confidence = calculateConfidence(goodMatches.size(), inlierRatio, avgDistance);
            
            // 置信度阈值检查
            if (confidence < MIN_CONFIDENCE_THRESHOLD) {
                Log.d(TAG, ">> match: confidence too low: " + String.format("%.2f", confidence) + " < " + MIN_CONFIDENCE_THRESHOLD);
                return new MatchResult.Builder()
                        .setSuccess(false)
                        .setTemplate(template)
                        .setConfidence(confidence)
                        .setMatchCount(goodMatches.size())
                        .setMatchTimeMs(System.currentTimeMillis() - startTime)
                        .setErrorMessage("Confidence too low: " + String.format("%.2f", confidence))
                        .build();
            }

            // 变换模板区域坐标
            List<MatchResult.TransformedRegion> transformedRegions = transformRegions(
                    template.getRegions(), homography, imageWidth, imageHeight);

            // 计算模板在输入图像中的位置（用于粗定位引导）
            PointF[] templateCornersInImage = computeTemplateCornersInImage(
                    template.getImageWidth(), template.getImageHeight(), 
                    homography, imageWidth, imageHeight);
            RectF templateBoundsInImage = computeBoundsFromCorners(templateCornersInImage);

            // 构建结果
            long matchTime = System.currentTimeMillis() - startTime;
            
            MatchResult result = new MatchResult.Builder()
                    .setSuccess(true)
                    .setTemplate(template)
                    .setConfidence(confidence)
                    .setInlierRatio(inlierRatio)
                    .setMatchCount(goodMatches.size())
                    .setAvgDistance(avgDistance)
                    .setHomography(homography.clone())
                    .setTransformedRegions(transformedRegions)
                    .setTemplateCornersInImage(templateCornersInImage)
                    .setTemplateBoundsInImage(templateBoundsInImage)
                    .setMatchTimeMs(matchTime)
                    .build();

            Log.d(TAG, "match: success, confidence=" + confidence + ", time=" + matchTime + "ms");

            return result;

        } catch (Exception e) {
            Log.e(TAG, "match failed", e);
            return MatchResult.failure("Match error: " + e.getMessage());
        } finally {
            if (inlierMask != null) inlierMask.release();
            if (homography != null) homography.release();
        }
    }

    /**
     * 获取模板特征（优先从缓存获取）
     */
    private CachedTemplateFeature getTemplateFeature(Template template) {
        long templateId = template.getId();
        long lastModified = template.getUpdateTime();

        // 检查缓存
        CachedTemplateFeature cached = featureCache.get(templateId);
        if (cached != null && cached.lastModified == lastModified) {
            Log.d(TAG, "getTemplateFeature: cache hit for template " + templateId);
            return cached;
        }

        // 缓存未命中或已过期，从文件加载
        Log.d(TAG, "getTemplateFeature: cache miss for template " + templateId + ", loading from file");
        
        Mat descriptors = FeatureSerializer.loadDescriptors(template.getDescriptorsPath());
        MatOfKeyPoint keypoints = FeatureSerializer.loadKeypoints(template.getKeypointsPath());

        if (descriptors == null || keypoints == null) {
            if (descriptors != null) descriptors.release();
            if (keypoints != null) keypoints.release();
            return null;
        }

        // 存入缓存
        CachedTemplateFeature feature = new CachedTemplateFeature(descriptors, keypoints, lastModified);
        featureCache.put(templateId, feature);

        return feature;
    }

    /**
     * 匹配多个模板，返回最佳匹配
     * @param inputBitmap 输入图像
     * @param templates 模板列表
     * @return 最佳匹配结果
     */
    public MatchResult matchBest(Bitmap inputBitmap, List<Template> templates) {
        if (templates == null || templates.isEmpty()) {
            return MatchResult.failure("No templates provided");
        }
        if (inputBitmap == null) {
            return MatchResult.failure("Input image is null");
        }

        // 提取输入图像特征（只提取一次，复用给所有模板）
        FeatureExtractor.FeatureData inputFeatures = featureExtractor.extract(inputBitmap);
        if (inputFeatures == null || !inputFeatures.isValid()) {
            return MatchResult.failure("Failed to extract input features");
        }

        try {
            return matchBestWithFeatures(inputFeatures, templates, 
                    inputBitmap.getWidth(), inputBitmap.getHeight());
        } finally {
            inputFeatures.release();
        }
    }

    /**
     * 从 Mat 匹配多个模板，返回最佳匹配（避免 Mat->Bitmap 转换）
     * @param inputMat 输入图像 Mat
     * @param templates 模板列表
     * @return 最佳匹配结果
     */
    public MatchResult matchBestFromMat(Mat inputMat, List<Template> templates) {
        if (templates == null || templates.isEmpty()) {
            return MatchResult.failure("No templates provided");
        }
        if (inputMat == null || inputMat.empty()) {
            return MatchResult.failure("Input Mat is null or empty");
        }

        // 提取输入图像特征（只提取一次）
        FeatureExtractor.FeatureData inputFeatures = featureExtractor.extractFromColorMat(inputMat);
        if (inputFeatures == null || !inputFeatures.isValid()) {
            return MatchResult.failure("Failed to extract input features");
        }

        try {
            return matchBestWithFeatures(inputFeatures, templates, inputMat.cols(), inputMat.rows());
        } finally {
            inputFeatures.release();
        }
    }

    /**
     * 使用已提取的特征匹配多个模板（特征复用）
     */
    private MatchResult matchBestWithFeatures(FeatureExtractor.FeatureData inputFeatures,
            List<Template> templates, int imageWidth, int imageHeight) {
        
        MatchResult bestResult = null;
        float bestConfidence = 0;

        for (Template template : templates) {
            if (!template.isEnabled() || !template.isValid()) {
                continue;
            }

            MatchResult result = matchWithFeatures(inputFeatures, template, imageWidth, imageHeight);
            
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

        if (bestResult == null) {
            return MatchResult.noMatch();
        }

        return bestResult;
    }

    /**
     * 计算综合置信度
     */
    private float calculateConfidence(int matchCount, float inlierRatio, float avgDistance) {
        // 内点比例分数 (0~1)，70%内点=满分
        float inlierScore = Math.min(inlierRatio / 0.7f, 1.0f);
        
        // 匹配数量分数 (0~1)，50个匹配=满分
        float matchScore = Math.min(matchCount / 50.0f, 1.0f);
        
        // 距离分数 (0~1)，距离越小越好
        // AKAZE Hamming 距离，好的匹配通常 < 50
        float distanceScore = Math.max(0, 1.0f - avgDistance / 100.0f);
        
        return W_INLIER_RATIO * inlierScore 
             + W_MATCH_COUNT * matchScore 
             + W_DISTANCE * distanceScore;
    }

    /**
     * 验证 Homography 矩阵有效性
     */
    private boolean isValidHomography(Mat H) {
        if (H == null || H.empty() || H.rows() != 3 || H.cols() != 3) {
            return false;
        }

        // 检查行列式（粗定位时图像会缩放，所以阈值放宽到 0.01 ~ 100）
        Mat subMat = H.submat(0, 2, 0, 2);
        double det = Core.determinant(subMat);
        subMat.release();
        
        if (det < 0.01 || det > 100) {
            Log.w(TAG, ">> isValidHomography: invalid determinant: " + det);
            return false;
        }

        // 检查是否有 NaN 或 Inf
        double[] data = new double[9];
        H.get(0, 0, data);
        for (double d : data) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                Log.w(TAG, ">> isValidHomography: contains NaN or Inf");
                return false;
            }
        }

        Log.d(TAG, ">> isValidHomography: det=" + String.format("%.2f", det) + ", valid");
        return true;
    }

    /**
     * 变换模板区域坐标到输入图像坐标系
     * 注意：Homography 是从输入图像到模板的变换，需要求逆
     */
    private List<MatchResult.TransformedRegion> transformRegions(
            List<TemplateRegion> regions, Mat homography, int imageWidth, int imageHeight) {
        
        List<MatchResult.TransformedRegion> result = new ArrayList<>();
        
        if (regions == null || regions.isEmpty() || homography == null) {
            return result;
        }

        // 计算逆变换（从模板坐标到输入图像坐标）
        Mat invHomography = homography.inv();

        try {
            for (TemplateRegion region : regions) {
                if (!region.isEnabled() || !region.isValid()) {
                    continue;
                }

                PointF[] corners = region.getCornerPoints();
                if (corners == null || corners.length != 4) {
                    continue;
                }

                // 构建源点
                MatOfPoint2f srcPoints = new MatOfPoint2f(
                        new Point(corners[0].x, corners[0].y),
                        new Point(corners[1].x, corners[1].y),
                        new Point(corners[2].x, corners[2].y),
                        new Point(corners[3].x, corners[3].y)
                );

                // 透视变换
                MatOfPoint2f dstPoints = new MatOfPoint2f();
                Core.perspectiveTransform(srcPoints, dstPoints, invHomography);

                Point[] transformedPoints = dstPoints.toArray();
                srcPoints.release();
                dstPoints.release();

                // 转换为 PointF 并限制在图像范围内
                PointF[] transformedCorners = new PointF[4];
                float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
                float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

                for (int i = 0; i < 4; i++) {
                    float x = (float) Math.max(0, Math.min(imageWidth, transformedPoints[i].x));
                    float y = (float) Math.max(0, Math.min(imageHeight, transformedPoints[i].y));
                    transformedCorners[i] = new PointF(x, y);
                    
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }

                RectF transformedBounds = new RectF(minX, minY, maxX, maxY);

                result.add(new MatchResult.TransformedRegion(region, transformedBounds, transformedCorners));
            }
        } finally {
            invHomography.release();
        }

        return result;
    }

    /**
     * 计算模板在输入图像中的四角点位置
     * 用于粗定位引导，显示模板在原图中的位置
     */
    private PointF[] computeTemplateCornersInImage(int templateWidth, int templateHeight,
            Mat homography, int imageWidth, int imageHeight) {
        if (homography == null || homography.empty() || templateWidth <= 0 || templateHeight <= 0) {
            return null;
        }

        Mat invHomography = null;
        try {
            // 模板四角（模板坐标系）
            MatOfPoint2f templateCorners = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(templateWidth, 0),
                    new Point(templateWidth, templateHeight),
                    new Point(0, templateHeight)
            );

            // 逆变换到输入图像坐标系
            invHomography = homography.inv();
            MatOfPoint2f imageCorners = new MatOfPoint2f();
            Core.perspectiveTransform(templateCorners, imageCorners, invHomography);

            Point[] points = imageCorners.toArray();
            templateCorners.release();
            imageCorners.release();

            // 转换为 PointF（不限制边界，允许超出图像范围以便显示引导）
            PointF[] result = new PointF[4];
            for (int i = 0; i < 4; i++) {
                result[i] = new PointF((float) points[i].x, (float) points[i].y);
            }
            Log.d(TAG, ">> computeTemplateCornersInImage: corners[0]=(" + 
                    String.format("%.1f,%.1f", result[0].x, result[0].y) + ")");
            return result;

        } catch (Exception e) {
            Log.e(TAG, "computeTemplateCornersInImage failed", e);
            return null;
        } finally {
            if (invHomography != null) invHomography.release();
        }
    }

    /**
     * 从四角点计算边界框
     */
    private RectF computeBoundsFromCorners(PointF[] corners) {
        if (corners == null || corners.length != 4) {
            return null;
        }

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

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 清除模板特征缓存
     */
    public void clearFeatureCache() {
        featureCache.evictAll();
        Log.d(TAG, "Feature cache cleared");
    }

    /**
     * 从缓存中移除指定模板的特征
     */
    public void invalidateTemplateCache(long templateId) {
        featureCache.remove(templateId);
        Log.d(TAG, "Invalidated cache for template " + templateId);
    }

    /**
     * 释放资源
     */
    public void release() {
        featureCache.evictAll();
        if (featureExtractor != null) {
            featureExtractor.release();
        }
        initialized = false;
        Log.d(TAG, "TemplateMatcher released");
    }
}
