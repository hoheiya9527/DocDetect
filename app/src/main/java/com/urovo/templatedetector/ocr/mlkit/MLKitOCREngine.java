package com.urovo.templatedetector.ocr.mlkit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.urovo.templatedetector.ocr.OCREngine;
import com.urovo.templatedetector.ocr.OCRResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 Google ML Kit 的中文 OCR 引擎
 * 支持中文、英文、数字等混合文本识别
 */
public class MLKitOCREngine implements OCREngine {

    private static final String TAG = "MLKitOCREngine";

    private final Context context;
    private TextRecognizer textRecognizer;
    private volatile boolean initialized = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public MLKitOCREngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean initialize() {
        if (initialized) {
            return true;
        }

        try {
            // 使用中文识别器（同时支持英文）
            textRecognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );
            initialized = true;
            Log.d(TAG, "ML Kit Chinese OCR initialized");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ML Kit OCR", e);
            return false;
        }
    }

    @Override
    public List<OCRResult> recognize(Bitmap bitmap) {
        List<OCRResult> results = new ArrayList<>();

//        if (!initialized || bitmap == null || bitmap.isRecycled()) {
//            return results;
//        }
//
//        try {
//            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
//            Text text = Tasks.await(textRecognizer.process(inputImage));
//            results = parseTextResult(text);
//        } catch (Exception e) {
//            Log.e(TAG, "OCR recognition failed", e);
//        }

        return results;
    }

    @Override
    public void recognizeAsync(Bitmap bitmap, RecognizeCallback callback) {
        if (!initialized || bitmap == null || bitmap.isRecycled()) {
            if (callback != null) {
                callback.onFailure(new IllegalStateException("Engine not initialized or invalid bitmap"));
            }
            return;
        }

        executor.execute(() -> {
            try {
                InputImage inputImage = InputImage.fromBitmap(bitmap, 0);
                textRecognizer.process(inputImage)
                        .addOnSuccessListener(text -> {
                            List<OCRResult> results = parseTextResult(text);
                            if (callback != null) {
                                callback.onSuccess(results);
                            }
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Async OCR failed", e);
                            if (callback != null) {
                                callback.onFailure(e);
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Async OCR exception", e);
                if (callback != null) {
                    callback.onFailure(e);
                }
            }
        });
    }

    /**
     * 解析 ML Kit 识别结果
     */
    private List<OCRResult> parseTextResult(Text text) {
        List<OCRResult> results = new ArrayList<>();

        if (text == null) {
            return results;
        }

        // 遍历文本块
        for (Text.TextBlock block : text.getTextBlocks()) {
            // 遍历行
            for (Text.Line line : block.getLines()) {
                String lineText = line.getText();
                android.graphics.Rect boundingBox = line.getBoundingBox();
                Float confidence = line.getConfidence();

                if (lineText == null || lineText.isEmpty() || boundingBox == null) {
                    continue;
                }

                RectF rectF = new RectF(boundingBox);
                PointF[] cornerPoints = extractCornerPoints(line.getCornerPoints());
                float conf = (confidence != null) ? confidence : 0.9f;

                results.add(new OCRResult(lineText, rectF, cornerPoints, conf));
            }
        }

        return results;
    }

    /**
     * 提取四角点
     */
    private PointF[] extractCornerPoints(android.graphics.Point[] points) {
        if (points == null || points.length < 4) {
            return null;
        }

        PointF[] cornerPoints = new PointF[4];
        for (int i = 0; i < 4; i++) {
            cornerPoints[i] = new PointF(points[i].x, points[i].y);
        }
        return cornerPoints;
    }

    @Override
    public EngineType getEngineType() {
        return EngineType.ML_KIT;
    }

    @Override
    public String getEngineName() {
        return "ML Kit Chinese OCR";
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void release() {
        if (textRecognizer != null) {
            textRecognizer.close();
            textRecognizer = null;
        }
        executor.shutdown();
        initialized = false;
        Log.d(TAG, "ML Kit OCR released");
    }
}
