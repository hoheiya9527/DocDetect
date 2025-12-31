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
import com.urovo.templatedetector.model.DetectedRegion;
import com.urovo.templatedetector.model.TemplateRegion;
import com.urovo.templatedetector.ocr.OCREngine;
import com.urovo.templatedetector.ocr.OCREngineFactory;
import com.urovo.templatedetector.ocr.OCRResult;
import com.urovo.templatedetector.util.ImageEnhancer;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

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
        void onComplete(List<DetectedRegion> regions);
        void onError(Exception e);
    }

    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private OCREngine ocrEngine;
    private BarcodeDecoder barcodeDecoder;
    private boolean isInitialized = false;
    private boolean componentsInjected = false;
    
    /** 是否启用图像增强 */
    private boolean enableEnhance = true;

    public ContentExtractor(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置是否启用图像增强
     * @param enable true 启用增强（灰度+CLAHE），false 使用原图
     */
    public void setEnableEnhance(boolean enable) {
        this.enableEnhance = enable;
    }

    /**
     * 获取是否启用图像增强
     */
    public boolean isEnableEnhance() {
        return enableEnhance;
    }

    /**
     * 设置已初始化的组件（从AppInitializer获取）
     */
    public synchronized void setInitializedComponents(OCREngine ocrEngine, BarcodeDecoder barcodeDecoder) {
        this.ocrEngine = ocrEngine;
        this.barcodeDecoder = barcodeDecoder;
        this.isInitialized = (ocrEngine != null);
        this.componentsInjected = true;
        Log.d(TAG, ">> ContentExtractor configured with pre-initialized components");
    }

    /**
     * 使用指定的OCR引擎类型初始化
     */
    public synchronized boolean initialize(OCREngine.EngineType ocrEngineType) {
        if (isInitialized) {
            return true;
        }

        try {
            ocrEngine = OCREngineFactory.create(ocrEngineType, context);
            if (!ocrEngine.initialize()) {
                Log.e(TAG, ">> Failed to initialize OCR engine: " + ocrEngine.getEngineName());
                return false;
            }

            barcodeDecoder = DecoderFactory.create(DecoderFactory.DecoderType.KYD, context);

            isInitialized = true;
            Log.d(TAG, ">> ContentExtractor initialized with " + ocrEngine.getEngineName());
            return true;

        } catch (Exception e) {
            Log.e(TAG, ">> Failed to initialize ContentExtractor", e);
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
        if (ocrEngine != null && !componentsInjected) {
            ocrEngine.release();
        }
        this.ocrEngine = engine;
        Log.d(TAG, ">> OCR engine switched to: " + engine.getEngineName());
    }

    /**
     * 获取当前OCR引擎
     */
    public OCREngine getOCREngine() {
        return ocrEngine;
    }

    /**
     * 提取所有内容（异步）
     * 同时进行 OCR 和条码识别
     */
    public void extract(Bitmap image, ExtractionCallback callback) {
        if (image == null) {
            callback.onError(new IllegalArgumentException("Image is null"));
            return;
        }

        Log.d(TAG, ">> extract: input image size=" + image.getWidth() + "x" + image.getHeight());

        executor.execute(() -> {
            Bitmap enhancedImage = null;
            try {
                // 如果启用增强，对图像进行增强处理
                final Bitmap imageForRecognition;
                if (enableEnhance) {
                    enhancedImage = enhanceBitmap(image);
                    imageForRecognition = enhancedImage != null ? enhancedImage : image;
                    if (enhancedImage != null) {
                        Log.d(TAG, ">> Image enhancement applied for extraction");
                    }
                } else {
                    imageForRecognition = image;
                }

                List<DetectedRegion> allRegions = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(2);

                AtomicReference<List<OCRResult>> ocrResults = new AtomicReference<>(new ArrayList<>());
                AtomicReference<List<BarcodeResult>> barcodeResults = new AtomicReference<>(new ArrayList<>());

                // OCR 识别
                executor.execute(() -> {
                    try {
                        if (ocrEngine != null) {
                            Log.d(TAG, ">> Starting OCR recognition");
                            List<OCRResult> results = ocrEngine.recognize(imageForRecognition);
                            if (results != null) {
                                ocrResults.set(results);
                                Log.d(TAG, ">> OCR recognition success, count=" + results.size());
                            }
                        }
                        postProgress(callback, 1, 2);
                    } catch (Exception e) {
                        Log.e(TAG, ">> OCR recognition error", e);
                    } finally {
                        latch.countDown();
                    }
                });

                // 条码解码
                executor.execute(() -> {
                    try {
                        if (barcodeDecoder != null) {
                            Log.d(TAG, ">> Starting barcode decode");
                            CountDownLatch decodeLatch = new CountDownLatch(1);
                            barcodeDecoder.decode(imageForRecognition, 0, new BarcodeDecoder.DecodeCallback() {
                                @Override
                                public void onSuccess(List<BarcodeResult> results) {
                                    Log.d(TAG, ">> Barcode decode success, count=" + results.size());
                                    barcodeResults.set(results);
                                    decodeLatch.countDown();
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    Log.e(TAG, ">> Barcode decode failed", e);
                                    decodeLatch.countDown();
                                }
                            });
                            decodeLatch.await(5, TimeUnit.SECONDS);
                        }
                        postProgress(callback, 2, 2);
                    } catch (Exception e) {
                        Log.e(TAG, ">> Barcode decode error", e);
                    } finally {
                        latch.countDown();
                    }
                });

                boolean completed = latch.await(EXTRACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!completed) {
                    Log.w(TAG, ">> Extraction timeout");
                }

                // 合并结果，条码优先（去重）
                List<DetectedRegion> barcodeRegions = convertBarcodeResults(barcodeResults.get());
                List<DetectedRegion> ocrRegions = convertOCRResults(ocrResults.get());
                
                // 添加条码结果
                allRegions.addAll(barcodeRegions);
                
                // 添加 OCR 结果（排除与条码重叠的区域）
                for (DetectedRegion ocrRegion : ocrRegions) {
                    if (!isOverlappingWithAny(ocrRegion, barcodeRegions)) {
                        allRegions.add(ocrRegion);
                    }
                }
                
                sortRegionsByPosition(allRegions);

                postComplete(callback, allRegions);

            } catch (Exception e) {
                Log.e(TAG, ">> Extraction failed", e);
                postError(callback, e);
            } finally {
                // 释放增强后的图像
                if (enhancedImage != null && !enhancedImage.isRecycled()) {
                    enhancedImage.recycle();
                }
            }
        });
    }

    /**
     * 对 Bitmap 进行图像增强（灰度 + CLAHE）
     * @param source 原始图像
     * @return 增强后的图像，失败返回 null
     */
    private Bitmap enhanceBitmap(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        Mat srcMat = null;
        Mat enhancedMat = null;
        try {
            srcMat = new Mat();
            Utils.bitmapToMat(source, srcMat);
            
            enhancedMat = ImageEnhancer.enhanceMat(srcMat);
            if (enhancedMat == null || enhancedMat.empty()) {
                return null;
            }

            return ImageEnhancer.matToBitmap(enhancedMat);
        } catch (Exception e) {
            Log.e(TAG, "enhanceBitmap failed", e);
            return null;
        } finally {
            if (srcMat != null) srcMat.release();
            if (enhancedMat != null) enhancedMat.release();
        }
    }

    /**
     * 检查区域是否与列表中任意区域重叠
     */
    private boolean isOverlappingWithAny(DetectedRegion region, List<DetectedRegion> others) {
        RectF bounds = region.getBoundingBox();
        if (bounds == null) return false;
        
        for (DetectedRegion other : others) {
            RectF otherBounds = other.getBoundingBox();
            if (otherBounds != null && RectF.intersects(bounds, otherBounds)) {
                // 计算重叠比例
                float overlapArea = calculateOverlapArea(bounds, otherBounds);
                float regionArea = bounds.width() * bounds.height();
                if (regionArea > 0 && overlapArea / regionArea > 0.5f) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 计算两个矩形的重叠面积
     */
    private float calculateOverlapArea(RectF a, RectF b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        
        if (left < right && top < bottom) {
            return (right - left) * (bottom - top);
        }
        return 0;
    }

    /**
     * 同步提取
     */
    public List<DetectedRegion> extractSync(Bitmap image) {
        if (!isInitialized || image == null) {
            return new ArrayList<>();
        }

        List<DetectedRegion> allRegions = new ArrayList<>();

        // OCR识别
        if (ocrEngine != null) {
            List<OCRResult> ocrResults = ocrEngine.recognize(image);
            allRegions.addAll(convertOCRResults(ocrResults));
        }

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

    private List<DetectedRegion> convertOCRResults(List<OCRResult> ocrResults) {
        List<DetectedRegion> regions = new ArrayList<>();
        if (ocrResults == null) {
            return regions;
        }

        for (OCRResult ocr : ocrResults) {
            if (ocr.isValid()) {
                // OCR 区域适当外扩
                RectF expandedBounds = expandBounds(ocr.getBoundingBox(), TemplateRegion.EXPAND_RATIO_TEXT);
                DetectedRegion region = new DetectedRegion(
                        DetectedRegion.Type.TEXT,
                        ocr.getText(),
                        "TEXT",
                        expandedBounds,
                        ocr.getCornerPoints(),
                        ocr.getConfidence()
                );
                regions.add(region);
            }
        }
        return regions;
    }

    private List<DetectedRegion> convertBarcodeResults(List<BarcodeResult> barcodeResults) {
        List<DetectedRegion> regions = new ArrayList<>();
        if (barcodeResults == null) {
            return regions;
        }

        for (BarcodeResult barcode : barcodeResults) {
            if (barcode.getContent() != null && !barcode.getContent().isEmpty()) {
                // 条码区域适当外扩，比文字扩展更多
                RectF expandedBounds = expandBounds(barcode.getBoundingBox(), TemplateRegion.EXPAND_RATIO_BARCODE);
                DetectedRegion region = new DetectedRegion(
                        DetectedRegion.Type.BARCODE,
                        barcode.getContent(),
                        barcode.getFormat(),
                        expandedBounds,
                        barcode.getCornerPoints(),
                        1.0f
                );
                regions.add(region);
            }
        }
        return regions;
    }

    /**
     * 扩展边界框
     * @param bounds 原始边界框
     * @param ratio 扩展比例（如 0.1 表示每边扩展 10%）
     * @return 扩展后的边界框
     */
    private RectF expandBounds(RectF bounds, float ratio) {
        if (bounds == null) {
            return null;
        }
        
        float width = bounds.width();
        float height = bounds.height();
        float expandX = width * ratio;
        float expandY = height * ratio;
        
        return new RectF(
                bounds.left - expandX,
                bounds.top - expandY,
                bounds.right + expandX,
                bounds.bottom + expandY
        );
    }

    private void sortRegionsByPosition(List<DetectedRegion> regions) {
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

    private void postComplete(ExtractionCallback callback, List<DetectedRegion> regions) {
        mainHandler.post(() -> callback.onComplete(regions));
    }

    private void postError(ExtractionCallback callback, Exception e) {
        mainHandler.post(() -> callback.onError(e));
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public synchronized void release() {
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
        Log.d(TAG, ">> ContentExtractor released");
    }
}
