package com.urovo.templatedetector.util;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.urovo.templatedetector.model.CameraSettings;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

/**
 * 自适应图像增强器
 * 根据图像质量和配置智能选择增强策略
 */
public class AdaptiveEnhancer {

    private static final String TAG = "AdaptiveEnhancer";

    /**
     * 图像质量评估结果
     */
    public static class QualityMetrics {
        private final double sharpness;
        private final double contrast;
        private final double brightness;

        public QualityMetrics(double sharpness, double contrast, double brightness) {
            this.sharpness = sharpness;
            this.contrast = contrast;
            this.brightness = brightness;
        }

        public double getSharpness() { return sharpness; }
        public double getContrast() { return contrast; }
        public double getBrightness() { return brightness; }

        @Override
        public String toString() {
            return String.format("QualityMetrics{sharpness=%.1f, contrast=%.3f, brightness=%.1f}", 
                sharpness, contrast, brightness);
        }
    }

    /**
     * 评估ImageProxy图像质量
     */
    public static QualityMetrics assessQuality(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy == null) {
            return new QualityMetrics(0, 0, 0);
        }

        // 获取Image对象
        Image image = imageProxy.getImage();
        if (image == null) {
            return new QualityMetrics(0, 0, 0);
        }

        return assessQuality(image);
    }

    /**
     * 评估YUV图像质量
     */
    public static QualityMetrics assessQuality(Image image) {
        if (image == null || image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
            return new QualityMetrics(0, 0, 0);
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.QUALITY_ASSESSMENT)) {
            // 提取Y通道
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = yPlane.getRowStride();

            // 创建灰度Mat
            Mat grayMat = new Mat(height, width, CvType.CV_8UC1);
            
            if (rowStride == width) {
                byte[] yData = new byte[width * height];
                yBuffer.get(yData);
                grayMat.put(0, 0, yData);
            } else {
                byte[] rowData = new byte[rowStride];
                for (int row = 0; row < height; row++) {
                    yBuffer.position(row * rowStride);
                    yBuffer.get(rowData, 0, Math.min(rowStride, yBuffer.remaining()));
                    grayMat.put(row, 0, rowData, 0, width);
                }
            }

            // 缩小图像加速计算
            Mat smallMat = new Mat();
            Imgproc.resize(grayMat, smallMat, new Size(width / 4.0, height / 4.0));

            // 计算清晰度（Laplacian方差）
            double sharpness = calculateSharpness(smallMat);

            // 计算对比度（标准差）
            double contrast = calculateContrast(smallMat);

            // 计算亮度（均值）
            double brightness = calculateBrightness(smallMat);

            grayMat.release();
            smallMat.release();

            return new QualityMetrics(sharpness, contrast, brightness);

        } catch (Exception e) {
            Log.e(TAG, "Failed to assess image quality", e);
            return new QualityMetrics(0, 0, 0);
        }
    }

    /**
     * 评估Bitmap图像质量
     */
    public static QualityMetrics assessQuality(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return new QualityMetrics(0, 0, 0);
        }

        Mat grayMat = null;
        Mat smallMat = null;

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.QUALITY_ASSESSMENT)) {
            // 转换为灰度Mat
            grayMat = new Mat();
            Utils.bitmapToMat(bitmap, grayMat);
            Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_RGBA2GRAY);

            // 缩小图像加速计算
            smallMat = new Mat();
            Imgproc.resize(grayMat, smallMat, new Size(bitmap.getWidth() / 4.0, bitmap.getHeight() / 4.0));

            double sharpness = calculateSharpness(smallMat);
            double contrast = calculateContrast(smallMat);
            double brightness = calculateBrightness(smallMat);

            return new QualityMetrics(sharpness, contrast, brightness);

        } catch (Exception e) {
            Log.e(TAG, "Failed to assess bitmap quality", e);
            return new QualityMetrics(0, 0, 0);
        } finally {
            if (grayMat != null) grayMat.release();
            if (smallMat != null) smallMat.release();
        }
    }

    /**
     * 完整增强（使用默认参数）
     */
    public static Bitmap fullEnhance(Bitmap input) {
        if (input == null || input.isRecycled()) {
            return null;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.FULL_ENHANCE)) {
            // 使用ImageEnhancer的默认参数
            byte[] jpegData = bitmapToJpeg(input);
            if (jpegData == null) {
                return input;
            }

            byte[] enhancedData = ImageEnhancer.enhance(jpegData);
            if (enhancedData == null) {
                return input;
            }

            return jpegToBitmap(enhancedData);

        } catch (Exception e) {
            Log.e(TAG, "Full enhancement failed", e);
            return input;
        }
    }

    /**
     * 智能增强（根据配置开关决定是否增强）
     */
    public static Bitmap smartEnhance(Bitmap input, CameraSettings.EnhanceConfig config) {
        if (input == null || input.isRecycled() || config == null) {
            return input;
        }

        // 检查增强开关
        if (!config.isEnableEnhance()) {
            return input;
        }

        Log.d(TAG, "Applying enhancement with default parameters");
        return fullEnhance(input);
    }

    /**
     * 计算图像清晰度
     */
    private static double calculateSharpness(Mat grayMat) {
        Mat laplacianMat = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();

        try {
            Imgproc.Laplacian(grayMat, laplacianMat, CvType.CV_64F);
            Core.meanStdDev(laplacianMat, mean, stddev);
            return Math.pow(stddev.get(0, 0)[0], 2);
        } finally {
            laplacianMat.release();
            mean.release();
            stddev.release();
        }
    }

    /**
     * 计算图像对比度
     */
    private static double calculateContrast(Mat grayMat) {
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();

        try {
            Core.meanStdDev(grayMat, mean, stddev);
            return stddev.get(0, 0)[0] / 255.0; // 归一化到[0,1]
        } finally {
            mean.release();
            stddev.release();
        }
    }

    /**
     * 计算图像亮度
     */
    private static double calculateBrightness(Mat grayMat) {
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();

        try {
            Core.meanStdDev(grayMat, mean, stddev);
            return mean.get(0, 0)[0];
        } finally {
            mean.release();
            stddev.release();
        }
    }

    /**
     * Bitmap转JPEG字节数组
     */
    private static byte[] bitmapToJpeg(Bitmap bitmap) {
        try {
            java.io.ByteArrayOutputStream stream = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
            return stream.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert bitmap to JPEG", e);
            return null;
        }
    }

    /**
     * JPEG字节数组转Bitmap
     */
    private static Bitmap jpegToBitmap(byte[] jpegData) {
        try {
            return android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert JPEG to bitmap", e);
            return null;
        }
    }
}