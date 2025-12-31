package com.urovo.templatedetector.ocr.paddle;

/**
 * PaddleOCR引擎实现
 * 基于 Paddle-Lite 引擎，支持 .nb 格式模型
 */
//public class PaddleOCREngine implements OCREngine {
//
//    private static final String TAG = "PaddleOCREngine";
//
//    // 模型路径配置 (assets目录下)
//    private static final String MODEL_PATH = "models/ch_PP-OCRv4";
//    private static final String DET_MODEL = "det.nb";
//    private static final String REC_MODEL = "rec.nb";
//    private static final String CLS_MODEL = "cls.nb";
//
//    private final Context context;
//    private OCR ocr;
//    private volatile boolean initialized = false;
//
//    public PaddleOCREngine(Context context) {
//        this.context = context.getApplicationContext();
//    }
//
//    @Override
//    public boolean initialize() {
//        if (initialized) {
//            return true;
//        }
//
//        try {
//            ocr = new OCR(context);
//
//            OcrConfig config = new OcrConfig();
//            config.setModelPath(MODEL_PATH);
//            config.setDetModelFilename(DET_MODEL);
//            config.setRecModelFilename(REC_MODEL);
//            config.setClsModelFilename(CLS_MODEL);
//
//            // 运行全部模型：检测+分类+识别
//            config.setRunDet(true);
//            config.setRunCls(true);
//            config.setRunRec(true);
//
//            // 性能设置：不绑定特定核心，由系统调度，避免在未知CPU架构上崩溃
//            config.setCpuPowerMode(CpuPowerMode.LITE_POWER_NO_BIND);
//
//            // 不绘制文字位置框
//            config.setDrwwTextPositionBox(false);
//
//            // 直接同步初始化（调用方AppInitializer已在后台线程）
//            boolean success = ocr.initModelSync(config);
//
//            if (success) {
//                initialized = true;
//                Log.d(TAG, "PaddleOCR model initialized successfully");
//            } else {
//                Log.e(TAG, "PaddleOCR model initialization failed");
//            }
//
//            return success;
//
//        } catch (Exception e) {
//            Log.e(TAG, "Failed to initialize PaddleOCR", e);
//            return false;
//        }
//    }
//
//    @Override
//    public List<OCRResult> recognize(Bitmap bitmap) {
//        List<OCRResult> results = new ArrayList<>();
//
//        if (!initialized || ocr == null) {
//            Log.w(TAG, "OCR engine not initialized");
//            return results;
//        }
//
//        if (bitmap == null || bitmap.isRecycled()) {
//            Log.w(TAG, "Invalid bitmap");
//            return results;
//        }
//
//        try {
//            // 直接同步调用（当前已在后台线程）
//            OcrResult ocrResult = ocr.runSync(bitmap);
//            if (ocrResult != null) {
//                results = convertResults(ocrResult);
//                Log.d(TAG, "OCR recognized " + results.size() + " text regions, time: " +
//                        ocrResult.getInferenceTime() + "ms");
//            }
//        } catch (Exception e) {
//            Log.e(TAG, "OCR recognition error", e);
//        }
//
//        return results;
//    }
//
//    @Override
//    public void recognizeAsync(Bitmap bitmap, RecognizeCallback callback) {
//        if (!initialized || ocr == null) {
//            callback.onFailure(new IllegalStateException("OCR engine not initialized"));
//            return;
//        }
//
//        if (bitmap == null || bitmap.isRecycled()) {
//            callback.onFailure(new IllegalArgumentException("Invalid bitmap"));
//            return;
//        }
//
//        // 在后台线程执行同步识别
//        new Thread(() -> {
//            try {
//                OcrResult result = ocr.runSync(bitmap);
//                List<OCRResult> results = convertResults(result);
//                Log.d(TAG, "OCR async recognized " + results.size() + " text regions, time: " +
//                        result.getInferenceTime() + "ms");
//                callback.onSuccess(results);
//            } catch (Exception e) {
//                Log.e(TAG, "OCR async recognition failed", e);
//                callback.onFailure(e);
//            }
//        }, "OCRRecognizeThread").start();
//    }
//
//    /**
//     * 转换OCR结果
//     */
//    private List<OCRResult> convertResults(OcrResult ocrResult) {
//        List<OCRResult> results = new ArrayList<>();
//
//        if (ocrResult == null) {
//            return results;
//        }
//
//        ArrayList<OcrResultModel> rawResults = ocrResult.getOutputRawResult();
//        if (rawResults == null || rawResults.isEmpty()) {
//            return results;
//        }
//
//        for (OcrResultModel model : rawResults) {
//            String text = model.getLabel();
//            if (text == null || text.isEmpty()) {
//                continue;
//            }
//
//            float confidence = model.getConfidence();
//            if (confidence < 0) {
//                confidence = 0;
//            }
//
//            // 获取边界点
//            List<Point> points = model.getPoints();
//            PointF[] cornerPoints = null;
//            RectF boundingBox = null;
//
//            if (points != null && points.size() >= 4) {
//                cornerPoints = new PointF[4];
//                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
//                float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
//
//                for (int i = 0; i < 4; i++) {
//                    Point p = points.get(i);
//                    cornerPoints[i] = new PointF(p.x, p.y);
//                    minX = Math.min(minX, p.x);
//                    minY = Math.min(minY, p.y);
//                    maxX = Math.max(maxX, p.x);
//                    maxY = Math.max(maxY, p.y);
//                }
//
//                boundingBox = new RectF(minX, minY, maxX, maxY);
//            }
//
//            results.add(new OCRResult(text, boundingBox, cornerPoints, confidence));
//        }
//
//        return results;
//    }
//
//    @Override
//    public EngineType getEngineType() {
//        return EngineType.PADDLE_OCR;
//    }
//
//    @Override
//    public String getEngineName() {
//        return "PaddleOCR (Paddle-Lite)";
//    }
//
//    @Override
//    public boolean isInitialized() {
//        return initialized;
//    }
//
//    @Override
//    public void release() {
//        if (ocr != null) {
//            try {
//                ocr.releaseModel();
//                Log.d(TAG, "PaddleOCR model released");
//            } catch (Exception e) {
//                Log.e(TAG, "Error releasing PaddleOCR model", e);
//            }
//            ocr = null;
//        }
//        initialized = false;
//    }
//}
