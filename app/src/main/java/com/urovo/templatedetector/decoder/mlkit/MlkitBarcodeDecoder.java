package com.urovo.templatedetector.decoder.mlkit;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.util.Log;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.urovo.templatedetector.decoder.BarcodeDecoder;
import com.urovo.templatedetector.decoder.BarcodeResult;
import com.urovo.templatedetector.util.PerformanceTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * MLKit条码解码器实现
 */
public class MlkitBarcodeDecoder implements BarcodeDecoder {

    private static final String TAG = "MlkitBarcodeDecoder";

    private final BarcodeScanner scanner;

    public MlkitBarcodeDecoder() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        this.scanner = BarcodeScanning.getClient(options);
    }

    @Override
    public void decode(Bitmap bitmap, int rotationDegrees, DecodeCallback callback) {
        decode(bitmap, rotationDegrees, false, callback);
    }

    @Override
    public void decode(Bitmap bitmap, int rotationDegrees, boolean saveDebugFile, DecodeCallback callback) {
        if (bitmap == null) {
            callback.onFailure(new IllegalArgumentException("Bitmap is null"));
            return;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.BARCODE_DECODE)) {
            InputImage image = InputImage.fromBitmap(bitmap, rotationDegrees);
            processImage(image, callback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create InputImage from Bitmap", e);
            callback.onFailure(e);
        }
    }

    @Override
    public void decode(Image image, int rotationDegrees, DecodeCallback callback) {
        if (image == null) {
            callback.onFailure(new IllegalArgumentException("Image is null"));
            return;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.BARCODE_DECODE)) {
            // InputImage.fromMediaImage 会在内部复制必要的数据
            // 所以创建 InputImage 后，原始 Image 可以被关闭
            InputImage inputImage = InputImage.fromMediaImage(image, rotationDegrees);
            processImage(inputImage, callback);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create InputImage from Image", e);
            callback.onFailure(e);
        }
    }

    @Override
    public void decode(byte[] jpegData, int rotationDegrees, DecodeCallback callback) {
        if (jpegData == null || jpegData.length == 0) {
            callback.onFailure(new IllegalArgumentException("JPEG data is null or empty"));
            return;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.BARCODE_DECODE)) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bitmap == null) {
                callback.onFailure(new RuntimeException("Failed to decode JPEG data"));
                return;
            }

            decode(bitmap, rotationDegrees, callback);
            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode JPEG data", e);
            callback.onFailure(e);
        }
    }

    @Override
    public void decode(byte[] grayData, int width, int height, int rotationDegrees, DecodeCallback callback) {
        if (grayData == null || width <= 0 || height <= 0) {
            callback.onFailure(new IllegalArgumentException("Invalid gray data or dimensions"));
            return;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.BARCODE_DECODE)) {
            // 将灰度数据转换为Bitmap
            Bitmap bitmap = grayDataToBitmap(grayData, width, height);
            if (bitmap == null) {
                callback.onFailure(new RuntimeException("Failed to convert gray data to bitmap"));
                return;
            }

            decode(bitmap, rotationDegrees, callback);
            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode gray data", e);
            callback.onFailure(e);
        }
    }

    @Override
    public void decodeYuv(byte[] yuvData, int width, int height, int rotationDegrees, DecodeCallback callback) {
        if (yuvData == null || width <= 0 || height <= 0) {
            callback.onFailure(new IllegalArgumentException("Invalid YUV data or dimensions"));
            return;
        }

        try (PerformanceTracker.Timer timer = new PerformanceTracker.Timer(PerformanceTracker.MetricType.BARCODE_DECODE)) {
            // 将YUV数据转换为Bitmap
            Bitmap bitmap = yuvDataToBitmap(yuvData, width, height);
            if (bitmap == null) {
                callback.onFailure(new RuntimeException("Failed to convert YUV data to bitmap"));
                return;
            }

            decode(bitmap, rotationDegrees, callback);
            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode YUV data", e);
            callback.onFailure(e);
        }
    }

    private void processImage(InputImage image, DecodeCallback callback) {
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    List<BarcodeResult> results = new ArrayList<>();
                    for (Barcode barcode : barcodes) {
                        BarcodeResult result = convertToResult(barcode);
                        if (result != null) {
                            results.add(result);
                        }
                    }
                    Log.d(TAG, "MLKit decoded " + results.size() + " barcodes");
                    callback.onSuccess(results);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "MLKit barcode detection failed", e);
                    callback.onFailure(e);
                });
    }

    private BarcodeResult convertToResult(Barcode barcode) {
        String content = barcode.getRawValue();
        if (content == null || content.isEmpty()) {
            content = barcode.getDisplayValue();
        }
        if (content == null) {
            return null;
        }

        String format = getFormatName(barcode.getFormat());

        // 边界框
        Rect boundingBox = barcode.getBoundingBox();
        RectF rectF = null;
        PointF centerPoint = null;

        if (boundingBox != null) {
            rectF = new RectF(boundingBox);
            centerPoint = new PointF(rectF.centerX(), rectF.centerY());
        }

        // 角点
        android.graphics.Point[] corners = barcode.getCornerPoints();
        PointF[] cornerPoints = null;
        if (corners != null && corners.length > 0) {
            cornerPoints = new PointF[corners.length];
            float sumX = 0, sumY = 0;
            for (int i = 0; i < corners.length; i++) {
                cornerPoints[i] = new PointF(corners[i].x, corners[i].y);
                sumX += corners[i].x;
                sumY += corners[i].y;
            }
            // 使用角点中心作为更精确的中心点
            centerPoint = new PointF(sumX / corners.length, sumY / corners.length);
        }

        return new BarcodeResult(content, format, centerPoint, rectF, cornerPoints);
    }

    private String getFormatName(int format) {
        switch (format) {
            case Barcode.FORMAT_QR_CODE:
                return "QR_CODE";
            case Barcode.FORMAT_EAN_13:
                return "EAN_13";
            case Barcode.FORMAT_EAN_8:
                return "EAN_8";
            case Barcode.FORMAT_UPC_A:
                return "UPC_A";
            case Barcode.FORMAT_UPC_E:
                return "UPC_E";
            case Barcode.FORMAT_CODE_128:
                return "CODE_128";
            case Barcode.FORMAT_CODE_39:
                return "CODE_39";
            case Barcode.FORMAT_CODE_93:
                return "CODE_93";
            case Barcode.FORMAT_CODABAR:
                return "CODABAR";
            case Barcode.FORMAT_ITF:
                return "ITF";
            case Barcode.FORMAT_DATA_MATRIX:
                return "DATA_MATRIX";
            case Barcode.FORMAT_PDF417:
                return "PDF417";
            case Barcode.FORMAT_AZTEC:
                return "AZTEC";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * 将灰度数据转换为Bitmap
     */
    private Bitmap grayDataToBitmap(byte[] grayData, int width, int height) {
        try {
            // 创建ARGB数组
            int[] pixels = new int[width * height];
            for (int i = 0; i < grayData.length; i++) {
                int gray = grayData[i] & 0xFF;
                pixels[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            }
            
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert gray data to bitmap", e);
            return null;
        }
    }

    /**
     * 将YUV数据转换为Bitmap
     */
    private Bitmap yuvDataToBitmap(byte[] yuvData, int width, int height) {
        try {
            // 简化实现：只使用Y通道（灰度）
            byte[] grayData = new byte[width * height];
            System.arraycopy(yuvData, 0, grayData, 0, width * height);
            return grayDataToBitmap(grayData, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert YUV data to bitmap", e);
            return null;
        }
    }

    @Override
    public void release() {
        try {
            scanner.close();
            Log.d(TAG, "MLKit BarcodeScanner released");
        } catch (Exception e) {
            Log.e(TAG, "Failed to release MLKit BarcodeScanner", e);
        }
    }
}
