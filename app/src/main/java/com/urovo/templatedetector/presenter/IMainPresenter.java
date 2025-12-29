package com.urovo.templatedetector.presenter;

import androidx.camera.core.ImageProxy;

/**
 * 主界面Presenter接口
 * MVP架构中Presenter层的契约接口
 */
public interface IMainPresenter {

    // ==================== View绑定 ====================

    /**
     * 绑定View
     *
     * @param view View实例
     */
    void attachView(IMainView view);

    /**
     * 解绑View
     */
    void detachView();

    // ==================== 生命周期 ====================

    /**
     * Activity onCreate
     */
    void onCreate();

    /**
     * Activity onResume
     */
    void onResume();

    /**
     * Activity onPause
     */
    void onPause();

    /**
     * Activity onDestroy
     */
    void onDestroy();

    // ==================== 相机回调 ====================

    /**
     * 帧可用回调
     *
     * @param image           图像代理
     * @param rotationDegrees 旋转角度
     */
    void onFrameAvailable(ImageProxy image, int rotationDegrees);

    /**
     * 缩放变化回调
     *
     * @param ratio 缩放比例
     */
    void onZoomChanged(float ratio);

    // ==================== 用户操作 ====================

    /**
     * 确认捕获
     */
    void onConfirmCapture();

    /**
     * 区域点击
     *
     * @param regionId 区域ID
     */
    void onRegionClick(String regionId);

    /**
     * 完成操作
     */
    void onComplete();

    /**
     * 取消操作
     */
    void onCancel();

    /**
     * 设置点击
     */
    void onSettingsClick();
}
