package com.urovo.templatedetector.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;

/**
 * 图像处理工具类
 * 提供统一的图像格式转换和保存功能
 * <p>
 * 线程安全说明：
 * - 所有转换方法都是无状态的，可以安全地在多线程环境中调用
 * - 缓冲区复用由调用方管理，避免内部状态导致的线程安全问题
 */
public class PicUtil {

    private static final String TAG = PicUtil.class.getSimpleName();

    /**
     * 异步保存YUV数据（不阻塞解码流程）
     * <p>
     * 注意事项：
     * 1. 这是调试功能，不保证数据100%正确
     * 2. 由于缓冲区复用，异步保存时数据可能被后续帧覆盖
     * 3. 为避免性能损耗，不进行数组复制
     * 4. 如需保证数据完整性，调用方应传入独立的数据副本
     *
     * @param context 上下文
     * @param yuvData YUV数据（注意：可能在保存过程中被修改）
     * @param width   图像宽度
     * @param height  图像高度
     * @param prefix  文件名前缀
     */
    public static void saveYuvDataAsync(Context context, byte[] yuvData, int width, int height, String prefix) {
        if (context == null || yuvData == null || width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid parameters for saveYuvDataAsync");
            return;
        }
        // 使用独立线程保存，避免阻塞解码
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                FileManager.saveYuvAndJpeg(context, yuvData, width, height, prefix);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save YUV data", e);
            }
        });
    }

    /**
     * 将Bitmap转换为NV21格式的YUV数据
     *
     * @param bitmap 源Bitmap，不能为null
     * @return NV21格式的YUV数据
     * @throws IllegalArgumentException 如果bitmap为null或尺寸无效
     */
    public static byte[] bitmapToYUV(Bitmap bitmap) {
        if (bitmap == null) {
            throw new IllegalArgumentException("Bitmap cannot be null");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid bitmap dimensions: " + width + "x" + height);
        }

        return bitmapToNV21(bitmap);
    }

    /**
     * 将Image转换为NV21格式的YUV数据（支持缓冲区复用）
     *
     * @param image       YUV_420_888格式的Image，不能为null
     * @param reuseBuffer 可复用的缓冲区，如果为null或大小不匹配会创建新缓冲区
     * @return NV21格式的YUV数据（可能是reuseBuffer或新分配的数组）
     * @throws IllegalArgumentException 如果image为null或格式不支持
     */
    public static byte[] imageToYUV(Image image, byte[] reuseBuffer) {
        if (image == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions: " + width + "x" + height);
        }

        return imageToNV21(image, reuseBuffer);
    }


    /**
     * 将Image转换为NV21格式（支持缓冲区复用）
     *
     * @param image       YUV_420_888格式的Image
     * @param reuseBuffer 可复用的缓冲区，如果为null或大小不匹配会创建新缓冲区
     * @return NV21格式的YUV数据
     */
    private static byte[] imageToNV21(Image image, byte[] reuseBuffer) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            throw new IllegalArgumentException("Image must have at least 3 planes (YUV)");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // 计算所需缓冲区大小（YUV420格式：Y + UV = width*height * 1.5）
        int requiredSize = width * height * 3 / 2;

        // 复用或重新分配缓冲区
        byte[] yuvBuffer;
        if (reuseBuffer != null && reuseBuffer.length == requiredSize) {
            yuvBuffer = reuseBuffer;
        } else {
            yuvBuffer = new byte[requiredSize];
        }

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // 重置buffer位置
        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        // 复制Y平面
        int yIndex = 0;
        for (int row = 0; row < height; row++) {
            int srcPos = row * yRowStride;
            if (srcPos + width <= yBuffer.capacity()) {
                yBuffer.position(srcPos);
                int remaining = Math.min(width, yBuffer.remaining());
                yBuffer.get(yuvBuffer, yIndex, remaining);
                yIndex += width;
            } else {
                Log.w(TAG, "Y plane row " + row + " exceeds buffer capacity");
                break;
            }
        }

        // 复制UV平面为NV21格式（VU交错）
        int uvIndex = width * height;
        int uvHeight = height / 2;
        int uvWidth = width / 2;

        for (int row = 0; row < uvHeight; row++) {
            for (int col = 0; col < uvWidth; col++) {
                int uvOffset = row * uvRowStride + col * uvPixelStride;

                // 检查边界
                if (uvOffset < vBuffer.capacity() && uvOffset < uBuffer.capacity() && uvIndex + 1 < yuvBuffer.length) {
                    yuvBuffer[uvIndex++] = vBuffer.get(uvOffset);
                    yuvBuffer[uvIndex++] = uBuffer.get(uvOffset);
                } else {
                    Log.w(TAG, "UV plane access out of bounds at row=" + row + ", col=" + col);
                    break;
                }
            }
        }

        return yuvBuffer;
    }

    /**
     * 将Bitmap转换为NV21格式
     *
     * @param bitmap 源Bitmap
     * @return NV21格式的YUV数据
     */
    private static byte[] bitmapToNV21(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        // YUV420 要求宽高为偶数，如果是奇数则向上取整
        int alignedWidth = (width + 1) & ~1;
        int alignedHeight = (height + 1) & ~1;
        
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);

        // 使用对齐后的尺寸计算缓冲区大小
        byte[] yuv = new byte[alignedWidth * alignedHeight * 3 / 2];
        encodeYUV420SP(yuv, argb, width, height, alignedWidth, alignedHeight);
        return yuv;
    }

    /**
     * RGB转YUV420SP(NV21)
     * 使用ITU-R BT.601标准转换公式
     *
     * @param yuv420sp      输出缓冲区
     * @param argb          输入ARGB像素数组
     * @param width         实际图像宽度
     * @param height        实际图像高度
     * @param alignedWidth  对齐后的宽度（偶数）
     * @param alignedHeight 对齐后的高度（偶数）
     */
    private static void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height, 
                                        int alignedWidth, int alignedHeight) {
        final int frameSize = alignedWidth * alignedHeight;

        int yIndex = 0;
        int uvIndex = frameSize;

        for (int j = 0; j < alignedHeight; j++) {
            for (int i = 0; i < alignedWidth; i++) {
                int R, G, B;
                
                // 如果超出实际图像范围，使用边缘像素
                int srcX = Math.min(i, width - 1);
                int srcY = Math.min(j, height - 1);
                int index = srcY * width + srcX;
                
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff);

                // ITU-R BT.601转换公式
                int Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                int U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                int V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                yuv420sp[yIndex++] = (byte) Math.max(0, Math.min(255, Y));

                // UV采样：每2x2的Y对应一个UV
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420sp[uvIndex++] = (byte) Math.max(0, Math.min(255, V));
                    yuv420sp[uvIndex++] = (byte) Math.max(0, Math.min(255, U));
                }
            }
        }
    }
}
