package com.urovo.templatedetector.ocr;

import android.content.Context;

import com.urovo.templatedetector.ocr.paddle.PaddleOCREngine;

/**
 * OCR引擎工厂
 * 用于创建不同类型的OCR引擎实例
 */
public final class OCREngineFactory {

    private OCREngineFactory() {
        // 禁止实例化
    }

    /**
     * 创建OCR引擎
     * @param type 引擎类型
     * @param context 上下文
     * @return OCR引擎实例
     */
    public static OCREngine create(OCREngine.EngineType type, Context context) {
        switch (type) {
            case PADDLE_OCR:
                return new PaddleOCREngine(context);
            case ML_KIT:
                throw new UnsupportedOperationException("ML Kit OCR engine not implemented yet");
            case TESSERACT:
                throw new UnsupportedOperationException("Tesseract OCR engine not implemented yet");
            case CUSTOM:
                throw new UnsupportedOperationException("Custom OCR engine requires manual implementation");
            default:
                throw new IllegalArgumentException("Unknown OCR engine type: " + type);
        }
    }

    /**
     * 创建默认OCR引擎 (PaddleOCR)
     * @param context 上下文
     * @return OCR引擎实例
     */
    public static OCREngine createDefault(Context context) {
        return create(OCREngine.EngineType.PADDLE_OCR, context);
    }
}
