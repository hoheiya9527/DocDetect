package com.urovo.templatedetector.matcher;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import com.urovo.templatedetector.decoder.BarcodeDecoder;
import com.urovo.templatedetector.decoder.BarcodeResult;
import com.urovo.templatedetector.model.MatchResult;
import com.urovo.templatedetector.model.TemplateRegion;
import com.urovo.templatedetector.ocr.OCREngine;
import com.urovo.templatedetector.ocr.OCRResult;
import com.urovo.templatedetector.util.ImageEnhancer;
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
 * 区域内容识别器
 * 对模板匹配后的变换区域进行 OCR/条码识别
 * <p>
 * 性能优化：
 * - 多区域并行识别
 * - 条码和 OCR 并行尝试
 */
public class RegionContentRecognizer {

    private static final String TAG = "RegionContentRecognizer";

    /**
     * 单个区域识别超时时间（毫秒）
     */
    private static final long REGION_TIMEOUT_MS = 3000;

    /**
     * 全部区域识别超时时间（毫秒）
     */
    private static final long TOTAL_TIMEOUT_MS = 10000;

    /**
     * 并行识别线程池
     */
    private final ExecutorService executor;

    private final OCREngine ocrEngine;
    private final BarcodeDecoder barcodeDecoder;

    /**
     * 是否启用图像增强
     */
    private boolean enableEnhance = true;

    public RegionContentRecognizer(OCREngine ocrEngine, BarcodeDecoder barcodeDecoder) {
        this.ocrEngine = ocrEngine;
        this.barcodeDecoder = barcodeDecoder;
        // 使用固定大小线程池，避免创建过多线程
        this.executor = Executors.newFixedThreadPool(
                Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * 设置是否启用图像增强
     *
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
     * 并行识别所有变换区域的内容
     *
     * @param image              输入图像
     * @param transformedRegions 变换后的区域列表
     */
    public void recognizeAll(Bitmap image, List<MatchResult.TransformedRegion> transformedRegions) {
        if (image == null || transformedRegions == null || transformedRegions.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        int regionCount = transformedRegions.size();

        // 预先裁剪所有区域图像
        List<RegionTask> tasks = new ArrayList<>();
        for (MatchResult.TransformedRegion region : transformedRegions) {
            RectF bounds = region.getTransformedBounds();
            if (bounds == null || region.getRegion() == null) continue;

            Bitmap croppedImage = cropRegion(image, bounds, region.getRegion().getRegionType());
            if (croppedImage != null) {
                tasks.add(new RegionTask(region, croppedImage));
            }
        }

        if (tasks.isEmpty()) {
            return;
        }

        // 使用 CountDownLatch 等待所有任务完成
        CountDownLatch latch = new CountDownLatch(tasks.size());

        for (RegionTask task : tasks) {
            executor.submit(() -> {
                try {
                    recognizeRegionParallel(task.croppedImage, task.region);
                } finally {
                    task.croppedImage.recycle();
                    latch.countDown();
                }
            });
        }

        try {
            // 等待所有任务完成或超时
            boolean completed = latch.await(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                Log.w(TAG, "recognizeAll: timeout, some regions may not be recognized");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "recognizeAll: interrupted");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        Log.d(TAG, "recognizeAll: " + regionCount + " regions in " + elapsed + "ms");
    }

    /**
     * 区域识别任务
     */
    private static class RegionTask {
        final MatchResult.TransformedRegion region;
        final Bitmap croppedImage;

        RegionTask(MatchResult.TransformedRegion region, Bitmap croppedImage) {
            this.region = region;
            this.croppedImage = croppedImage;
        }
    }

    /**
     * 并行识别单个区域（条码和 OCR 同时尝试）
     */
    private void recognizeRegionParallel(Bitmap croppedImage, MatchResult.TransformedRegion region) {
        if (croppedImage == null || region == null) {
            return;
        }

        TemplateRegion templateRegion = region.getRegion();
        if (templateRegion == null) {
            return;
        }

        // 如果启用增强，对裁剪后的图像进行增强处理
        Bitmap imageForRecognition = croppedImage;
        Bitmap enhancedImage = null;
        if (enableEnhance) {
            enhancedImage = enhanceBitmap(croppedImage);
            if (enhancedImage != null) {
                imageForRecognition = enhancedImage;
            }
        }
        final Bitmap finalImage = imageForRecognition;
        final Bitmap finalEnhancedImage = enhancedImage;

        // 使用 CountDownLatch 实现条码和 OCR 并行
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<String> barcodeResult = new AtomicReference<>();
        AtomicReference<String> barcodeFormat = new AtomicReference<>();
        AtomicReference<String> ocrResult = new AtomicReference<>();
        AtomicReference<Float> ocrConfidence = new AtomicReference<>(0f);

        // 条码识别任务
        Thread barcodeThread = new Thread(() -> {
            try {
                if (barcodeDecoder != null) {
                    CountDownLatch barcodeLatch = new CountDownLatch(1);
                    barcodeDecoder.decode(finalImage, 0, new BarcodeDecoder.DecodeCallback() {
                        @Override
                        public void onSuccess(List<BarcodeResult> results) {
                            if (results != null && !results.isEmpty()) {
                                BarcodeResult result = results.get(0);
                                barcodeResult.set(result.getContent());
                                barcodeFormat.set(result.getFormat());
                            }
                            barcodeLatch.countDown();
                        }

                        @Override
                        public void onFailure(Exception e) {
                            barcodeLatch.countDown();
                        }
                    });
                    barcodeLatch.await(REGION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                Log.e(TAG, "Barcode recognition failed", e);
            } finally {
                latch.countDown();
            }
        });

        // OCR 识别任务
        Thread ocrThread = new Thread(() -> {
            try {
                if (ocrEngine != null) {
                    List<OCRResult> results = ocrEngine.recognize(finalImage);
                    if (results != null && !results.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        float totalConfidence = 0;
                        int validCount = 0;

                        for (OCRResult result : results) {
                            if (result.isValid()) {
                                if (sb.length() > 0) sb.append(" ");
                                sb.append(result.getText());
                                totalConfidence += result.getConfidence();
                                validCount++;
                            }
                        }

                        if (sb.length() > 0) {
                            ocrResult.set(sb.toString());
                            ocrConfidence.set(totalConfidence / validCount);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "OCR recognition failed", e);
            } finally {
                latch.countDown();
            }
        });

        barcodeThread.start();
        ocrThread.start();

        try {
            latch.await(REGION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 释放增强后的图像
        if (finalEnhancedImage != null && !finalEnhancedImage.isRecycled()) {
            finalEnhancedImage.recycle();
        }

        // 优先使用条码结果
        if (barcodeResult.get() != null) {
            region.setContent(barcodeResult.get());
            region.setFormat(barcodeFormat.get());
            region.setRecognitionConfidence(1.0f);
            Log.d(TAG, "recognizeRegion: " + templateRegion.getName() + " = " + barcodeResult.get() + " (barcode)");
        } else if (ocrResult.get() != null) {
            region.setContent(ocrResult.get());
            region.setFormat("TEXT");
            region.setRecognitionConfidence(ocrConfidence.get());
            Log.d(TAG, "recognizeRegion: " + templateRegion.getName() + " = " + ocrResult.get() + " (OCR)");
        }
    }

    /**
     * 对 Bitmap 进行图像增强（灰度 + CLAHE）
     *
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
     * 识别单个区域的内容（串行版本，保留兼容性）
     */
    public void recognizeRegion(Bitmap image, MatchResult.TransformedRegion transformedRegion) {
        if (image == null || transformedRegion == null) {
            return;
        }

        TemplateRegion region = transformedRegion.getRegion();
        RectF bounds = transformedRegion.getTransformedBounds();

        if (region == null || bounds == null) {
            return;
        }

        Bitmap croppedImage = cropRegion(image, bounds, region.getRegionType());
        if (croppedImage == null) {
            Log.w(TAG, "recognizeRegion: failed to crop region: " + region.getName());
            return;
        }

        try {
            recognizeRegionParallel(croppedImage, transformedRegion);
        } finally {
            croppedImage.recycle();
        }
    }

    /**
     * 裁剪区域图像（根据区域类型使用对应的扩展比例）
     */
    private Bitmap cropRegion(Bitmap source, RectF bounds, TemplateRegion.RegionType regionType) {
        float expandRatio = (regionType == TemplateRegion.RegionType.BARCODE)
                ? TemplateRegion.EXPAND_RATIO_BARCODE
                : TemplateRegion.EXPAND_RATIO_TEXT;

        float width = bounds.width();
        float height = bounds.height();
        float expandX = width * expandRatio;
        float expandY = height * expandRatio * 2;//Y向上多扩展一倍避免高度不够

        int left = Math.max(0, (int) (bounds.left - expandX));
        int top = Math.max(0, (int) (bounds.top - expandY));
        int right = Math.min(source.getWidth(), (int) (bounds.right + expandX));
        int bottom = Math.min(source.getHeight(), (int) (bounds.bottom + expandY));

        int cropWidth = right - left;
        int cropHeight = bottom - top;

        if (cropWidth <= 0 || cropHeight <= 0) {
            return null;
        }

        try {
            return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
        } catch (Exception e) {
            Log.e(TAG, "cropRegion failed", e);
            return null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, "RegionContentRecognizer released");
    }
}
