package com.urovo.templatedetector.detector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.urovo.templatedetector.model.DetectionResult;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
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

import com.urovo.templatedetector.util.PerformanceTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 标签检测器
 * 参考: https://github.com/pynicolas/FairScan
 */
public class LabelDetector {

    private static final String TAG = "LabelDetector";
    private static final String MODEL_PATH = "model/fairscan-segmentation-model.tflite";

    // 最小四边形面积比例
    private static final double MIN_QUAD_AREA_RATIO = 0.02;

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
     * 检测标签区域
     */
    public DetectionResult detect(Bitmap bitmap) {
        Log.d(TAG, "detect(Bitmap) called, initialized=" + isInitialized + ", bitmap=" + (bitmap != null));
        if (!isInitialized || bitmap == null) {
            Log.w(TAG, "Detection skipped: initialized=" + isInitialized + ", bitmap=" + (bitmap != null));
            return DetectionResult.notDetected();
        }

        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        Log.d(TAG, "Input image size: " + originalWidth + "x" + originalHeight);

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.DETECTION)) {
            // 1. 使用 TensorImage 加载并预处理图像
            TensorImage tensorImage = new TensorImage(DataType.FLOAT32);
            tensorImage.load(bitmap);
            TensorImage processedImage = imageProcessor.process(tensorImage);

            // 2. 准备输出缓冲区
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(4 * outputHeight * outputWidth);
            outputBuffer.order(ByteOrder.nativeOrder());
            outputBuffer.rewind();

            // 3. 执行推理
            interpreter.run(processedImage.getBuffer(), outputBuffer);

            // 4. 提取概率图
            float[] probMap = extractProbMap(outputBuffer);

            // 5. 检测四边形
            PointF[] quad = detectDocumentQuad(probMap, true);

            if (quad == null) {
                return DetectionResult.notDetected();
            }

            // 6. 缩放到原始图像尺寸
            PointF[] scaledQuad = scaleQuad(quad, outputWidth, outputHeight, originalWidth, originalHeight);

            // 7. 向外扩展%，补偿模型检测边缘不完整（用于显示）
            PointF[] expandedQuad = expandQuad(scaledQuad, 0.05f, originalWidth, originalHeight);

            // 计算边界框（使用扩展后的坐标）
            RectF boundingBox = calculateBoundingBox(expandedQuad);

            // 计算置信度
            float confidence = calculateConfidence(probMap, quad);

            // 计算旋转角度
            float rotationAngle = calculateRotationAngle(expandedQuad);

            return new DetectionResult.Builder()
                    .setDetected(true)
                    .setCornerPoints(expandedQuad)           // 扩展后的坐标（用于显示）
                    .setOriginalCornerPoints(scaledQuad)     // 原始坐标（用于裁剪）
                    .setBoundingBox(boundingBox)
                    .setConfidence(confidence)
                    .setRotationAngle(rotationAngle)
                    .build();

        } catch (Exception e) {
            Log.e(TAG, "Detection failed", e);
            return DetectionResult.notDetected();
        }
    }


    /**
     * 从ImageProxy检测
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

        Bitmap bitmap = imageProxyToBitmap(imageProxy, rotationDegrees);
        if (bitmap == null) {
            Log.w(TAG, "Failed to convert ImageProxy to Bitmap");
            return DetectionResult.notDetected();
        }

        Log.d(TAG, "Converted to Bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        DetectionResult result = detect(bitmap);
        bitmap.recycle();
        Log.d(TAG, "Detection result: detected=" + result.isDetected());
        return result;
    }

    /**
     * 从ImageProxy检测（无旋转）
     *
     * @deprecated 使用 detect(ImageProxy, int) 代替
     */
    public DetectionResult detect(ImageProxy imageProxy) {
        return detect(imageProxy, 0);
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
     * 检测文档四边形
     */
    private PointF[] detectDocumentQuad(float[] probMap, boolean isLiveAnalysis) {
        // 创建掩码 Mat
        Mat mask = probMapToMat(probMap);

        // 尝试从掩码中找到最大轮廓
        BiggestContourResult contourResult = biggestContour(mask);

        PointF[] vertices = null;

        // 如果找到了4个顶点的轮廓且面积足够大
        if (contourResult.contour != null &&
                contourResult.contour.total() == 4 &&
                contourResult.area > outputWidth * outputHeight * MIN_QUAD_AREA_RATIO) {

            Point[] points = contourResult.contour.toArray();
            vertices = createQuad(points);
        } else {
            // Fallback 1: 自适应阈值
            double[] thresholds = isLiveAnalysis
                    ? new double[]{25.0, 50.0, 75.0}
                    : generateThresholds(0.2, 0.8, 13);

            vertices = detectDocumentQuadFromProbmap(mask, thresholds);

            // Fallback 2: 如果还是没找到，但有多于4个顶点的轮廓，尝试最小外接矩形
            if (vertices == null && contourResult.contour != null && contourResult.contour.total() > 4) {
                vertices = minAreaRect(contourResult.contour);
            }
        }

        // 释放资源
        mask.release();
        if (contourResult.contour != null) {
            contourResult.contour.release();
        }

        return vertices;
    }

    /**
     * 将概率图转换为 Mat（二值掩码）
     */
    private Mat probMapToMat(float[] probMap) {
        Mat mask = new Mat(outputHeight, outputWidth, CvType.CV_8UC1);
        byte[] data = new byte[outputWidth * outputHeight];

        for (int i = 0; i < probMap.length; i++) {
            data[i] = probMap[i] >= 0.5f ? (byte) 255 : 0;
        }

        mask.put(0, 0, data);
        return mask;
    }

    /**
     * 找到最大轮廓
     */
    private BiggestContourResult biggestContour(Mat mat) {
        // refineMask
        Mat refinedMask = refineMask(mat);

        // 高斯模糊
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(refinedMask, blurred, new Size(5, 5), 0);

        // Canny 边缘检测
        Mat edges = new Mat();
        Imgproc.Canny(blurred, edges, 75, 200);

        // 查找轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        // 释放临时资源
        refinedMask.release();
        blurred.release();
        edges.release();
        hierarchy.release();

        MatOfPoint2f biggest = null;
        double maxArea = 0;

        for (MatOfPoint contour : contours) {
            MatOfPoint2f contour2f = new MatOfPoint2f(contour.toArray());
            double peri = Imgproc.arcLength(contour2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true);

            double area = Math.abs(Imgproc.contourArea(approx));
            if (area > maxArea) {
                maxArea = area;
                if (biggest != null) {
                    biggest.release();
                }
                biggest = approx;
            } else {
                approx.release();
            }
            contour2f.release();
            contour.release();
        }

        return new BiggestContourResult(biggest, maxArea);
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
     * 从概率图中使用自适应阈值检测四边形
     */
    private PointF[] detectDocumentQuadFromProbmap(Mat probmap, double[] thresholds) {
        // 转换为 8 位
        Mat probmapU8 = new Mat();
        probmap.convertTo(probmapU8, CvType.CV_8U, 255.0);

        // 高斯模糊
        Mat probmapSmooth = new Mat();
        Imgproc.GaussianBlur(probmapU8, probmapSmooth, new Size(3, 3), 0);

        double bestScore = 0;
        PointF[] bestQuad = null;

        // 1) Otsu
        Mat otsu = new Mat();
        Imgproc.threshold(probmapSmooth, otsu, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
        PointF[] quad = findQuadFromBinaryMask(otsu);
        if (quad != null) {
            double score = scoreQuadAgainstProbmap(quad, probmap);
            if (score > bestScore) {
                bestScore = score;
                bestQuad = quad;
            }
        }
        otsu.release();

        // 2) 阈值扫描
        for (double thr : thresholds) {
            Mat bin = new Mat();
            Imgproc.threshold(probmapSmooth, bin, thr * 255, 255, Imgproc.THRESH_BINARY);

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();

            quad = findQuadFromBinaryMask(bin);
            if (quad != null) {
                double score = scoreQuadAgainstProbmap(quad, probmap);
                if (score > bestScore) {
                    bestScore = score;
                    bestQuad = quad;
                }
            }
            bin.release();
        }

        probmapU8.release();
        probmapSmooth.release();

        return bestQuad;
    }

    /**
     * 从二值掩码中找到四边形
     */
    private PointF[] findQuadFromBinaryMask(Mat binMask) {
        Mat blurred = new Mat();
        Imgproc.GaussianBlur(binMask, blurred, new Size(5, 5), 0);

        Mat edges = new Mat();
        Imgproc.Canny(blurred, edges, 75, 200);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(edges, contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

        blurred.release();
        edges.release();

        MatOfPoint2f biggest = null;
        double maxArea = 0;

        for (MatOfPoint cnt : contours) {
            MatOfPoint2f cnt2f = new MatOfPoint2f(cnt.toArray());
            double peri = Imgproc.arcLength(cnt2f, true);
            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(cnt2f, approx, 0.02 * peri, true);

            // 只接受恰好 4 个顶点的轮廓
            if (approx.rows() == 4) {
                double area = Math.abs(Imgproc.contourArea(approx));
                if (area > maxArea) {
                    maxArea = area;
                    if (biggest != null) {
                        biggest.release();
                    }
                    biggest = approx;
                } else {
                    approx.release();
                }
            } else {
                approx.release();
            }
            cnt2f.release();
            cnt.release();
        }

        double totalArea = binMask.rows() * binMask.cols();
        if (maxArea > totalArea * MIN_QUAD_AREA_RATIO && biggest != null) {
            Point[] points = biggest.toArray();
            biggest.release();
            return createQuad(points);
        }

        if (biggest != null) {
            biggest.release();
        }
        return null;
    }

    /**
     * 计算四边形与概率图的匹配分数
     */
    private double scoreQuadAgainstProbmap(PointF[] quad, Mat probmap) {
        // 创建多边形掩码
        Mat mask = Mat.zeros(probmap.size(), CvType.CV_8U);
        Point[] points = new Point[4];
        for (int i = 0; i < 4; i++) {
            points[i] = new Point(quad[i].x, quad[i].y);
        }
        MatOfPoint pts = new MatOfPoint(points);
        List<MatOfPoint> contourList = new ArrayList<>();
        contourList.add(pts);
        Imgproc.fillPoly(mask, contourList, new Scalar(1));
        pts.release();

        // 转换为浮点
        Mat maskFloat = new Mat();
        mask.convertTo(maskFloat, CvType.CV_32F);
        mask.release();

        // 计算分数
        Mat probFloat = new Mat();
        probmap.convertTo(probFloat, CvType.CV_32F);

        Mat masked = new Mat();
        Core.multiply(probFloat, maskFloat, masked);

        double sumMasked = Core.sumElems(masked).val[0];
        double sumMask = Core.sumElems(maskFloat).val[0];
        double meanProb = sumMask > 0 ? sumMasked / sumMask : 0;
        double areaRatio = sumMask / (probmap.rows() * probmap.cols());

        maskFloat.release();
        probFloat.release();
        masked.release();

        return meanProb * (0.7 + 0.3 * areaRatio);
    }

    /**
     * 创建四边形（按角度排序）
     * 与 FairScan 的 createQuad 一致
     * 排序后: [0]=topLeft, [1]=topRight, [2]=bottomRight, [3]=bottomLeft
     */
    private PointF[] createQuad(Point[] vertices) {
        if (vertices.length != 4) {
            return null;
        }

        // 计算质心
        double cx = 0, cy = 0;
        for (Point p : vertices) {
            cx += p.x;
            cy += p.y;
        }
        cx /= 4;
        cy /= 4;

        // 按角度排序（从质心出发，逆时针）
        final double fcx = cx;
        final double fcy = cy;
        Point[] sorted = vertices.clone();
        Arrays.sort(sorted, Comparator.comparingDouble(p -> Math.atan2(p.y - fcy, p.x - fcx)));

        Log.d(TAG, "createQuad: centroid=(" + cx + ", " + cy + ")");
        for (int i = 0; i < 4; i++) {
            double angle = Math.toDegrees(Math.atan2(sorted[i].y - fcy, sorted[i].x - fcx));
            Log.d(TAG, "createQuad: sorted[" + i + "]=(" + sorted[i].x + ", " + sorted[i].y + "), angle=" + angle);
        }

        // 返回：按角度排序后的四个点
        return new PointF[]{
                new PointF((float) sorted[0].x, (float) sorted[0].y),
                new PointF((float) sorted[1].x, (float) sorted[1].y),
                new PointF((float) sorted[2].x, (float) sorted[2].y),
                new PointF((float) sorted[3].x, (float) sorted[3].y)
        };
    }

    /**
     * 最小外接矩形
     */
    private PointF[] minAreaRect(MatOfPoint2f contour) {
        org.opencv.core.RotatedRect rect = Imgproc.minAreaRect(contour);
        Point[] points = new Point[4];
        rect.points(points);
        return createQuad(points);
    }

    /**
     * 生成阈值数组
     */
    private double[] generateThresholds(double start, double end, int count) {
        double[] thresholds = new double[count];
        double step = (end - start) / (count - 1);
        for (int i = 0; i < count; i++) {
            thresholds[i] = start + i * step;
        }
        return thresholds;
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
     * 提取并校正标签图像
     * 按照 FairScan 的 extractDocument 实现
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
            // 与 FairScan 的 Quad(topLeft, topRight, bottomRight, bottomLeft) 一致
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

            // 计算目标尺寸（与 FairScan 一致）
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
     * 最大轮廓结果
     */
    private static class BiggestContourResult {
        final MatOfPoint2f contour;
        final double area;

        BiggestContourResult(MatOfPoint2f contour, double area) {
            this.contour = contour;
            this.area = area;
        }
    }
}
