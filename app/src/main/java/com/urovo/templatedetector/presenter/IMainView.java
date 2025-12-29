package com.urovo.templatedetector.presenter;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import com.urovo.templatedetector.model.ContentRegion;

import java.util.List;

/**
 * 主界面View接口
 * MVP架构中View层的契约接口
 */
public interface IMainView {

    // ==================== 相机控制 ====================

    /**
     * 显示相机预览
     */
    void showCameraPreview();

    /**
     * 停止相机预览
     */
    void stopCameraPreview();

    /**
     * 显示相机错误
     *
     * @param message 错误消息
     */
    void showCameraError(String message);

    // ==================== 检测结果显示 ====================

    /**
     * 设置源图像尺寸（用于坐标转换）
     *
     * @param width  源图像宽度
     * @param height 源图像高度
     */
    void setSourceSize(int width, int height);

    /**
     * 显示检测框（绿色）
     *
     * @param box     边界框
     * @param corners 四角点
     */
    void showDetectionBox(RectF box, PointF[] corners);

    /**
     * 隐藏检测框
     */
    void hideDetectionBox();

    /**
     * 更新置信度显示
     *
     * @param confidence 当前检测置信度 (0.0-1.0)
     */
    void updateConfidenceDisplay(double confidence);

    /**
     * 显示校正后的图像
     *
     * @param image 校正后的图像
     */
    void showCorrectedImage(Bitmap image);

    // ==================== 内容区域显示 ====================

    /**
     * 显示内容区域（蓝色边框）
     *
     * @param regions 内容区域列表
     */
    void showContentRegions(List<ContentRegion> regions);

    /**
     * 更新区域选择状态
     *
     * @param regionId 区域ID
     * @param selected 是否选中
     */
    void updateRegionSelection(String regionId, boolean selected);

    /**
     * 清除所有内容区域
     */
    void clearContentRegions();

    // ==================== 信息面板 ====================

    /**
     * 显示引导文本
     *
     * @param text 引导文本
     */
    void showGuidanceText(String text);

    /**
     * 显示已选内容
     *
     * @param selected 已选内容区域列表
     */
    void showSelectedContent(List<ContentRegion> selected);

    // ==================== 进度与状态 ====================

    /**
     * 显示加载中
     *
     * @param message 加载消息
     */
    void showLoading(String message);

    /**
     * 隐藏加载中
     */
    void hideLoading();

    /**
     * 显示Toast消息
     *
     * @param message 消息内容
     */
    void showToast(String message);

    /**
     * 完成操作，返回结果
     *
     * @param selectedRegions 选中的内容区域
     */
    void finishWithResult(List<ContentRegion> selectedRegions);
}
