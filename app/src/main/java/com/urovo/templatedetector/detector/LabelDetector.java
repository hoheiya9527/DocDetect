package com.urovo.templatedetector.detector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.urovo.templatedetector.model.DetectionResult;
import com.urovo.templatedetector.util.MLog;
import com.urovo.templatedetector.util.PerformanceTracker;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 标签检测器
 * 参考: https://github.com/pynicolas/FairScan
 */
public class LabelDetector {

    private static final String TAG = "LabelDetector";
    private static final String MODEL_PATH = "model/fairscan-segmentation-model.tflite";

    // 最小四边形面积比例（降低以适应面单场景）
    private static final double MIN_QUAD_AREA_RATIO = 0.005;  // 从0.02降低到0.005

    private final Context context;
    private Interpreter interpreter;
    private boolean isInitialized = false;

    // 模型输出尺寸
    private int outputWidth;
    private int outputHeight;

    // 图像处理器
    private ImageProcessor imageProcessor;

    public LabelDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 初始化检测器
     */
    public synchronized boolean initialize() {
        if (isInitialized) {
            return true;
        }

        try {
            // 加载模型
            ByteBuffer modelBuffer = FileUtil.loadMappedFile(context, MODEL_PATH);
            Log.i(TAG, "Loaded LiteRT model");

            // 配置解释器选项
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(2);

            interpreter = new Interpreter(modelBuffer, options);

            // 获取模型输出形状 [1, height, width, 1]
            int[] outputShape = interpreter.getOutputTensor(0).shape();
            outputHeight = outputShape[1];
            outputWidth = outputShape[2];
            Log.d(TAG, "Model output shape: " + Arrays.toString(outputShape));

            // 创建图像处理器
            imageProcessor = new ImageProcessor.Builder()
                    .add(new ResizeOp(outputHeight, outputWidth, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new NormalizeOp(127.5f, 127.5f))
                    .build();

            isInitialized = true;
            Log.d(TAG, "LabelDetector initialized successfully");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize LabelDetector", e);
            return false;
        }
    }

    /**
     * 检测标签区域（Mat输入）
     */
    public DetectionResult detect(Mat mat) {
        if (!isInitialized || mat == null || mat.empty()) {
            return DetectionResult.notDetected();
        }

        // Mat → Bitmap（TensorImage需要Bitmap输入）
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);

            // 根据通道数转换
            if (mat.channels() == 1) {
                Mat rgba = new Mat();
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA);
                Utils.matToBitmap(rgba, bitmap);
                rgba.release();
            } else if (mat.channels() == 3) {
                Mat rgba = new Mat();
                Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA);
                Utils.matToBitmap(rgba, bitmap);
                rgba.release();
            } else {
                Utils.matToBitmap(mat, bitmap);
            }

            return detect(bitmap);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /**
     * 检测标签区域（带旋转支持）
     *
     * @param bitmap          彩色 Bitmap（ARGB_8888）
     * @param rotationDegrees 旋转角度（0, 90, 180, 270）
     * @return 检测结果
     */
    public DetectionResult detect(Bitmap bitmap, int rotationDegrees) {
        if (!isInitialized || bitmap == null) {
            return DetectionResult.notDetected();
        }

        // 计算旋转后的尺寸
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        boolean isRotated90or270 = (rotationDegrees == 90 || rotationDegrees == 270);
        int rotatedWidth = isRotated90or270 ? originalHeight : originalWidth;
        int rotatedHeight = isRotated90or270 ? originalWidth : originalHeight;

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.DETECTION)) {
            // 1. 创建带旋转的图像处理器
            int rotation = -rotationDegrees / 90;

            ImageProcessor rotatedImageProcessor = new ImageProcessor.Builder()
                    .add(new ResizeOp(outputHeight, outputWidth, ResizeOp.ResizeMethod.BILINEAR))
                    .add(new Rot90Op(rotation))
                    .add(new NormalizeOp(127.5f, 127.5f))
                    .build();

            // 2. 使用 TensorImage 加载并预处理图像
            TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
            tensorImage.load(bitmap);

            TensorImage processedImage = rotatedImageProcessor.process(tensorImage);

            // 3. 准备输出缓冲区
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(4 * outputHeight * outputWidth);
            outputBuffer.order(ByteOrder.nativeOrder());
            outputBuffer.rewind();

            // 4. 执行推理
            interpreter.run(processedImage.getBuffer(), outputBuffer);

            // 5. 提取概率图
            float[] probMap = extractProbMap(outputBuffer);

            // 6. 检测四边形（模型坐标系 256x256）
            PointF[] quad = detectDocumentQuad(probMap, true);

            if (quad == null) {
                return DetectionResult.notDetected();
            }

            // 7. 缩放到旋转后的图像尺寸（用于裁剪）
            PointF[] imageQuad = scaleQuad(quad, outputWidth, outputHeight, rotatedWidth, rotatedHeight);

            // 8. 在模型坐标系中扩展（用于显示）
            PointF[] expandedModelQuad = expandQuad(quad, 0.02f, outputWidth, outputHeight);

            // 计算边界框（模型坐标系）
            RectF boundingBox = calculateBoundingBox(expandedModelQuad);

            // 计算置信度
            float confidence = calculateConfidence(probMap, quad);

            // 计算旋转角度
            float rotationAngle = calculateRotationAngle(quad);

            // 返回结果：
            // - cornerPoints: 模型坐标系（用于显示）
            // - originalCornerPoints: 图像坐标系（用于裁剪）
            return new DetectionResult.Builder()
                    .setDetected(true)
                    .setCornerPoints(expandedModelQuad)
                    .setOriginalCornerPoints(imageQuad)
                    .setBoundingBox(boundingBox)
                    .setConfidence(confidence)
                    .setRotationAngle(rotationAngle)
                    .setModelSize(outputWidth, outputHeight)
                    .build();

        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            return DetectionResult.notDetected();
        }
    }

    /**
     * 检测标签区域（无旋转）
     */
    public DetectionResult detect(Bitmap bitmap) {
        return detect(bitmap, 0);
    }


    /**
     * 从ImageProxy检测（使用彩色图像 + Rot90Op）
     *
     * @param imageProxy      图像代理
     * @param rotationDegrees 旋转角度（0, 90, 180, 270）
     */
    public DetectionResult detect(ImageProxy imageProxy, int rotationDegrees) {
        Log.d(TAG, "detect(ImageProxy) called, rotation=" + rotationDegrees);
        if (imageProxy == null) {
            Log.w(TAG, "ImageProxy is null");
            return DetectionResult.notDetected();
        }

        // 直接获取彩色 Bitmap，不进行旋转（旋转在 ImageProcessor 中通过 Rot90Op 处理）
        Bitmap bitmap = imageProxyToBitmap(imageProxy);
        if (bitmap == null) {
            Log.w(TAG, "Failed to convert ImageProxy to Bitmap");
            return DetectionResult.notDetected();
        }

        Log.d(TAG, "Converted to Bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        // 使用带旋转的检测方法
        DetectionResult result = detect(bitmap, rotationDegrees);
        bitmap.recycle();
        Log.d(TAG, "Detection result: detected=" + result.isDetected());
        return result;
    }


    /**
     * 从输出缓冲区提取概率图
     */
    private float[] extractProbMap(ByteBuffer outputBuffer) {
        outputBuffer.rewind();
        float[] probMap = new float[outputWidth * outputHeight];
        outputBuffer.asFloatBuffer().get(probMap);

        // 限制概率值范围到 [0, 1]
        for (int i = 0; i < probMap.length; i++) {
            probMap[i] = Math.max(0f, Math.min(1f, probMap[i]));
        }

        return probMap;
    }

    /**
     * 面单精准检测算法（重写版）
     * 
     * 核心策略：
     * 1. 精准阈值选择：基于概率图特征动态确定最优阈值
     * 2. 边缘增强：使用形态学操作增强边缘连续性
     * 3. 智能轮廓筛选：多重过滤条件确保质量
     * 4. 简化评分系统：专注于面单特征的关键指标
     */
    private PointF[] detectDocumentQuad(float[] probMap, boolean isLiveAnalysis) {
        MLog.d(TAG, ">> detectDocumentQuad: isLiveAnalysis=" + isLiveAnalysis);
        
        // 1. 分析概率图特征
        ProbMapStats stats = analyzeProbMapStatistics(probMap);
        MLog.d(TAG, String.format(">> ProbMap统计: mean=%.3f, stdDev=%.3f, median=%.3f, min=%.3f, max=%.3f",
                stats.mean, stats.stdDev, stats.median, stats.min, stats.max));

        // 2. 智能阈值选择（专为面单优化）
        double optimalThreshold = selectOptimalThreshold(stats, isLiveAnalysis);
        MLog.d(TAG, String.format(">> 选择最优阈值: %.3f", optimalThreshold));

        // 3. 生成增强的二值掩码
        Mat enhancedMask = createEnhancedMask(probMap, optimalThreshold);
        
        // 4. 提取并筛选候选轮廓
        List<LabelCandidate> candidates = extractLabelCandidates(enhancedMask);
        MLog.d(TAG, ">> 提取候选数量: " + candidates.size());
        
        enhancedMask.release();
        
        if (candidates.isEmpty()) {
            MLog.w(TAG, ">> 未找到任何候选");
            return null;
        }

        // 5. 计算面单特征评分
        for (LabelCandidate candidate : candidates) {
            candidate.calculateLabelScore(probMap, outputWidth, outputHeight);
        }

        // 6. 按评分排序并选择最佳
        candidates.sort((a, b) -> Double.compare(b.score, a.score));
        
        // 输出候选信息
        int showCount = Math.min(3, candidates.size());
        for (int i = 0; i < showCount; i++) {
            LabelCandidate c = candidates.get(i);
            MLog.d(TAG, String.format(">> 候选#%d: score=%.3f, area=%.0f, 矩形度=%.3f, 面积比=%.3f, 概率=%.3f",
                    i + 1, c.score, c.area, c.rectangularity, c.areaRatio, c.avgProbability));
        }

        // 7. 选择最佳候选
        LabelCandidate best = candidates.get(0);
        double minThreshold = isLiveAnalysis ? 0.4 : 0.5;  // 提高质量要求
        
        if (best.score < minThreshold) {
            MLog.w(TAG, String.format(">> 最佳候选质量不足: %.3f < %.3f", best.score, minThreshold));
            return null;
        }

        MLog.d(TAG, String.format(">> 选中最佳候选: score=%.3f, area=%.0f", best.score, best.area));

        // 8. 精确角点排序
        PointF[] quad = orderCornersCorrectly(best.corners);
        MLog.d(TAG, String.format(">> 最终四边形: TL(%.1f,%.1f) TR(%.1f,%.1f) BR(%.1f,%.1f) BL(%.1f,%.1f)",
                quad[0].x, quad[0].y, quad[1].x, quad[1].y, 
                quad[2].x, quad[2].y, quad[3].x, quad[3].y));

        return quad;
    }

    /**
     * 优化掩码
     */
    private Mat refineMask(Mat original) {
        // 确保是二值图像
        Mat binaryMask = new Mat();
        Imgproc.threshold(original, binaryMask, 128, 255, Imgproc.THRESH_BINARY);

        // 闭运算（填充小孔）
        Mat kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Mat closed = new Mat();
        Imgproc.morphologyEx(binaryMask, closed, Imgproc.MORPH_CLOSE, kernelClose);
        kernelClose.release();
        binaryMask.release();

        // 开运算（去除噪点）
        Mat kernelOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Mat opened = new Mat();
        Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, kernelOpen);
        kernelOpen.release();
        closed.release();

        return opened;
    }

    /**
     * 缩放四边形到目标尺寸
     */
    private PointF[] scaleQuad(PointF[] quad, int fromWidth, int fromHeight, int toWidth, int toHeight) {
        float scaleX = (float) toWidth / fromWidth;
        float scaleY = (float) toHeight / fromHeight;

        PointF[] scaled = new PointF[4];
        for (int i = 0; i < 4; i++) {
            scaled[i] = new PointF(quad[i].x * scaleX, quad[i].y * scaleY);
        }
        return scaled;
    }

    /**
     * 向外扩展四边形
     * 从中心点向外扩展指定比例，补偿模型检测边缘不完整
     *
     * @param quad        原始四边形顶点
     * @param expandRatio 扩展比例（0.1 = 10%）
     * @param maxWidth    图像最大宽度（用于边界限制）
     * @param maxHeight   图像最大高度（用于边界限制）
     * @return 扩展后的四边形顶点
     */
    private PointF[] expandQuad(PointF[] quad, float expandRatio, int maxWidth, int maxHeight) {
        // 计算中心点
        float centerX = 0, centerY = 0;
        for (PointF p : quad) {
            centerX += p.x;
            centerY += p.y;
        }
        centerX /= 4;
        centerY /= 4;

        // 从中心向外扩展每个顶点
        PointF[] expanded = new PointF[4];
        for (int i = 0; i < 4; i++) {
            float dx = quad[i].x - centerX;
            float dy = quad[i].y - centerY;

            float newX = centerX + dx * (1 + expandRatio);
            float newY = centerY + dy * (1 + expandRatio);

            // 限制在图像边界内
            newX = Math.max(0, Math.min(maxWidth, newX));
            newY = Math.max(0, Math.min(maxHeight, newY));

            expanded[i] = new PointF(newX, newY);
        }

        return expanded;
    }

    /**
     * 计算边界框
     */
    private RectF calculateBoundingBox(PointF[] corners) {
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
     * 计算置信度
     */
    private float calculateConfidence(float[] probMap, PointF[] quad) {
        // 简单计算：四边形内的平均概率
        float sum = 0;
        int count = 0;
        for (float p : probMap) {
            if (p > 0.5f) {
                sum += p;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * 计算旋转角度
     */
    private float calculateRotationAngle(PointF[] corners) {
        float dx = corners[1].x - corners[0].x;
        float dy = corners[1].y - corners[0].y;
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * ImageProxy转Bitmap（带旋转）
     * 使用 CameraX 内置的 toBitmap() 方法
     */
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy, int rotationDegrees) {
        try {
            // 使用 CameraX 1.3+ 提供的 toBitmap() 方法
            Bitmap bitmap = imageProxy.toBitmap();

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
     * ImageProxy转Bitmap（不旋转）
     */
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        return imageProxyToBitmap(imageProxy, 0);
    }

    /**
     * 提取并校正标签图像（Mat版本）
     *
     * @param sourceMat 源图像Mat
     * @param result    检测结果
     * @return 校正后的Mat，失败返回null
     */
    public Mat extractAndCorrectMat(Mat sourceMat, DetectionResult result) {
        CorrectionResult correctionResult = extractAndCorrectMatWithTransform(sourceMat, result);
        if (correctionResult == null) {
            return null;
        }
        // 释放变换矩阵，只返回校正后的图像
        if (correctionResult.perspectiveMatrix != null) {
            correctionResult.perspectiveMatrix.release();
        }
        return correctionResult.correctedMat;
    }

    /**
     * 校正结果（包含变换矩阵）
     */
    public static class CorrectionResult {
        /**
         * 校正后的图像
         */
        public final Mat correctedMat;
        /**
         * 透视变换矩阵（从原图到校正图）
         */
        public final Mat perspectiveMatrix;
        /**
         * 校正后图像的宽度
         */
        public final int width;
        /**
         * 校正后图像的高度
         */
        public final int height;

        public CorrectionResult(Mat correctedMat, Mat perspectiveMatrix, int width, int height) {
            this.correctedMat = correctedMat;
            this.perspectiveMatrix = perspectiveMatrix;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 提取并校正标签图像，同时返回变换矩阵
     *
     * @param sourceMat 源图像Mat
     * @param result    检测结果
     * @return 校正结果（包含校正后的Mat和变换矩阵），失败返回null
     */
    public CorrectionResult extractAndCorrectMatWithTransform(Mat sourceMat, DetectionResult result) {
        if (sourceMat == null || sourceMat.empty() || result == null || !result.hasValidCorners()) {
            Log.w(TAG, "extractAndCorrectMatWithTransform: invalid input");
            return null;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.PERSPECTIVE_TRANSFORM)) {
            PointF[] corners = result.getOriginalCornerPoints();
            if (corners == null || corners.length != 4) {
                Log.w(TAG, "extractAndCorrectMatWithTransform: invalid corners");
                return null;
            }

            PointF topLeft = corners[0];
            PointF topRight = corners[1];
            PointF bottomRight = corners[2];
            PointF bottomLeft = corners[3];

            MatOfPoint2f srcPoints = new MatOfPoint2f(
                    new Point(topLeft.x, topLeft.y),
                    new Point(topRight.x, topRight.y),
                    new Point(bottomRight.x, bottomRight.y),
                    new Point(bottomLeft.x, bottomLeft.y)
            );

            float widthTop = distance(topLeft, topRight);
            float widthBottom = distance(bottomLeft, bottomRight);
            float targetWidth = (widthTop + widthBottom) / 2;

            float heightLeft = distance(topLeft, bottomLeft);
            float heightRight = distance(topRight, bottomRight);
            float targetHeight = (heightLeft + heightRight) / 2;

            int dstWidth = (int) targetWidth;
            int dstHeight = (int) targetHeight;

            if (dstWidth <= 0 || dstHeight <= 0) {
                Log.w(TAG, "extractAndCorrectMatWithTransform: invalid target size " + dstWidth + "x" + dstHeight);
                srcPoints.release();
                return null;
            }

            MatOfPoint2f dstPoints = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(dstWidth, 0),
                    new Point(dstWidth, dstHeight),
                    new Point(0, dstHeight)
            );

            Mat perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);
            Mat dstMat = new Mat();
            Imgproc.warpPerspective(sourceMat, dstMat, perspectiveMatrix, new Size(dstWidth, dstHeight));

            srcPoints.release();
            dstPoints.release();

            Log.d(TAG, "extractAndCorrectMatWithTransform: success, output size " + dstWidth + "x" + dstHeight);
            return new CorrectionResult(dstMat, perspectiveMatrix, dstWidth, dstHeight);

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract and correct mat with transform", e);
            return null;
        }
    }

    /**
     * 提取并校正标签图像
     */
    public Bitmap extractAndCorrect(Bitmap source, DetectionResult result) {
        if (source == null || result == null || !result.hasValidCorners()) {
            Log.w(TAG, "extractAndCorrect: invalid input");
            return null;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.PERSPECTIVE_TRANSFORM)) {
            // 使用原始坐标进行裁剪，而不是扩展后的坐标
            PointF[] corners = result.getOriginalCornerPoints();
            if (corners == null || corners.length != 4) {
                Log.w(TAG, "extractAndCorrect: invalid corners");
                return null;
            }
            Mat srcMat = new Mat();
            Utils.bitmapToMat(source, srcMat);

            // 确保颜色通道正确：bitmapToMat 产生 RGBA，需要转换为 BGR 进行处理
            // 然后再转回 RGBA 用于输出
            Mat srcBgr = new Mat();
            if (srcMat.channels() == 4) {
                Imgproc.cvtColor(srcMat, srcBgr, Imgproc.COLOR_RGBA2BGR);
            } else if (srcMat.channels() == 3) {
                srcBgr = srcMat.clone();
            } else {
                srcBgr = srcMat.clone();
            }

            // corners 按角度排序: [0]=topLeft, [1]=topRight, [2]=bottomRight, [3]=bottomLeft
            PointF topLeft = corners[0];
            PointF topRight = corners[1];
            PointF bottomRight = corners[2];
            PointF bottomLeft = corners[3];

            MatOfPoint2f srcPoints = new MatOfPoint2f(
                    new Point(topLeft.x, topLeft.y),
                    new Point(topRight.x, topRight.y),
                    new Point(bottomRight.x, bottomRight.y),
                    new Point(bottomLeft.x, bottomLeft.y)
            );

            // 计算目标尺寸
            float widthTop = distance(topLeft, topRight);
            float widthBottom = distance(bottomLeft, bottomRight);
            float targetWidth = (widthTop + widthBottom) / 2;

            float heightLeft = distance(topLeft, bottomLeft);
            float heightRight = distance(topRight, bottomRight);
            float targetHeight = (heightLeft + heightRight) / 2;

            int dstWidth = (int) targetWidth;
            int dstHeight = (int) targetHeight;

            if (dstWidth <= 0 || dstHeight <= 0) {
                Log.w(TAG, "extractAndCorrect: invalid target size " + dstWidth + "x" + dstHeight);
                srcMat.release();
                srcBgr.release();
                srcPoints.release();
                return null;
            }

            MatOfPoint2f dstPoints = new MatOfPoint2f(
                    new Point(0, 0),
                    new Point(dstWidth, 0),
                    new Point(dstWidth, dstHeight),
                    new Point(0, dstHeight)
            );

            Mat perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints);

            Mat dstBgr = new Mat();
            Imgproc.warpPerspective(srcBgr, dstBgr, perspectiveMatrix, new Size(dstWidth, dstHeight));

            // 转换回 RGBA 用于 Bitmap
            Mat dstRgba = new Mat();
            Imgproc.cvtColor(dstBgr, dstRgba, Imgproc.COLOR_BGR2RGBA);

            Bitmap correctedBitmap = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(dstRgba, correctedBitmap);

            srcMat.release();
            srcBgr.release();
            srcPoints.release();
            dstPoints.release();
            perspectiveMatrix.release();
            dstBgr.release();
            dstRgba.release();

            Log.d(TAG, "extractAndCorrect: success, output size " + dstWidth + "x" + dstHeight);
            return correctedBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract and correct image", e);
            return null;
        }
    }

    private float distance(PointF p1, PointF p2) {
        float dx = p2.x - p1.x;
        float dy = p2.y - p1.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public synchronized void release() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        isInitialized = false;
        Log.d(TAG, "LabelDetector released");
    }

    /**
     * 概率图统计特征
     */
    private static class ProbMapStats {
        float mean;
        float stdDev;
        float median;
        float min;
        float max;

        ProbMapStats(float mean, float stdDev, float median, float min, float max) {
            this.mean = mean;
            this.stdDev = stdDev;
            this.median = median;
            this.min = min;
            this.max = max;
        }
    }

    /**
     * 面单候选类（简化版）
     */
    private static class LabelCandidate {
        PointF[] corners;
        double area;
        double rectangularity;  // 矩形度
        double areaRatio;       // 面积比例
        double avgProbability;  // 平均概率
        double score;           // 综合评分

        LabelCandidate(PointF[] corners, double area) {
            this.corners = corners;
            this.area = area;
        }

        /**
         * 计算面单特征评分（简化版）
         */
        void calculateLabelScore(float[] probMap, int imgWidth, int imgHeight) {
            // 1. 矩形度评分（最重要）
            rectangularity = calculateRectangularity();

            // 2. 面积比例评分
            double totalArea = imgWidth * imgHeight;
            areaRatio = area / totalArea;
            double areaScore = calculateAreaScore();

            // 3. 概率图匹配评分
            avgProbability = calculateAverageProbability(probMap, imgWidth, imgHeight);

            // 4. 综合评分（权重优化）
            score = rectangularity * 0.5 +      // 矩形度最重要
                    areaScore * 0.3 +           // 面积合理性
                    avgProbability * 0.2;       // 概率匹配
        }

        private double calculateRectangularity() {
            if (corners.length != 4) return 0;

            // 计算四个角的角度
            double totalAngleError = 0;
            for (int i = 0; i < 4; i++) {
                PointF a = corners[(i + 3) % 4];
                PointF b = corners[i];
                PointF c = corners[(i + 1) % 4];

                double angle = calculateAngle(a, b, c);
                double error = Math.abs(angle - 90.0);
                totalAngleError += error;
            }

            // 角度误差转换为分数
            double avgError = totalAngleError / 4.0;
            double angleScore = Math.max(0, 1.0 - avgError / 30.0);  // 30度误差对应0分

            // 计算边长比例
            double[] sides = new double[4];
            for (int i = 0; i < 4; i++) {
                sides[i] = distance(corners[i], corners[(i + 1) % 4]);
            }

            // 对边应该相等
            double ratio1 = Math.min(sides[0], sides[2]) / Math.max(sides[0], sides[2]);
            double ratio2 = Math.min(sides[1], sides[3]) / Math.max(sides[1], sides[3]);
            double sideScore = (ratio1 + ratio2) / 2.0;

            return (angleScore + sideScore) / 2.0;
        }

        private double calculateAreaScore() {
            // 面单合理面积范围：5% - 60%
            if (areaRatio >= 0.05 && areaRatio <= 0.6) {
                return 1.0;
            } else if (areaRatio < 0.05) {
                return Math.max(0, areaRatio / 0.05);
            } else {
                return Math.max(0, 1.0 - (areaRatio - 0.6) / 0.4);
            }
        }

        private double calculateAverageProbability(float[] probMap, int imgWidth, int imgHeight) {
            // 创建多边形掩码
            Mat mask = Mat.zeros(imgHeight, imgWidth, CvType.CV_8U);
            Point[] points = new Point[4];
            for (int i = 0; i < 4; i++) {
                points[i] = new Point(corners[i].x, corners[i].y);
            }
            MatOfPoint pts = new MatOfPoint(points);
            List<MatOfPoint> contourList = new ArrayList<>();
            contourList.add(pts);
            Imgproc.fillPoly(mask, contourList, new Scalar(1));
            pts.release();

            // 计算掩码内的平均概率
            double sumProb = 0;
            int count = 0;

            for (int y = 0; y < imgHeight; y++) {
                for (int x = 0; x < imgWidth; x++) {
                    if (mask.get(y, x)[0] > 0) {
                        sumProb += probMap[y * imgWidth + x];
                        count++;
                    }
                }
            }

            mask.release();
            return count > 0 ? sumProb / count : 0;
        }

        private double calculateAngle(PointF a, PointF b, PointF c) {
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

        private double distance(PointF p1, PointF p2) {
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    /**
     * 智能阈值选择（专为面单优化）
     */
    private double selectOptimalThreshold(ProbMapStats stats, boolean isLiveAnalysis) {
        // 基于日志分析的优化策略
        double threshold;
        
        if (stats.median < 0.01) {
            // 大部分像素为0，使用低阈值
            threshold = Math.max(0.05, stats.mean * 0.8);
        } else {
            // 有明显的前景，使用中等阈值
            threshold = Math.max(0.08, Math.min(0.25, stats.mean + 0.5 * stats.stdDev));
        }
        
        // 实时模式使用更保守的阈值
        if (isLiveAnalysis) {
            threshold = Math.min(threshold, 0.15);
        }
        
        return threshold;
    }

    /**
     * 创建增强的二值掩码
     */
    private Mat createEnhancedMask(float[] probMap, double threshold) {
        // 1. 基础二值化
        Mat binaryMask = new Mat(outputHeight, outputWidth, CvType.CV_8UC1);
        byte[] data = new byte[outputWidth * outputHeight];
        int positivePixels = 0;
        
        for (int i = 0; i < probMap.length; i++) {
            if (probMap[i] >= threshold) {
                data[i] = (byte) 255;
                positivePixels++;
            } else {
                data[i] = 0;
            }
        }
        binaryMask.put(0, 0, data);
        
        double positiveRatio = (double) positivePixels / probMap.length;
        MLog.d(TAG, String.format(">> >> 二值化结果: 正像素=%d (%.1f%%)", positivePixels, positiveRatio * 100));

        // 2. 形态学增强
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        
        // 闭运算：连接断开的边缘
        Mat closed = new Mat();
        Imgproc.morphologyEx(binaryMask, closed, Imgproc.MORPH_CLOSE, kernel);
        
        // 开运算：去除小噪点
        Mat opened = new Mat();
        Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, kernel);
        
        binaryMask.release();
        closed.release();
        kernel.release();
        
        return opened;
    }

    /**
     * 提取面单候选
     */
    private List<LabelCandidate> extractLabelCandidates(Mat mask) {
        List<LabelCandidate> candidates = new ArrayList<>();
        
        // 1. 边缘检测
        Mat edges = new Mat();
        Imgproc.Canny(mask, edges, 50, 150);
        
        // 2. 查找轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        
        MLog.d(TAG, ">> >> 找到轮廓数量: " + contours.size());
        
        double minArea = outputWidth * outputHeight * 0.005;  // 最小0.5%面积
        double maxArea = outputWidth * outputHeight * 0.9;    // 最大90%面积
        
        for (int i = 0; i < contours.size(); i++) {
            MatOfPoint contour = contours.get(i);
            double area = Imgproc.contourArea(contour);
            
            // 面积过滤
            if (area < minArea || area > maxArea) {
                continue;
            }
            
            // 轮廓简化
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            double perimeter = Imgproc.arcLength(contour2f, true);
            
            // 尝试多个epsilon值
            double[] epsilons = {0.02, 0.03, 0.04, 0.05};
            MatOfPoint2f bestApprox = null;
            int bestVertices = Integer.MAX_VALUE;
            
            for (double epsilon : epsilons) {
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(contour2f, approx, epsilon * perimeter, true);
                int vertices = approx.rows();
                
                if (vertices == 4) {
                    if (bestApprox != null) bestApprox.release();
                    bestApprox = approx;
                    bestVertices = vertices;
                    break;
                } else if (vertices < bestVertices && vertices >= 4) {
                    if (bestApprox != null) bestApprox.release();
                    bestApprox = approx;
                    bestVertices = vertices;
                } else {
                    approx.release();
                }
            }
            
            // 检查是否得到4个顶点
            if (bestApprox == null || bestVertices != 4) {
                if (bestApprox != null) bestApprox.release();
                contour2f.release();
                continue;
            }
            
            Point[] points = bestApprox.toArray();
            PointF[] corners = new PointF[4];
            for (int j = 0; j < 4; j++) {
                corners[j] = new PointF((float) points[j].x, (float) points[j].y);
            }
            
            // 验证四边形有效性
            if (!isValidQuadrilateral(corners)) {
                bestApprox.release();
                contour2f.release();
                continue;
            }
            
            candidates.add(new LabelCandidate(corners, area));
            MLog.d(TAG, String.format(">> >> 添加候选: 面积=%.0f", area));
            
            bestApprox.release();
            contour2f.release();
        }
        
        edges.release();
        hierarchy.release();
        
        return candidates;
    }

    /**
     * 验证四边形有效性（宽松版）
     */
    private boolean isValidQuadrilateral(PointF[] corners) {
        if (corners.length != 4) {
            return false;
        }
        
        // 1. 检查是否有重复点（降低阈值）
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                double dist = Math.sqrt(Math.pow(corners[i].x - corners[j].x, 2) + 
                                      Math.pow(corners[i].y - corners[j].y, 2));
                if (dist < 3.0) {  // 从5.0降低到3.0
                    return false;
                }
            }
        }
        
        // 2. 检查面积是否为正（简单的凸性检查）
        double area = calculateQuadArea(corners);
        if (area <= 0) {
            return false;
        }
        
        return true;
    }

    /**
     * 计算四边形面积（使用鞋带公式）
     */
    private double calculateQuadArea(PointF[] corners) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += corners[i].x * corners[j].y;
            area -= corners[j].x * corners[i].y;
        }
        return Math.abs(area) / 2.0;
    }

    /**
     * 分析概率图统计特征
     */
    private ProbMapStats analyzeProbMapStatistics(float[] probMap) {
        float sum = 0;
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;

        for (float p : probMap) {
            sum += p;
            min = Math.min(min, p);
            max = Math.max(max, p);
        }

        float mean = sum / probMap.length;

        // 计算标准差
        float sumSqDiff = 0;
        for (float p : probMap) {
            float diff = p - mean;
            sumSqDiff += diff * diff;
        }
        float stdDev = (float) Math.sqrt(sumSqDiff / probMap.length);

        // 计算中位数
        float[] sorted = probMap.clone();
        Arrays.sort(sorted);
        float median = sorted[sorted.length / 2];

        return new ProbMapStats(mean, stdDev, median, min, max);
    }



    /**
     * 智能角点排序
     * 返回顺序：[topLeft, topRight, bottomRight, bottomLeft]
     */
    private PointF[] orderCornersCorrectly(PointF[] points) {
        if (points.length != 4) {
            return points;
        }

        // 1. 找到最左上的点（x+y最小）
        int topLeftIdx = 0;
        float minSum = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float sum = points[i].x + points[i].y;
            if (sum < minSum) {
                minSum = sum;
                topLeftIdx = i;
            }
        }

        // 2. 找到最右下的点（x+y最大）
        int bottomRightIdx = 0;
        float maxSum = Float.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            float sum = points[i].x + points[i].y;
            if (sum > maxSum) {
                maxSum = sum;
                bottomRightIdx = i;
            }
        }

        // 3. 剩余两个点，根据叉积判断
        List<Integer> remainingIndices = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (i != topLeftIdx && i != bottomRightIdx) {
                remainingIndices.add(i);
            }
        }

        PointF topLeft = points[topLeftIdx];
        PointF bottomRight = points[bottomRightIdx];

        int topRightIdx = -1;
        int bottomLeftIdx = -1;

        for (int idx : remainingIndices) {
            PointF p = points[idx];
            // 计算叉积：(bottomRight - topLeft) × (p - topLeft)
            double cross = (bottomRight.x - topLeft.x) * (p.y - topLeft.y) -
                    (bottomRight.y - topLeft.y) * (p.x - topLeft.x);

            if (cross < 0) {
                // 点在连线右侧 -> topRight
                topRightIdx = idx;
            } else {
                // 点在连线左侧 -> bottomLeft
                bottomLeftIdx = idx;
            }
        }

        // 4. 按顺序返回
        return new PointF[]{
                points[topLeftIdx],
                points[topRightIdx],
                points[bottomRightIdx],
                points[bottomLeftIdx]
        };
    }
}
