package com.urovo.templatedetector.init;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.urovo.templatedetector.decoder.BarcodeDecoder;
import com.urovo.templatedetector.decoder.DecoderFactory;
import com.urovo.templatedetector.detector.LabelDetector;
import com.urovo.templatedetector.matcher.TemplateMatchingService;
import com.urovo.templatedetector.ocr.OCREngine;
import com.urovo.templatedetector.ocr.OCREngineFactory;

import org.litepal.LitePal;
import org.opencv.android.OpenCVLoader;

/**
 * 应用初始化管理器
 * 负责在应用启动时初始化所有需要的组件
 */
public class AppInitializer {

    private static final String TAG = "AppInitializer";

    /**
     * 初始化回调
     */
    public interface InitCallback {
        void onProgress(int progress, String message);
        void onComplete(boolean success, String errorMessage);
    }

    private static volatile AppInitializer instance;
    private final Context context;
    private final Handler mainHandler;

    // 初始化状态
    private volatile boolean isInitialized = false;
    private volatile boolean isInitializing = false;

    // 组件实例
    private LabelDetector labelDetector;
    private OCREngine ocrEngine;
    private BarcodeDecoder barcodeDecoder;
    private TemplateMatchingService templateMatchingService;

    private AppInitializer(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static AppInitializer getInstance(Context context) {
        if (instance == null) {
            synchronized (AppInitializer.class) {
                if (instance == null) {
                    instance = new AppInitializer(context);
                }
            }
        }
        return instance;
    }

    /**
     * 开始初始化（在主线程调用）
     */
    public void initialize(InitCallback callback) {
        if (isInitialized) {
            callback.onComplete(true, null);
            return;
        }

        if (isInitializing) {
            return;
        }

        isInitializing = true;

        // 步骤0：初始化 LitePal 数据库
        postProgress(callback, 0, "正在初始化数据库...");
        try {
            LitePal.initialize(context);
            Log.d(TAG, "LitePal initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize LitePal", e);
            isInitializing = false;
            callback.onComplete(false, "数据库初始化失败: " + e.getMessage());
            return;
        }

        // 步骤1：在主线程初始化OpenCV和条码解码器（有线程限制）
        postProgress(callback, 10, "正在初始化OpenCV...");

        boolean opencvSuccess = OpenCVLoader.initDebug();
        if (!opencvSuccess) {
            isInitializing = false;
            callback.onComplete(false, "OpenCV初始化失败");
            return;
        }
        Log.d(TAG, "OpenCV initialized");
        postProgress(callback, 20, "正在初始化条码解码器...");

        // 初始化条码解码器
        try {
            barcodeDecoder = DecoderFactory.create(DecoderFactory.DecoderType.KYD, context);
            Log.d(TAG, "BarcodeDecoder initialized");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize BarcodeDecoder", e);
            isInitializing = false;
            callback.onComplete(false, "条码解码器初始化失败: " + e.getMessage());
            return;
        }

        postProgress(callback, 40, "正在初始化检测模型...");

        // 步骤2：在后台线程初始化耗时组件
        new Thread(() -> {
            try {
                // 初始化标签检测器
                labelDetector = new LabelDetector(context);
                if (!labelDetector.initialize()) {
                    postComplete(callback, false, "标签检测器初始化失败");
                    return;
                }
                Log.d(TAG, "LabelDetector initialized");
                postProgress(callback, 60, "正在初始化OCR引擎...");

                // 初始化OCR引擎 (ML Kit)
                ocrEngine = OCREngineFactory.create(OCREngine.EngineType.ML_KIT, context);
                if (!ocrEngine.initialize()) {
                    postComplete(callback, false, "OCR引擎初始化失败");
                    return;
                }
                Log.d(TAG, "OCREngine initialized: " + ocrEngine.getEngineName());

                postProgress(callback, 80, "正在初始化模板匹配服务...");

                // 初始化模板匹配服务
                templateMatchingService = TemplateMatchingService.getInstance(context);
                Log.d(TAG, "TemplateMatchingService initialized");

                postProgress(callback, 100, "初始化完成");

                isInitialized = true;
                isInitializing = false;
                postComplete(callback, true, null);

            } catch (Exception e) {
                Log.e(TAG, "Initialization failed", e);
                isInitializing = false;
                postComplete(callback, false, "初始化失败: " + e.getMessage());
            }
        }, "AppInitThread").start();
    }

    private void postProgress(InitCallback callback, int progress, String message) {
        mainHandler.post(() -> callback.onProgress(progress, message));
    }

    private void postComplete(InitCallback callback, boolean success, String errorMessage) {
        mainHandler.post(() -> callback.onComplete(success, errorMessage));
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public LabelDetector getLabelDetector() {
        return labelDetector;
    }

    public OCREngine getOcrEngine() {
        return ocrEngine;
    }

    public BarcodeDecoder getBarcodeDecoder() {
        return barcodeDecoder;
    }

    public TemplateMatchingService getTemplateMatchingService() {
        return templateMatchingService;
    }

    /**
     * 释放所有资源
     */
    public void release() {
        if (labelDetector != null) {
            labelDetector.release();
            labelDetector = null;
        }
        if (ocrEngine != null) {
            ocrEngine.release();
            ocrEngine = null;
        }
        if (barcodeDecoder != null) {
            barcodeDecoder.release();
            barcodeDecoder = null;
        }
        if (templateMatchingService != null) {
            templateMatchingService.release();
            templateMatchingService = null;
        }
        isInitialized = false;
        instance = null;
    }
}
