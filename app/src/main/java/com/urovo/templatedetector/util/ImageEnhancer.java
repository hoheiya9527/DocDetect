package com.urovo.templatedetector.util;

import android.media.Image;
import android.media.JetPlayer;
import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

/**
 * 图像增强工具类
 * <p>
 * 对图像进行灰度转换、CLAHE增强和锐化，提升条码识别率
 */
public class ImageEnhancer {

    private static final String TAG = "ImageEnhancer";

    // CLAHE实例（懒加载）
    private static CLAHE clahe;

    private ImageEnhancer() {
    }

    private static synchronized CLAHE getCLAHE() {
        if (clahe == null) {
            clahe = Imgproc.createCLAHE();
        }
        return clahe;
    }

    /**
     * 检测图像是否模糊（基于 Laplacian 方差）
     * <p>
     * 使用 Laplacian 算子计算图像的二阶导数，方差越大表示图像越清晰。
     *
     * @param yuvData YUV 数据（NV21 格式，只使用 Y 通道）
     * @param width   图像宽度
     * @param height  图像高度
     * @return Laplacian 方差值，越大越清晰
     */
    public static double calculateSharpness(byte[] yuvData, int width, int height) {
        if (yuvData == null || yuvData.length < width * height) {
            return 0;
        }

        Mat grayMat = null;
        Mat smallMat = null;
        Mat laplacianMat = null;
        MatOfDouble mean = null;
        MatOfDouble stddev = null;

        try {
            // 创建灰度 Mat（只用 Y 通道，即前 width*height 字节）
            grayMat = new Mat(height, width, CvType.CV_8UC1);
            grayMat.put(0, 0, yuvData, 0, width * height);

            // 缩小图像加速计算（1/4 尺寸）
            smallMat = new Mat();
            Imgproc.resize(grayMat, smallMat, new Size(width / 4.0, height / 4.0));

            // 计算 Laplacian
            laplacianMat = new Mat();
            Imgproc.Laplacian(smallMat, laplacianMat, CvType.CV_64F);

            // 计算方差
            mean = new MatOfDouble();
            stddev = new MatOfDouble();
            Core.meanStdDev(laplacianMat, mean, stddev);
            double variance = Math.pow(stddev.get(0, 0)[0], 2);

            return variance;

        } catch (Exception e) {
            Log.e(TAG, "calculateSharpness failed", e);
            return 0;
        } finally {
            if (grayMat != null) grayMat.release();
            if (smallMat != null) smallMat.release();
            if (laplacianMat != null) laplacianMat.release();
            if (mean != null) mean.release();
            if (stddev != null) stddev.release();
        }
    }

    /**
     * 检测图像是否模糊
     *
     * @param yuvData   YUV 数据
     * @param width     图像宽度
     * @param height    图像高度
     * @param threshold 模糊阈值（建议 100-200，越大要求越清晰）
     * @return true 表示图像清晰，false 表示模糊
     */
    public static boolean isSharp(byte[] yuvData, int width, int height, double threshold) {
        double sharpness = calculateSharpness(yuvData, width, height);
        boolean result = sharpness >= threshold;
        if (!result) {
            Log.d(TAG, "Image too blurry, sharpness=" + String.format("%.1f", sharpness) + ", threshold=" + threshold);
        }
        return result;
    }

    /**
     * 使用默认参数增强
     *
     * @param image YUV_420_888格式的Image
     * @return
     */
    public static byte[] enhance(Image image) {
        return enhance(image);
    }

    /**
     * 增强YUV_420_888格式的Image（用于预览分析）
     * <p>
     * 直接提取Y通道作为灰度图，应用CLAHE增强和锐化。
     * 适用于条码识别等场景。
     *
     * @param image  YUV_420_888格式的Image
     * @param params 增强参数，null时使用默认参数
     * @return 增强后的灰度数据；处理失败返回null
     */
    public static byte[] enhanceYuv(Image image, EnhanceParams params) {
        if (image == null || image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
            Log.e(TAG, "Invalid image format, expected YUV_420_888");
            return null;
        }

        long startTime = System.currentTimeMillis();
        Mat grayMat = null;
        Mat enhancedMat = null;

        try {
            // 1. 提取Y通道（灰度数据）
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = yPlane.getRowStride();

            // 创建灰度Mat
            grayMat = new Mat(height, width, CvType.CV_8UC1);

            // 处理行步长（rowStride可能大于width）
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

            // 2. 应用CLAHE增强 + 锐化
            EnhanceParams p = params != null ? params : new EnhanceParams();
            enhancedMat = applyEnhance(grayMat, p.claheClipLimit, p.claheTileSize, p.sharpenStrength);

            // 3. 提取结果数据
            byte[] result = new byte[width * height];
            enhancedMat.get(0, 0, result);

            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "YUV enhancement completed in " + duration + "ms");

            return result;

        } catch (Exception e) {
            Log.e(TAG, "YUV enhancement failed", e);
            return null;
        } finally {
            if (grayMat != null) grayMat.release();
            if (enhancedMat != null) enhancedMat.release();
        }
    }

    /**
     * 使用默认参数增强
     *
     * @param jpegData
     * @return
     */
    public static byte[] enhance(byte[] jpegData) {
        return enhance(jpegData);
    }

    /**
     * 增强图像
     *
     * @param jpegData 原始JPEG数据
     * @param params   增强参数
     * @return 增强后的JPEG数据；处理失败返回null
     */
    public static byte[] enhance(byte[] jpegData, EnhanceParams params) {
        long startTime = System.currentTimeMillis();

        MatOfByte inputMat = null;
        Mat grayMat = null;
        Mat enhancedMat = null;

        try {
            // 1. 直接解码JPEG为灰度图（跳过Bitmap中转，性能优化）
            inputMat = new MatOfByte(jpegData);
            grayMat = Imgcodecs.imdecode(inputMat, Imgcodecs.IMREAD_GRAYSCALE);

            if (grayMat.empty()) {
                Log.e(TAG, "Failed to decode JPEG");
                return null;
            }

            // 2. CLAHE增强 + 锐化
            EnhanceParams p = params != null ? params : new EnhanceParams();
            enhancedMat = applyEnhance(grayMat, p.claheClipLimit, p.claheTileSize, p.sharpenStrength);

            // 3. 编码为JPEG
            byte[] enhancedJpeg = encodeToJpeg(enhancedMat);
            if (enhancedJpeg == null) {
                Log.e(TAG, "Failed to encode enhanced image");
                return null;
            }

            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Enhancement completed in " + duration + "ms");

            return enhancedJpeg;

        } catch (Exception e) {
            Log.e(TAG, "Enhancement failed", e);
            return null;
        } finally {
            if (inputMat != null) inputMat.release();
            if (grayMat != null) grayMat.release();
            if (enhancedMat != null) enhancedMat.release();
        }
    }

    /**
     * 应用CLAHE增强和锐化
     */
    private static Mat applyEnhance(Mat gray, float clipLimit, int tileSize, float sharpenStrength) {
        Mat enhanced = null;
        Mat blurred = null;

        try {
            CLAHE claheInstance = getCLAHE();
            claheInstance.setClipLimit(clipLimit);
            claheInstance.setTilesGridSize(new Size(tileSize, tileSize));

            enhanced = new Mat();
            claheInstance.apply(gray, enhanced);

            // 锐化（Unsharp Mask）
            if (sharpenStrength > 0.01f) {
                blurred = new Mat();
                Imgproc.GaussianBlur(enhanced, blurred, new Size(0, 0), 1.5);
                Core.addWeighted(enhanced, 1.0 + sharpenStrength, blurred, -sharpenStrength, 0, enhanced);
            }

            return enhanced;

        } catch (Exception e) {
            Log.e(TAG, "Enhancement failed", e);
            if (enhanced != null) enhanced.release();
            return gray.clone();
        } finally {
            if (blurred != null) blurred.release();
        }
    }

    /**
     * 编码为JPEG
     */
    private static byte[] encodeToJpeg(Mat mat) {
        MatOfByte buffer = new MatOfByte();
        MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90);

        try {
            boolean success = Imgcodecs.imencode(".jpg", mat, buffer, params);
            if (!success) {
                Log.e(TAG, "Failed to encode JPEG");
                return null;
            }
            return buffer.toArray();
        } finally {
            buffer.release();
            params.release();
        }
    }

    /**
     * 释放资源
     */
    public static synchronized void release() {
        clahe = null;
    }

    /**
     * 增强参数
     */
    public static class EnhanceParams {
        /**
         * CLAHE clipLimit (1.0-4.0)
         */
        public float claheClipLimit = 2.0f;
        /**
         * CLAHE网格大小 (4-16)
         */
        public int claheTileSize = 16;
        /**
         * 锐化强度 (0-1)
         */
        public float sharpenStrength = 0.2f;

        public EnhanceParams() {
        }

        public static EnhanceParams createDefault() {
            return new EnhanceParams();
        }
    }
}
