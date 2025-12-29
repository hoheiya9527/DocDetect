package com.urovo.templatedetector.ocr;

import android.graphics.Bitmap;

import java.util.List;

/**
 * OCR引擎接口
 * 定义OCR识别的标准接口，支持不同OCR引擎实现的切换
 */
public interface OCREngine {

    /**
     * OCR引擎类型
     */
    enum EngineType {
        PADDLE_OCR,     // PaddleOCR (FastDeploy)
        ML_KIT,         // Google ML Kit
        TESSERACT,      // Tesseract OCR
        CUSTOM          // 自定义实现
    }

    /**
     * 初始化OCR引擎
     * @return true表示初始化成功
     */
    boolean initialize();

    /**
     * 执行OCR识别
     * @param bitmap 输入图像
     * @return OCR识别结果列表
     */
    List<OCRResult> recognize(Bitmap bitmap);

    /**
     * 异步执行OCR识别
     * @param bitmap 输入图像
     * @param callback 识别回调
     */
    void recognizeAsync(Bitmap bitmap, RecognizeCallback callback);

    /**
     * 获取引擎类型
     */
    EngineType getEngineType();

    /**
     * 获取引擎名称
     */
    String getEngineName();

    /**
     * 是否已初始化
     */
    boolean isInitialized();

    /**
     * 释放资源
     */
    void release();

    /**
     * 识别回调接口
     */
    interface RecognizeCallback {
        void onSuccess(List<OCRResult> results);
        void onFailure(Exception e);
    }
}
