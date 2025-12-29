package com.urovo.templatedetector.decoder;

import android.graphics.PointF;
import android.util.Log;

/**
 * 图像坐标到屏幕坐标的转换器
 * 
 * 支持两种预览模式：
 * 1. FIT_START（左上对齐）：图像缩放后左上角对齐，超出部分在右边/下边被裁剪
 * 2. CENTER_CROP（居中裁剪）：图像缩放后居中，超出部分在两边被裁剪
 */
public class CoordinateTransformer {
    
    private static final String TAG = "CoordinateTransformer";
    
    /**
     * 缩放模式
     */
    public enum ScaleMode {
        /** 左上对齐，超出部分在右边/下边被裁剪 */
        FIT_START,
        /** 居中裁剪，超出部分在两边被裁剪 */
        CENTER_CROP
    }
    
    private final int imageWidth;
    private final int imageHeight;
    private final int viewWidth;
    private final int viewHeight;
    private final int rotationDegrees;
    private final ScaleMode scaleMode;
    
    // 计算后的缩放和偏移
    private float scale;
    private float offsetX;
    private float offsetY;
    private boolean swapDimensions;
    
    /**
     * 使用默认的 FIT_START 模式
     */
    public CoordinateTransformer(int imageWidth, int imageHeight, 
                                  int viewWidth, int viewHeight, 
                                  int rotationDegrees) {
        this(imageWidth, imageHeight, viewWidth, viewHeight, rotationDegrees, ScaleMode.FIT_START);
    }
    
    /**
     * @param imageWidth 图像宽度（旋转后的有效宽度）
     * @param imageHeight 图像高度（旋转后的有效高度）
     * @param viewWidth 视图宽度（屏幕上可见区域）
     * @param viewHeight 视图高度（屏幕上可见区域）
     * @param rotationDegrees 图像旋转角度（0, 90, 180, 270）
     * @param scaleMode 缩放模式
     */
    public CoordinateTransformer(int imageWidth, int imageHeight, 
                                  int viewWidth, int viewHeight, 
                                  int rotationDegrees, ScaleMode scaleMode) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.rotationDegrees = rotationDegrees;
        this.scaleMode = scaleMode;
        
        calculateTransform();
    }
    
    private void calculateTransform() {
        // 判断是否需要交换宽高（90度或270度旋转）
        swapDimensions = (rotationDegrees == 90 || rotationDegrees == 270);
        
        int effectiveImageWidth = swapDimensions ? imageHeight : imageWidth;
        int effectiveImageHeight = swapDimensions ? imageWidth : imageHeight;
        
        // 使用较大的缩放比例，确保图像填满视图（CenterCrop 行为）
        float scaleX = (float) viewWidth / effectiveImageWidth;
        float scaleY = (float) viewHeight / effectiveImageHeight;
        scale = Math.max(scaleX, scaleY);
        
        // 计算缩放后的图像尺寸
        float scaledWidth = effectiveImageWidth * scale;
        float scaledHeight = effectiveImageHeight * scale;
        
        // 根据缩放模式计算偏移
        if (scaleMode == ScaleMode.CENTER_CROP) {
            // 居中裁剪
            offsetX = (viewWidth - scaledWidth) / 2f;
            offsetY = (viewHeight - scaledHeight) / 2f;
        } else {
            // FIT_START：左上对齐，偏移为 0
            offsetX = 0;
            offsetY = 0;
        }
        
        Log.d(TAG, "Transform: image=" + effectiveImageWidth + "x" + effectiveImageHeight +
                ", view=" + viewWidth + "x" + viewHeight +
                ", scale=" + scale + ", mode=" + scaleMode +
                ", offset=(" + offsetX + ", " + offsetY + ")");
    }
    
    /**
     * 将图像坐标转换为屏幕坐标
     * @param imageX 图像X坐标
     * @param imageY 图像Y坐标
     * @return 屏幕坐标
     */
    public PointF imageToScreen(float imageX, float imageY) {
        float rotatedX, rotatedY;
        
        // 根据旋转角度转换坐标
        switch (rotationDegrees) {
            case 90:
                rotatedX = imageY;
                rotatedY = imageWidth - imageX;
                break;
            case 180:
                rotatedX = imageWidth - imageX;
                rotatedY = imageHeight - imageY;
                break;
            case 270:
                rotatedX = imageHeight - imageY;
                rotatedY = imageX;
                break;
            default: // 0
                rotatedX = imageX;
                rotatedY = imageY;
                break;
        }
        
        // 应用缩放和偏移
        float screenX = rotatedX * scale + offsetX;
        float screenY = rotatedY * scale + offsetY;
        
        return new PointF(screenX, screenY);
    }
    
    /**
     * 将图像坐标点转换为屏幕坐标
     */
    public PointF imageToScreen(PointF imagePoint) {
        return imageToScreen(imagePoint.x, imagePoint.y);
    }
    
    /**
     * 获取有效的图像宽度（考虑旋转后）
     */
    public int getEffectiveImageWidth() {
        return swapDimensions ? imageHeight : imageWidth;
    }
    
    /**
     * 获取有效的图像高度（考虑旋转后）
     */
    public int getEffectiveImageHeight() {
        return swapDimensions ? imageWidth : imageHeight;
    }
    
    public float getScale() {
        return scale;
    }
    
    public float getOffsetX() {
        return offsetX;
    }
    
    public float getOffsetY() {
        return offsetY;
    }
}
