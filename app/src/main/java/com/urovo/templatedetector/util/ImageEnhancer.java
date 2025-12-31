package com.urovo.templatedetector.util;

import android.media.Image;
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

    private ImageEnhancer() {
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
     * 增强YUV_420_888格式的Image（用于预览分析）
     * <p>
     * 直接提取Y通道作为灰度图，应用CLAHE增强和锐化。
     * 适用于条码识别等场景。
     *
     * @param image YUV_420_888格式的Image
     * @return 增强后的灰度数据；处理失败返回null
     */
    public static byte[] enhanceYuv(Image image) {
        return enhanceYuv(image, null);
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
     * @param jpegData 原始JPEG数据
     * @return 增强后的JPEG数据；处理失败返回null
     */
    public static byte[] enhance(byte[] jpegData) {
        return enhance(jpegData, null);
    }

    /**
     * 增强图像
     *
     * @param jpegData 原始JPEG数据
     * @param params   增强参数，null时使用默认参数
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
     * 注意：每次调用创建独立的 CLAHE 实例，确保线程安全
     */
    private static Mat applyEnhance(Mat gray, float clipLimit, int tileSize, float sharpenStrength) {
        Mat enhanced = null;
        Mat blurred = null;
        CLAHE claheInstance = null;

        try {
            // 每次创建独立的 CLAHE 实例，避免多线程并发问题
            claheInstance = Imgproc.createCLAHE();
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
            // 释放 CLAHE 实例，避免内存泄漏
            // 注意：OpenCV 的 CLAHE 继承自 Algorithm，没有显式的 release 方法
            // 但 Java 对象会被 GC 回收，native 资源会在析构时释放
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
     * 从ImageProxy提取YUV数据
     */
    public static byte[] extractYuv(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy == null) {
            return null;
        }
        
        try {
            androidx.camera.core.ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            
            // Y平面
            ByteBuffer yBuffer = planes[0].getBuffer();
            int yRowStride = planes[0].getRowStride();
            
            // UV平面
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();
            
            // NV21格式：Y + VU交错
            int ySize = width * height;
            int uvSize = width * height / 2;
            byte[] nv21 = new byte[ySize + uvSize];
            
            // 复制Y平面
            if (yRowStride == width) {
                yBuffer.get(nv21, 0, ySize);
            } else {
                for (int row = 0; row < height; row++) {
                    yBuffer.position(row * yRowStride);
                    yBuffer.get(nv21, row * width, width);
                }
            }
            
            // 复制UV平面（转为NV21的VU交错格式）
            int uvIndex = ySize;
            int uvHeight = height / 2;
            int uvWidth = width / 2;
            
            for (int row = 0; row < uvHeight; row++) {
                for (int col = 0; col < uvWidth; col++) {
                    int uvOffset = row * uvRowStride + col * uvPixelStride;
                    nv21[uvIndex++] = vBuffer.get(uvOffset); // V
                    nv21[uvIndex++] = uBuffer.get(uvOffset); // U
                }
            }
            
            return nv21;
        } catch (Exception e) {
            Log.e(TAG, "extractYuv failed", e);
            return null;
        }
    }

    /**
     * 从 RGBA_8888 格式的 ImageProxy 提取 RGBA 数据
     * 用于 ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 格式
     */
    public static byte[] extractRgba(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy == null) {
            return null;
        }
        
        try {
            androidx.camera.core.ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
            if (planes.length < 1) {
                Log.e(TAG, "extractRgba: no planes available");
                return null;
            }
            
            ByteBuffer buffer = planes[0].getBuffer();
            int rowStride = planes[0].getRowStride();
            int pixelStride = planes[0].getPixelStride();
            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();
            
            byte[] rgba = new byte[width * height * 4];
            
            if (rowStride == width * pixelStride) {
                // 无填充，直接复制
                buffer.get(rgba);
            } else {
                // 有填充，逐行复制
                for (int row = 0; row < height; row++) {
                    buffer.position(row * rowStride);
                    buffer.get(rgba, row * width * 4, width * 4);
                }
            }
            
            return rgba;
        } catch (Exception e) {
            Log.e(TAG, "extractRgba failed", e);
            return null;
        }
    }

    /**
     * RGBA 数据转彩色 Mat（BGR 格式，用于 OpenCV 处理）
     */
    public static Mat rgbaToColorMat(byte[] rgbaData, int width, int height) {
        if (rgbaData == null || rgbaData.length < width * height * 4) {
            return null;
        }
        
        Mat rgbaMat = new Mat(height, width, CvType.CV_8UC4);
        rgbaMat.put(0, 0, rgbaData);
        
        Mat bgrMat = new Mat();
        Imgproc.cvtColor(rgbaMat, bgrMat, Imgproc.COLOR_RGBA2BGR);
        rgbaMat.release();
        
        return bgrMat;
    }

    /**
     * YUV数据转灰度Mat（只取Y通道）
     */
    public static Mat yuvToGrayMat(byte[] yuvData, int width, int height) {
        if (yuvData == null || yuvData.length < width * height) {
            return null;
        }
        
        Mat grayMat = new Mat(height, width, CvType.CV_8UC1);
        grayMat.put(0, 0, yuvData, 0, width * height);
        return grayMat;
    }

    /**
     * YUV数据转彩色Mat（BGR格式）
     */
    public static Mat yuvToColorMat(byte[] yuvData, int width, int height) {
        if (yuvData == null || yuvData.length < width * height * 3 / 2) {
            return null;
        }
        
        Mat yuvMat = new Mat(height + height / 2, width, CvType.CV_8UC1);
        yuvMat.put(0, 0, yuvData);
        
        Mat bgrMat = new Mat();
        Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_NV21);
        yuvMat.release();
        
        return bgrMat;
    }

    /**
     * Mat转Bitmap（用于显示）
     */
    public static android.graphics.Bitmap matToBitmap(Mat mat) {
        if (mat == null || mat.empty()) {
            return null;
        }
        
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                mat.cols(), mat.rows(), android.graphics.Bitmap.Config.ARGB_8888);
        
        // 根据通道数转换
        if (mat.channels() == 1) {
            // 灰度 → RGBA
            Mat rgba = new Mat();
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA);
            org.opencv.android.Utils.matToBitmap(rgba, bitmap);
            rgba.release();
        } else if (mat.channels() == 3) {
            // BGR → RGBA
            Mat rgba = new Mat();
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA);
            org.opencv.android.Utils.matToBitmap(rgba, bitmap);
            rgba.release();
        } else if (mat.channels() == 4) {
            org.opencv.android.Utils.matToBitmap(mat, bitmap);
        }
        
        return bitmap;
    }

    /**
     * 增强Mat（灰度图）
     */
    public static Mat enhanceMat(Mat grayMat) {
        return enhanceMat(grayMat, null);
    }

    /**
     * 增强Mat（灰度图）
     */
    public static Mat enhanceMat(Mat grayMat, EnhanceParams params) {
        if (grayMat == null || grayMat.empty()) {
            return grayMat;
        }
        
        // 如果不是灰度图，先转换
        Mat gray;
        if (grayMat.channels() != 1) {
            gray = new Mat();
            Imgproc.cvtColor(grayMat, gray, Imgproc.COLOR_BGR2GRAY);
        } else {
            gray = grayMat;
        }
        
        EnhanceParams p = params != null ? params : new EnhanceParams();
        Mat enhanced = applyEnhance(gray, p.claheClipLimit, p.claheTileSize, p.sharpenStrength);
        
        if (gray != grayMat) {
            gray.release();
        }
        
        return enhanced;
    }

    /**
     * 旋转Mat
     */
    public static Mat rotateMat(Mat mat, int rotationDegrees) {
        if (mat == null || rotationDegrees == 0) {
            return mat;
        }
        
        Mat rotated = new Mat();
        switch (rotationDegrees) {
            case 90:
                Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE);
                break;
            case 180:
                Core.rotate(mat, rotated, Core.ROTATE_180);
                break;
            case 270:
                Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE);
                break;
            default:
                return mat;
        }
        return rotated;
    }

    /**
     * 释放资源
     * 注意：CLAHE 实例现在是每次调用时创建，无需全局释放
     */
    public static void release() {
        // 不再需要释放静态 CLAHE 实例
    }


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

    /**
     * 增强YUV数据（直接处理YUV，保持格式）
     *
     * @param yuvData YUV数据
     * @param width   图像宽度
     * @param height  图像高度
     * @return 增强后的YUV数据，失败返回原数据
     */
    public static byte[] enhanceYuvData(byte[] yuvData, int width, int height) {
        return enhanceYuvData(yuvData, width, height, null);
    }

    /**
     * 增强YUV数据（直接处理YUV，保持格式）
     *
     * @param yuvData YUV数据
     * @param width   图像宽度
     * @param height  图像高度
     * @param params  增强参数，null时使用默认参数
     * @return 增强后的YUV数据，失败返回原数据
     */
    public static byte[] enhanceYuvData(byte[] yuvData, int width, int height, EnhanceParams params) {
        if (yuvData == null || yuvData.length < width * height) {
            return yuvData;
        }

        long startTime = System.currentTimeMillis();
        Mat yMat = null;
        Mat enhancedYMat = null;

        try {
            // 1. 提取Y通道数据
            int ySize = width * height;
            byte[] yData = new byte[ySize];
            System.arraycopy(yuvData, 0, yData, 0, ySize);

            // 2. 创建Y通道Mat
            yMat = new Mat(height, width, CvType.CV_8UC1);
            yMat.put(0, 0, yData);

            // 3. 增强Y通道
            EnhanceParams p = params != null ? params : new EnhanceParams();
            enhancedYMat = applyEnhance(yMat, p.claheClipLimit, p.claheTileSize, p.sharpenStrength);

            // 4. 提取增强后的Y数据
            byte[] enhancedYData = new byte[ySize];
            enhancedYMat.get(0, 0, enhancedYData);

            // 5. 构建增强后的YUV数据（替换Y通道，保持UV通道不变）
            byte[] result = new byte[yuvData.length];
            System.arraycopy(enhancedYData, 0, result, 0, ySize);  // 复制增强后的Y
            System.arraycopy(yuvData, ySize, result, ySize, yuvData.length - ySize);  // 保持原UV

            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "YUV data enhancement completed in " + duration + "ms");

            return result;

        } catch (Exception e) {
            Log.e(TAG, "YUV data enhancement failed", e);
            return yuvData;
        } finally {
            if (yMat != null) yMat.release();
            if (enhancedYMat != null) enhancedYMat.release();
        }
    }
}
