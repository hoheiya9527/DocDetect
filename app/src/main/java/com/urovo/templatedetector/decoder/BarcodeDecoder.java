package com.urovo.templatedetector.decoder;

import android.graphics.Bitmap;
import android.media.Image;

import java.util.List;

/**
 * 条码解码器接口
 */
public interface BarcodeDecoder {

    /**
     * 解码回调
     */
    interface DecodeCallback {
        void onSuccess(List<BarcodeResult> results);

        void onFailure(Exception e);
    }


    /**
     * 从Bitmap解码（异步）
     *
     * @param bitmap          图像
     * @param rotationDegrees 图像旋转角度（0, 90, 180, 270）
     * @param callback        回调
     */
    void decode(Bitmap bitmap, int rotationDegrees, DecodeCallback callback);

    /**
     * 从Bitmap解码（异步，可控制是否保存调试文件）
     *
     * @param bitmap          图像
     * @param rotationDegrees 图像旋转角度（0, 90, 180, 270）
     * @param saveDebugFile   是否保存调试YUV文件
     * @param callback        回调
     */
    void decode(Bitmap bitmap, int rotationDegrees, boolean saveDebugFile, DecodeCallback callback);

    /**
     * 从Camera2 Image解码（异步）
     * <p>
     * 如果设置了增强配置且 enabled=true，会先增强再解码。
     *
     * @param image           YUV_420_888格式图像
     * @param rotationDegrees 图像旋转角度
     * @param callback        回调
     */
    void decode(Image image, int rotationDegrees, DecodeCallback callback);

    /**
     * 从JPEG字节数组解码（异步）
     *
     * @param jpegData        JPEG数据
     * @param rotationDegrees 图像旋转角度
     * @param callback        回调
     */
    void decode(byte[] jpegData, int rotationDegrees, DecodeCallback callback);

    /**
     * 从灰度数据解码（异步）
     * <p>
     * 用于解码增强后的灰度图像数据。
     *
     * @param grayData        灰度图像数据（单通道，行优先存储）
     * @param width           图像宽度
     * @param height          图像高度
     * @param rotationDegrees 图像旋转角度
     * @param callback        回调
     */
    void decode(byte[] grayData, int width, int height, int rotationDegrees, DecodeCallback callback);

    /**
     * 从YUV数据解码（异步）
     * <p>
     * 用于解码从 ARCore Image 提取的 YUV 数据。
     * 调用方负责在调用此方法前关闭 Image。
     *
     * @param yuvData         YUV 图像数据（NV21 格式）
     * @param width           图像宽度
     * @param height          图像高度
     * @param rotationDegrees 图像旋转角度
     * @param callback        回调
     */
    void decodeYuv(byte[] yuvData, int width, int height, int rotationDegrees, DecodeCallback callback);

    /**
     * 释放资源
     */
    void release();
}
