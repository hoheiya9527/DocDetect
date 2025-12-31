package com.urovo.templatedetector.matcher;

import android.graphics.Bitmap;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.AKAZE;
import org.opencv.imgproc.Imgproc;

/**
 * 特征提取器
 * 使用 AKAZE 算法提取图像特征点和描述符
 */
public class FeatureExtractor {

    private static final String TAG = "FeatureExtractor";

    /** AKAZE 检测器实例 */
    private final AKAZE akaze;

    /** 是否已初始化 */
    private boolean initialized = false;

    public FeatureExtractor() {
        this.akaze = AKAZE.create();
        this.initialized = true;
        Log.d(TAG, "FeatureExtractor initialized with AKAZE");
    }

    /**
     * 特征提取结果
     */
    public static class FeatureData {
        /** 特征点 */
        private final MatOfKeyPoint keypoints;
        
        /** 描述符 */
        private final Mat descriptors;
        
        /** 特征点数量 */
        private final int count;

        public FeatureData(MatOfKeyPoint keypoints, Mat descriptors) {
            this.keypoints = keypoints;
            this.descriptors = descriptors;
            this.count = keypoints != null ? (int) keypoints.total() : 0;
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

        public boolean isValid() {
            return keypoints != null && descriptors != null 
                    && !keypoints.empty() && !descriptors.empty();
        }

        /**
         * 释放资源
         */
        public void release() {
            if (keypoints != null) {
                keypoints.release();
            }
            if (descriptors != null) {
                descriptors.release();
            }
        }
    }

    /**
     * 从 Bitmap 提取特征
     * @param bitmap 输入图像
     * @return 特征数据，失败返回 null
     */
    public FeatureData extract(Bitmap bitmap) {
        if (!initialized || bitmap == null) {
            Log.e(TAG, "extract: invalid input, initialized=" + initialized + ", bitmap=" + (bitmap != null));
            return null;
        }

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

            return extractFromGray(grayMat);

        } catch (Exception e) {
            Log.e(TAG, "extract from Bitmap failed", e);
            return null;
        } finally {
            if (colorMat != null) colorMat.release();
            if (grayMat != null) grayMat.release();
        }
    }

    /**
     * 从彩色 Mat 提取特征（自动转灰度）
     * @param colorMat 彩色图像 Mat（RGB/RGBA/BGR）
     * @return 特征数据，失败返回 null
     */
    public FeatureData extractFromColorMat(Mat colorMat) {
        if (!initialized || colorMat == null || colorMat.empty()) {
            Log.e(TAG, "extractFromColorMat: invalid input");
            return null;
        }

        Mat grayMat = null;
        try {
            grayMat = new Mat();
            int channels = colorMat.channels();
            if (channels == 4) {
                Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGBA2GRAY);
            } else if (channels == 3) {
                Imgproc.cvtColor(colorMat, grayMat, Imgproc.COLOR_RGB2GRAY);
            } else if (channels == 1) {
                grayMat = colorMat; // 已经是灰度图
            } else {
                Log.e(TAG, "extractFromColorMat: unsupported channels: " + channels);
                return null;
            }

            return extractFromGray(grayMat);

        } catch (Exception e) {
            Log.e(TAG, "extractFromColorMat failed", e);
            return null;
        } finally {
            if (grayMat != null && grayMat != colorMat) {
                grayMat.release();
            }
        }
    }

    /**
     * 从灰度 Mat 提取特征
     * @param grayMat 灰度图像
     * @return 特征数据，失败返回 null
     */
    public FeatureData extractFromGray(Mat grayMat) {
        if (!initialized || grayMat == null || grayMat.empty()) {
            Log.e(TAG, "extractFromGray: invalid input");
            return null;
        }

        try {
            MatOfKeyPoint keypoints = new MatOfKeyPoint();
            Mat descriptors = new Mat();

            // 检测特征点并计算描述符
            akaze.detectAndCompute(grayMat, new Mat(), keypoints, descriptors);

            int count = (int) keypoints.total();
            Log.d(TAG, "extractFromGray: detected " + count + " keypoints");

            if (count == 0) {
                keypoints.release();
                descriptors.release();
                return null;
            }

            return new FeatureData(keypoints, descriptors);

        } catch (Exception e) {
            Log.e(TAG, "extractFromGray failed", e);
            return null;
        }
    }

    /**
     * @deprecated 使用 {@link #extractFromGray(Mat)} 代替
     */
    @Deprecated
    public FeatureData extract(Mat grayMat) {
        return extractFromGray(grayMat);
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 释放资源
     */
    public void release() {
        initialized = false;
        Log.d(TAG, "FeatureExtractor released");
    }
}
