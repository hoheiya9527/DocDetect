package com.urovo.templatedetector.decoder.kyd;

/**
 * KYD条码解码器实现
 */
//public class KydBarcodeDecoder implements BarcodeDecoder {
//
//    private static final String TAG = "KydBarcodeDecoder";
//    private static final boolean isHoneyWell = true;//解码库 true:霍尼，false：KYD
//    /**
//     * 调试开关：是否保存YUV数据到Download/Scan目录
//     */
//    public static volatile boolean ENABLE_YUV_DEBUG_SAVE = false;
//
//    private final Context context;
//    private final KydDecoderManager decoderManager;
//    private final ExecutorService executor;
//
//    private volatile boolean released = false;
//
//    // 缓冲区复用，减少内存分配
//    private byte[] yuvBuffer;
//    private int lastBufferSize = 0;
//
//    // 解码锁，确保同一时间只有一帧在解码（避免缓冲区复用竞态）
//    private volatile boolean isDecoding = false;
//
//    // 框标记回调接口
//    public interface BoxMarkingCallback {
//        void onROIDetected(List<ROIDetectionResult> roiResults, int imageWidth, int imageHeight, int rotationDegrees);
//
//        void onDecodeSuccess(List<BarcodeResult> decodeResults, int imageWidth, int imageHeight, int rotationDegrees);
//    }
//
//    private BoxMarkingCallback boxMarkingCallback;
//
//    public KydBarcodeDecoder(Context context) {
//        this.context = context.getApplicationContext();
//        this.decoderManager = KydDecoderManager.getInstance();
//        this.executor = Executors.newSingleThreadExecutor();
//
//        if (!decoderManager.isInitialized()) {
//            boolean success = decoderManager.initialize(context, isHoneyWell);
//            if (!success) {
//                Log.e(TAG, "Failed to initialize KYD decoder");
//            }
//        }
//    }
//
//
//    @Override
//    public void decode(Bitmap bitmap, int rotationDegrees, DecodeCallback callback) {
//        decode(bitmap, rotationDegrees, ENABLE_YUV_DEBUG_SAVE, callback);
//    }
//
//    @Override
//    public void decode(Bitmap bitmap, int rotationDegrees, boolean saveDebugFile, DecodeCallback callback) {
//        if (released) {
//            callback.onFailure(new IllegalStateException("Decoder has been released"));
//            return;
//        }
//
//        if (bitmap == null) {
//            callback.onFailure(new IllegalArgumentException("Bitmap is null"));
//            return;
//        }
//
//        executor.execute(() -> {
//            try {
//                byte[] yuvData = PicUtil.bitmapToYUV(bitmap);
//                if (yuvData == null) {
//                    callback.onFailure(new RuntimeException("Failed to convert bitmap to YUV"));
//                    return;
//                }
//
//                // 调试模式：保存YUV数据
//                if (saveDebugFile) {
//                    PicUtil.saveYuvDataAsync(context, yuvData, bitmap.getWidth(), bitmap.getHeight(), "bitmap_decode");
//                }
//
//                List<BarcodeResult> results = decodeYuvData(yuvData, bitmap.getWidth(), bitmap.getHeight(), rotationDegrees);
//                callback.onSuccess(results);
//
//            } catch (Exception e) {
//                callback.onFailure(e);
//            }
//        });
//    }
//
//    @Override
//    public void decode(Image image, int rotationDegrees, DecodeCallback callback) {
//        if (released) {
//            callback.onFailure(new IllegalStateException("Decoder has been released"));
//            return;
//        }
//
//        if (image == null) {
//            callback.onFailure(new IllegalArgumentException("Image is null"));
//            return;
//        }
//
//        // 如果上一帧还在解码，跳过当前帧（避免帧堆积）
//        if (isDecoding) {
//            callback.onSuccess(Collections.emptyList());
//            return;
//        }
//
//        final int width = image.getWidth();
//        final int height = image.getHeight();
//
//        // 原始模式：直接提取 YUV 数据解码
//        // 注意：每次都创建新的缓冲区，避免并发修改问题
//        final byte[] yuvData;
//        try {
//            yuvData = PicUtil.imageToYUV(image, null);  // 不复用缓冲区
//        } catch (Exception e) {
//            callback.onFailure(e);
//            return;
//        }
//
//        if (yuvData == null) {
//            callback.onFailure(new RuntimeException("Failed to convert image to YUV"));
//            return;
//        }
//
//        if (ENABLE_YUV_DEBUG_SAVE) {
//            PicUtil.saveYuvDataAsync(context, yuvData, width, height, "image_decode");
//        }
//
//        isDecoding = true;
//        executor.execute(() -> {
//            try {
//                List<BarcodeResult> results = decodeYuvData(yuvData, width, height, rotationDegrees);
//                callback.onSuccess(results);
//            } catch (Exception e) {
//                callback.onFailure(e);
//            } finally {
//                isDecoding = false;
//            }
//        });
//    }
//
//
//    @Override
//    public void decode(byte[] jpegData, int rotationDegrees, DecodeCallback callback) {
//        if (released) {
//            callback.onFailure(new IllegalStateException("Decoder has been released"));
//            return;
//        }
//
//        if (jpegData == null || jpegData.length == 0) {
//            callback.onFailure(new IllegalArgumentException("JPEG data is null or empty"));
//            return;
//        }
//
//        executor.execute(() -> {
//            try {
//                byte[] dataToProcess = jpegData;
//
//                Bitmap bitmap = BitmapFactory.decodeByteArray(dataToProcess, 0, dataToProcess.length);
//                if (bitmap == null) {
//                    callback.onFailure(new RuntimeException("Failed to decode JPEG data"));
//                    return;
//                }
//
//                int width = bitmap.getWidth();
//                int height = bitmap.getHeight();
//
//                byte[] yuvData = PicUtil.bitmapToYUV(bitmap);
//                bitmap.recycle();
//
//                if (yuvData == null) {
//                    callback.onFailure(new RuntimeException("Failed to convert bitmap to YUV"));
//                    return;
//                }
//
//                // 调试模式：保存YUV数据
//                if (ENABLE_YUV_DEBUG_SAVE) {
//                    PicUtil.saveYuvDataAsync(context, yuvData, width, height, "jpeg_decode");
//                }
//
//                List<BarcodeResult> results = decodeYuvData(yuvData, width, height, rotationDegrees);
//                callback.onSuccess(results);
//
//            } catch (Exception e) {
//                callback.onFailure(e);
//            }
//        });
//    }
//
//    @Override
//    public void decode(byte[] grayData, int width, int height, int rotationDegrees, DecodeCallback callback) {
//        if (released) {
//            callback.onFailure(new IllegalStateException("Decoder has been released"));
//            return;
//        }
//
//        if (grayData == null || grayData.length == 0) {
//            callback.onFailure(new IllegalArgumentException("Gray data is null or empty"));
//            return;
//        }
//
//        int expectedSize = width * height;
//        if (grayData.length != expectedSize) {
//            callback.onFailure(new IllegalArgumentException(
//                    "Gray data size mismatch: expected " + expectedSize + ", got " + grayData.length));
//            return;
//        }
//
//        executor.execute(() -> {
//            try {
//                // 灰度数据直接作为YUV的Y通道传给解码器
//                // KYD解码器只使用Y通道进行条码识别
//                List<BarcodeResult> results = decodeYuvData(grayData, width, height, rotationDegrees);
//                callback.onSuccess(results);
//
//            } catch (Exception e) {
//                callback.onFailure(e);
//            }
//        });
//    }
//
//    @Override
//    public void decodeYuv(byte[] yuvData, int width, int height, int rotationDegrees, DecodeCallback callback) {
//        if (released) {
//            callback.onFailure(new IllegalStateException("Decoder has been released"));
//            return;
//        }
//
//        if (yuvData == null || yuvData.length == 0) {
//            callback.onFailure(new IllegalArgumentException("YUV data is null or empty"));
//            return;
//        }
//
//        // 如果上一帧还在解码，跳过当前帧（避免帧堆积）
//        if (isDecoding) {
//            callback.onSuccess(Collections.emptyList());
//            return;
//        }
//
//        isDecoding = true;
//        executor.execute(() -> {
//            try {
//                List<BarcodeResult> results = decodeYuvData(yuvData, width, height, rotationDegrees);
//                callback.onSuccess(results);
//            } catch (Exception e) {
//                callback.onFailure(e);
//            } finally {
//                isDecoding = false;
//            }
//        });
//    }
//
//    /**
//     * 解码YUV数据
//     */
//    private List<BarcodeResult> decodeYuvData(byte[] yuvData, int width, int height, int rotationDegrees) {
//        if (!decoderManager.isInitialized()) {
//            return Collections.emptyList();
//        }
//
//        List<BarcodeResult> results = new ArrayList<>();
//        List<ROIDetectionResult> roiResults = new ArrayList<>();
//
//        boolean isDetect = false;// 调试,false为不处理找码
//        // 优先使用检测器+ROI解码（更精确）
/// /        if (isDetect && decoderManager.isDetectorReady()) {
/// /            ArrayList<Recognition> recognitions = decoderManager.detectBarcodes(yuvData, width, height);
/// /
/// /            // 收集ROI检测结果（红框）
/// /            if (recognitions != null && !recognitions.isEmpty()) {
/// /                for (Recognition recognition : recognitions) {
/// /                    int[] symBounds = recognition.getSymBounds();
/// /                    Log.d(TAG, "symBounds:" + Arrays.toString(symBounds));
/// /                    ROIDetectionResult roiResult = convertToROIResult(recognition, width, height, rotationDegrees);
/// /                    if (roiResult != null) {
/// /                        roiResults.add(roiResult);
/// /                    }
/// /                }
/// /
/// /                // 通知ROI检测结果
/// /                if (boxMarkingCallback != null && !roiResults.isEmpty()) {
/// /                    boxMarkingCallback.onROIDetected(roiResults, width, height, rotationDegrees);
/// /                }
/// /
/// /                // 对ROI区域进行解码
/// /                ArrayList<DecodeResult> decodeResults = decoderManager.decodeROIRegions(yuvData, width, height, recognitions);
/// /
/// /                if (decodeResults != null) {
/// /                    for (int i = 0; i < decodeResults.size(); i++) {
/// /                        DecodeResult decodeResult = decodeResults.get(i);
/// /                        Log.d(TAG, "decodeResult:" + new String(decodeResult.getBarcodeDataBytes()));
/// /
/// /                        Recognition recognition = i < recognitions.size() ? recognitions.get(i) : null;
/// /                        ROIDetectionResult roiResult = i < roiResults.size() ? roiResults.get(i) : null;
/// /
/// /                        BarcodeResult result = convertToResult(decodeResult, recognition, roiResult, width, height, rotationDegrees);
/// /                        if (result != null) {
/// /                            results.add(result);
/// /                        }
/// /                    }
/// /                }
/// /            }
/// /        }
//
//        // 如果检测器不可用或未检测到，尝试直接解码
//        if (!isDetect) {
//            Log.d(TAG, "decodeImage: " + width + " x " + height);
//            ArrayList<DecodeResult> decodeResults = decoderManager.decodeImage(yuvData, width, height);
//            if (decodeResults != null) {
//                for (int i = 0; i < decodeResults.size(); i++) {
//                    DecodeResult decodeResult = decodeResults.get(i);
//                    Log.d(TAG, "decodeResult[" + decodeResult.getCodeId() + "]:" + new String(decodeResult.getBarcodeDataBytes()));
//                    int[] barcodeBounds = decodeResult.getBarcodeBounds();
//                    Log.d(TAG, "barcodeBounds: " + (barcodeBounds != null ? Arrays.toString(barcodeBounds) : "null")
//                            + ", length=" + (barcodeBounds != null ? barcodeBounds.length : 0));
//
//                    // barcodeBounds 转结果识别区域
//                    // SDK 可能返回4个值 [left, top, right, bottom] 或8个值 [x0,y0,x1,y1,x2,y2,x3,y3]
//                    int[] symBounds = convertToSymBounds(barcodeBounds);
//
//                    Recognition recognition = new Recognition();
//                    recognition.setSymBounds(symBounds);
//                    recognition.setLabel(decodeResult.getCodeId());
//
//                    BarcodeResult result = convertToResult(decodeResult, recognition, null, width, height, rotationDegrees);
//                    if (result != null) {
//                        results.add(result);
//                    }
//                }
//            }
//        }
//
//        // 通知解码成功结果（绿框）
//        if (boxMarkingCallback != null && !results.isEmpty()) {
//            boxMarkingCallback.onDecodeSuccess(results, width, height, rotationDegrees);
//        }
//
//        return results;
//    }
//
//
//    /**
//     * 将Recognition转换为ROI检测结果
//     */
//    private ROIDetectionResult convertToROIResult(Recognition recognition, int imgWidth, int imgHeight, int rotationDegrees) {
//        if (recognition == null) {
//            return null;
//        }
//
//        int[] bounds = recognition.getSymBounds();
//        if (bounds == null || bounds.length < 8) {
//            return null;
//        }
//
//        // 验证ROI有效性
//        if (!isValidBounds(bounds, imgWidth, imgHeight)) {
//            Log.w(TAG, "Invalid ROI bounds detected, skipping");
//            return null;
//        }
//
//        // 计算旋转后的图像尺寸
//        int rotatedWidth, rotatedHeight;
//        if (rotationDegrees == 90 || rotationDegrees == 270) {
//            rotatedWidth = imgHeight;
//            rotatedHeight = imgWidth;
//        } else {
//            rotatedWidth = imgWidth;
//            rotatedHeight = imgHeight;
//        }
//
//        // 提取4个角点并转换到旋转后的坐标系
//        PointF[] cornerPoints = new PointF[4];
//        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
//        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
//
//        for (int i = 0; i < 4; i++) {
//            float x = bounds[i * 2];
//            float y = bounds[i * 2 + 1];
//
//            // 根据旋转角度转换坐标到旋转后的坐标系
//            PointF transformed = transformPoint(x, y, imgWidth, imgHeight, rotationDegrees);
//            cornerPoints[i] = transformed;
//
//            minX = Math.min(minX, transformed.x);
//            minY = Math.min(minY, transformed.y);
//            maxX = Math.max(maxX, transformed.x);
//            maxY = Math.max(maxY, transformed.y);
//        }
//
//        // 计算边界框
//        RectF boundingBox = new RectF(minX, minY, maxX, maxY);
//        // 获取置信度（如果Recognition有提供的话，否则使用默认值）
//        float confidence = 1.0f; // KYD SDK可能没有直接提供置信度，使用默认值
//        return new ROIDetectionResult(boundingBox, cornerPoints, confidence);
//    }
//
//
//    /**
//     * 将KYD解码结果转换为BarcodeResult
//     * 注意：返回的坐标是旋转后的坐标系（与MLKit行为一致）
//     */
//    private BarcodeResult convertToResult(DecodeResult decodeResult, Recognition recognition, ROIDetectionResult roiResult, int imgWidth, int imgHeight, int rotationDegrees) {
//        if (decodeResult == null) {
//            return null;
//        }
//
//        byte[] dataBytes = decodeResult.getBarcodeDataBytes();
//        if (dataBytes == null || dataBytes.length == 0) {
//            return null;
//        }
//
//        String content = new String(dataBytes);
//        String format = mapBarcodeType(decodeResult.getCodeId());
//        Log.d(TAG, "convertToResult: content=" + content + ", format=" + format);
//
//        RectF boundingBox = null;
//        PointF centerPoint = null;
//        PointF[] cornerPoints = null;
//
//        // 获取ROI信息
//        RectF roiBox = null;
//        PointF[] roiCornerPoints = null;
//        if (roiResult != null) {
//            roiBox = roiResult.getBoundingBox();
//            roiCornerPoints = roiResult.getCornerPoints();
//        }
//
//        // 从Recognition获取解码成功后的位置信息
//        if (recognition != null) {
//            int[] bounds = recognition.getSymBounds();
//            Log.d(TAG, "convertToResult: recognition bounds=" + (bounds != null ? java.util.Arrays.toString(bounds) : "null"));
//            if (bounds != null && bounds.length >= 8) {
//                // 验证ROI有效性：检查坐标是否在图像边界内
//                if (!isValidBounds(bounds, imgWidth, imgHeight)) {
//                    Log.w(TAG, "Invalid ROI bounds detected, skipping");
//                    return null;
//                }
//
//                // 计算旋转后的图像尺寸
//                int rotatedWidth, rotatedHeight;
//                if (rotationDegrees == 90 || rotationDegrees == 270) {
//                    rotatedWidth = imgHeight;
//                    rotatedHeight = imgWidth;
//                } else {
//                    rotatedWidth = imgWidth;
//                    rotatedHeight = imgHeight;
//                }
//
//                // 提取4个角点并转换到旋转后的坐标系
//                cornerPoints = new PointF[4];
//                float sumX = 0, sumY = 0;
//
//                for (int i = 0; i < 4; i++) {
//                    float x = bounds[i * 2];
//                    float y = bounds[i * 2 + 1];
//
//                    // 根据旋转角度转换坐标到旋转后的坐标系
//                    PointF transformed = transformPoint(x, y, imgWidth, imgHeight, rotationDegrees);
//                    cornerPoints[i] = transformed;
//                    sumX += transformed.x;
//                    sumY += transformed.y;
//                }
//
//                // 计算中心点
//                centerPoint = new PointF(sumX / 4, sumY / 4);
//
//                // 计算边界框
//                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
//                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
//                for (PointF point : cornerPoints) {
//                    minX = Math.min(minX, point.x);
//                    minY = Math.min(minY, point.y);
//                    maxX = Math.max(maxX, point.x);
//                    maxY = Math.max(maxY, point.y);
//                }
//                boundingBox = new RectF(minX, minY, maxX, maxY);
//
////                // 验证边界框尺寸合理性（使用旋转后的尺寸）
////                float boxWidth = boundingBox.width();
////                float boxHeight = boundingBox.height();
////                if (!isValidBoundingBoxSize(boxWidth, boxHeight, rotatedWidth, rotatedHeight)) {
////                    Log.w(TAG, "Invalid bounding box size: " + boxWidth + "x" + boxHeight);
////                    return null;
////                }
//            }
//        }
//
//        return new BarcodeResult(content, format, centerPoint, boundingBox, cornerPoints, roiBox, roiCornerPoints, true);
//    }
//
//    /**
//     * 验证ROI边界是否有效
//     *
//     * @param bounds    8个坐标值 [x0,y0, x1,y1, x2,y2, x3,y3]
//     * @param imgWidth  图像宽度
//     * @param imgHeight 图像高度
//     * @return 是否有效
//     */
//    private boolean isValidBounds(int[] bounds, int imgWidth, int imgHeight) {
////        // 允许一定的边界容差（5%）
////        int toleranceX = (int) (imgWidth * 0.05f);
////        int toleranceY = (int) (imgHeight * 0.05f);
////
////        for (int i = 0; i < 4; i++) {
////            int x = bounds[i*2];
////            int y = bounds[i*2+1];
////
////            // 检查坐标是否严重超出边界
////            if (x < -toleranceX || x > imgWidth + toleranceX ||
////                y < -toleranceY || y > imgHeight + toleranceY) {
////                return false;
////            }
////        }
//        return true;
//    }
//
//    /**
//     * 将 barcodeBounds 转换为 symBounds 格式（8个值的角点坐标）
//     * SDK 可能返回：
//     * - 4个值：[left, top, right, bottom] 矩形格式
//     * - 8个值：[x0,y0, x1,y1, x2,y2, x3,y3] 角点格式
//     * - 10个值：[x0,y0, x1,y1, x2,y2, x3,y3, cx,cy] 角点+中心点格式
//     *
//     * @param barcodeBounds 原始边界数据
//     * @return 8个值的角点坐标，顺序为：左上、右上、右下、左下
//     */
//    private int[] convertToSymBounds(int[] barcodeBounds) {
//        if (barcodeBounds == null || barcodeBounds.length == 0) {
//            return null;
//        }
//
//        if (barcodeBounds.length >= 10) {
//            // 10个值：前8个是角点，后2个是中心点，取前8个
//            return new int[]{
//                    barcodeBounds[0], barcodeBounds[1],
//                    barcodeBounds[2], barcodeBounds[3],
//                    barcodeBounds[4], barcodeBounds[5],
//                    barcodeBounds[6], barcodeBounds[7]
//            };
//        }
//
//        if (barcodeBounds.length >= 8) {
//            // 已经是8个值的角点格式，直接返回
//            return barcodeBounds;
//        }
//
//        if (barcodeBounds.length >= 4) {
//            // 4个值的矩形格式 [left, top, right, bottom]
//            // 转换为8个值的角点格式 [x0,y0, x1,y1, x2,y2, x3,y3]
//            // 顺序：左上、右上、右下、左下
//            int left = barcodeBounds[0];
//            int top = barcodeBounds[1];
//            int right = barcodeBounds[2];
//            int bottom = barcodeBounds[3];
//
//            return new int[]{
//                    left, top,      // 左上
//                    right, top,     // 右上
//                    right, bottom,  // 右下
//                    left, bottom    // 左下
//            };
//        }
//
//        Log.w(TAG, "Invalid barcodeBounds length: " + barcodeBounds.length);
//        return null;
//    }
//
////    /**
////     * 验证边界框尺寸是否合理
////     */
////    private boolean isValidBoundingBoxSize(float width, float height, int imgWidth, int imgHeight) {
////        // 最小尺寸：图像尺寸的1%
////        float minSize = Math.min(imgWidth, imgHeight) * 0.01f;
////        // 最大尺寸：图像尺寸的90%
////        float maxWidth = imgWidth * 0.9f;
////        float maxHeight = imgHeight * 0.9f;
////
////        return width >= minSize && height >= minSize &&
////               width <= maxWidth && height <= maxHeight;
////    }
//
//    /**
//     * 根据旋转角度转换坐标点
//     */
//    private PointF transformPoint(float x, float y, int imgWidth, int imgHeight, int rotationDegrees) {
//        switch (rotationDegrees) {
//            case 90:
//                return new PointF(imgHeight - y, x);
//            case 180:
//                return new PointF(imgWidth - x, imgHeight - y);
//            case 270:
//                return new PointF(y, imgWidth - x);
//            default:
//                return new PointF(x, y);
//        }
//    }
//
//    /**
//     * 映射条码类型
//     */
//    private String mapBarcodeType(int type) {
//        // 根据KYD SDK的类型定义映射
//        return SymbologyType.getSymbologyName(type);
//    }
//
//
//    @Override
//    public void release() {
//        released = true;
//        isDecoding = false;
//        executor.shutdown();
//        synchronized (this) {
//            yuvBuffer = null;
//            lastBufferSize = 0;
//        }
//        Log.d(TAG, "KydBarcodeDecoder released");
//    }
//
//    /**
//     * 设置框标记回调
//     */
//    public void setBoxMarkingCallback(BoxMarkingCallback callback) {
//        this.boxMarkingCallback = callback;
//    }
//}
