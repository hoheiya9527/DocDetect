package com.urovo.templatedetector.extractor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.urovo.templatedetector.decoder.BarcodeDecoder;
import com.urovo.templatedetector.decoder.BarcodeResult;
import com.urovo.templatedetector.decoder.DecoderFactory;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.model.ContentRegion;
import com.urovo.templatedetector.ocr.OCREngine;
import com.urovo.templatedetector.ocr.OCREngineFactory;
import com.urovo.templatedetector.ocr.OCRResult;
import com.urovo.templatedetector.util.AdaptiveEnhancer;
import com.urovo.templatedetector.util.CameraConfigManager;
import com.urovo.templatedetector.util.PerformanceTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内容提取器
 * 协调OCR和条码解码，合并结果
 */
public class ContentExtractor {

    private static final String TAG = "ContentExtractor";
    private static final long EXTRACTION_TIMEOUT_MS = 10000;

    /**
     * 提取回调
     */
    public interface ExtractionCallback {
        void onProgress(int current, int total);
        void onComplete(List<ContentRegion> regions);
        void onError(Exception e);
    }

    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final CameraConfigManager configManager;

    private OCREngine ocrEngine;
    private BarcodeDecoder barcodeDecoder;
    private boolean isInitialized = false;
    // 标记组件是否由外部注入（不应在release时释放）
    private boolean componentsInjected = false;

    public ContentExtractor(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.configManager = CameraConfigManager.getInstance(context);
    }

    /**
     * 设置已初始化的组件（从AppInitializer获取）
     * 注意：外部注入的组件不会在release时被释放
     */
    public synchronized void setInitializedComponents(OCREngine ocrEngine, BarcodeDecoder barcodeDecoder) {
        this.ocrEngine = ocrEngine;
        this.barcodeDecoder = barcodeDecoder;
        this.isInitialized = (ocrEngine != null);
        this.componentsInjected = true;
        Log.d(TAG, "ContentExtractor configured with pre-initialized components");
    }

    /**
     * 使用指定的OCR引擎类型初始化
     */
    public synchronized boolean initialize(OCREngine.EngineType ocrEngineType) {
        if (isInitialized) {
            return true;
        }

        try {
            // 初始化OCR引擎
            ocrEngine = OCREngineFactory.create(ocrEngineType, context);
            if (!ocrEngine.initialize()) {
                Log.e(TAG, "Failed to initialize OCR engine: " + ocrEngine.getEngineName());
                return false;
            }

            // 初始化条码解码器
            barcodeDecoder = DecoderFactory.create(DecoderFactory.DecoderType.KYD, context);

            isInitialized = true;
            Log.d(TAG, "ContentExtractor initialized with " + ocrEngine.getEngineName());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ContentExtractor", e);
            return false;
        }
    }

    /**
     * 使用默认OCR引擎初始化
     */
    public synchronized boolean initialize() {
        return initialize(OCREngine.EngineType.PADDLE_OCR);
    }

    /**
     * 设置自定义OCR引擎
     */
    public synchronized void setOCREngine(OCREngine engine) {
        if (ocrEngine != null) {
            ocrEngine.release();
        }
        this.ocrEngine = engine;
        Log.d(TAG, "OCR engine switched to: " + engine.getEngineName());
    }

    /**
     * 获取当前OCR引擎
     */
    public OCREngine getOCREngine() {
        return ocrEngine;
    }

    /**
     * 提取所有内容（异步）
     */
    public void extract(Bitmap image, ExtractionCallback callback) {
//        if (!isInitialized) {
//            callback.onError(new IllegalStateException("ContentExtractor not initialized"));
//            return;
//        }

        if (image == null) {
            callback.onError(new IllegalArgumentException("Image is null"));
            return;
        }

        Log.d(TAG, "extract: input image size=" + image.getWidth() + "x" + image.getHeight());

        executor.execute(() -> {
            try {
                // 获取当前相机设置
                CameraSettings settings = configManager.loadSettings();
                CameraSettings.EnhanceConfig enhanceConfig = settings.getEnhanceConfig();

                // 识别阶段的智能增强
                Bitmap enhancedImage = AdaptiveEnhancer.smartEnhance(image, enhanceConfig, false);
                if (enhancedImage != image) {
                    Log.d(TAG, "Applied recognition-stage enhancement");
                }

                List<ContentRegion> allRegions = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(2);

                AtomicReference<List<OCRResult>> ocrResults = new AtomicReference<>(new ArrayList<>());
                AtomicReference<List<BarcodeResult>> barcodeResults = new AtomicReference<>(new ArrayList<>());

                // 并行执行OCR
//                executor.execute(() -> {
//                    try {
//                        postProgress(callback, 0, 2);
//                        List<OCRResult> results = ocrEngine.recognize(image);
//                        ocrResults.set(results);
//                        postProgress(callback, 1, 2);
//                    } catch (Exception e) {
//                        Log.e(TAG, "OCR failed", e);
//                    } finally {
                        latch.countDown();
//                    }
//                });

                // 并行执行条码解码
                executor.execute(() -> {
                    try {
                        Log.d(TAG, "Starting barcode decode, image size=" + enhancedImage.getWidth() + "x" + enhancedImage.getHeight());
                        CountDownLatch decodeLatch = new CountDownLatch(1);
                        barcodeDecoder.decode(enhancedImage, 0, new BarcodeDecoder.DecodeCallback() {
                            @Override
                            public void onSuccess(List<BarcodeResult> results) {
                                Log.d(TAG, "Barcode decode success, results count=" + results.size());
                                for (BarcodeResult result : results) {
                                    Log.d(TAG, "Barcode result: content=" + result.getContent() + 
                                          ", bounds=" + result.getBoundingBox() + 
                                          ", corners=" + java.util.Arrays.toString(result.getCornerPoints()));
                                }
                                barcodeResults.set(results);
                                decodeLatch.countDown();
                            }

                            @Override
                            public void onFailure(Exception e) {
                                Log.e(TAG, "Barcode decode failed", e);
                                decodeLatch.countDown();
                            }
                        });
                        decodeLatch.await(5, TimeUnit.SECONDS);
                        postProgress(callback, 2, 2);
                    } catch (Exception e) {
                        Log.e(TAG, "Barcode decode error", e);
                    } finally {
                        latch.countDown();
                    }
                });

                boolean completed = latch.await(EXTRACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!completed) {
                    Log.w(TAG, "Extraction timeout");
                }

                allRegions.addAll(convertOCRResults(ocrResults.get()));
                allRegions.addAll(convertBarcodeResults(barcodeResults.get()));
                sortRegionsByPosition(allRegions);

                // 清理增强后的图像（如果不同于原始图像）
                if (enhancedImage != image && !enhancedImage.isRecycled()) {
                    enhancedImage.recycle();
                }

                postComplete(callback, allRegions);

            } catch (Exception e) {
                Log.e(TAG, "Extraction failed", e);
                postError(callback, e);
            }
        });
    }

    /**
     * 同步提取
     */
    public List<ContentRegion> extractSync(Bitmap image) {
        if (!isInitialized || image == null) {
            return new ArrayList<>();
        }

        List<ContentRegion> allRegions = new ArrayList<>();

        // OCR识别
        List<OCRResult> ocrResults = ocrEngine.recognize(image);
        allRegions.addAll(convertOCRResults(ocrResults));

        // 条码解码
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<BarcodeResult>> barcodeResults = new AtomicReference<>(new ArrayList<>());

        barcodeDecoder.decode(image, 0, new BarcodeDecoder.DecodeCallback() {
            @Override
            public void onSuccess(List<BarcodeResult> results) {
                barcodeResults.set(results);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                latch.countDown();
            }
        });

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        allRegions.addAll(convertBarcodeResults(barcodeResults.get()));
        sortRegionsByPosition(allRegions);

        return allRegions;
    }

    private List<ContentRegion> convertOCRResults(List<OCRResult> ocrResults) {
        List<ContentRegion> regions = new ArrayList<>();
        if (ocrResults == null) {
            return regions;
        }

        for (OCRResult ocr : ocrResults) {
            if (ocr.isValid()) {
                ContentRegion region = new ContentRegion(
                        ContentRegion.ContentType.OCR,
                        ocr.getText(),
                        "TEXT",
                        ocr.getBoundingBox(),
                        ocr.getCornerPoints(),
                        ocr.getConfidence()
                );
                regions.add(region);
            }
        }
        return regions;
    }

    private List<ContentRegion> convertBarcodeResults(List<BarcodeResult> barcodeResults) {
        List<ContentRegion> regions = new ArrayList<>();
        if (barcodeResults == null) {
            return regions;
        }

        for (BarcodeResult barcode : barcodeResults) {
            if (barcode.getContent() != null && !barcode.getContent().isEmpty()) {
                ContentRegion region = new ContentRegion(
                        ContentRegion.ContentType.BARCODE,
                        barcode.getContent(),
                        barcode.getFormat(),
                        barcode.getBoundingBox(),
                        barcode.getCornerPoints(),
                        1.0f
                );
                regions.add(region);
            }
        }
        return regions;
    }

    private void sortRegionsByPosition(List<ContentRegion> regions) {
        regions.sort((a, b) -> {
            RectF boxA = a.getBoundingBox();
            RectF boxB = b.getBoundingBox();
            if (boxA == null || boxB == null) {
                return 0;
            }
            int yCompare = Float.compare(boxA.top, boxB.top);
            if (Math.abs(boxA.top - boxB.top) < 20) {
                return Float.compare(boxA.left, boxB.left);
            }
            return yCompare;
        });
    }

    private void postProgress(ExtractionCallback callback, int current, int total) {
        mainHandler.post(() -> callback.onProgress(current, total));
    }

    private void postComplete(ExtractionCallback callback, List<ContentRegion> regions) {
        mainHandler.post(() -> callback.onComplete(regions));
    }

    private void postError(ExtractionCallback callback, Exception e) {
        mainHandler.post(() -> callback.onError(e));
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public synchronized void release() {
        // 只释放自己创建的组件，外部注入的组件由 AppInitializer 管理
        if (!componentsInjected) {
            if (ocrEngine != null) {
                ocrEngine.release();
            }
            if (barcodeDecoder != null) {
                barcodeDecoder.release();
            }
        }
        ocrEngine = null;
        barcodeDecoder = null;
        executor.shutdown();
        isInitialized = false;
        componentsInjected = false;
        Log.d(TAG, "ContentExtractor released");
    }
}
