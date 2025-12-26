package com.example.docdetect;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.camera.core.ExperimentalGetImage
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final long STABLE_DURATION_MS = 800; // 稳定时间

    private PreviewView previewView;
    private DocumentDetectorView detectorView;
    private DocumentResultView resultView;
    private Button btnCancel;
    private ExecutorService cameraExecutor;

    private TextRecognizer textRecognizer;
    private BarcodeScanner barcodeScanner;
    private ProcessCameraProvider cameraProvider;

    private Rect lastDetectedRect;
    private long lastDetectionTime;
    private Handler handler;
    private boolean isCapturing = false;

    private Size imageAnalysisSize;
    private int imageRotation;
    private byte[] lastImageData;
    private byte[] yuvConvertBuffer;
    private int lastImageWidth;
    private int lastImageHeight;

    // 复用List避免每帧创建
    private final List<Rect> allRectsBuffer = new ArrayList<>();

    // 保存最后一次检测到的文本块和条码（用于结果页面）
    private final List<DetectedItem> lastDetectedItems = new ArrayList<>();

    // 平滑处理
    private static final int SMOOTH_WINDOW_SIZE = 5;
    private final Rect[] rectHistory = new Rect[SMOOTH_WINDOW_SIZE];
    private int rectHistoryIndex = 0;
    private int rectHistoryCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        detectorView = findViewById(R.id.detectorView);
        resultView = findViewById(R.id.resultView);
        btnCancel = findViewById(R.id.btnCancel);

        btnCancel.setOnClickListener(v -> cancelAndRestart());

        cameraExecutor = Executors.newSingleThreadExecutor();
        textRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        barcodeScanner = BarcodeScanning.getClient();
        handler = new Handler(Looper.getMainLooper());

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 如果正在处理文档，不要重启相机
        if (isCapturing) {
            Log.d(TAG, "正在处理文档，跳过相机启动");
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机启动失败", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        this.cameraProvider = cameraProvider;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

        builder.setResolutionSelector(
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                                new androidx.camera.core.resolutionselector.ResolutionStrategy(
                                        new Size(2560, 1440),
                                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                        )
                        .build()
        );

        ImageAnalysis imageAnalysis = builder.build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }


    private void analyzeImage(ImageProxy imageProxy) {
        if (isCapturing) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        imageAnalysisSize = new Size(imageProxy.getWidth(), imageProxy.getHeight());
        imageRotation = imageProxy.getImageInfo().getRotationDegrees();

        // 直接从MediaImage创建InputImage，无需手动YUV转换
        InputImage image = InputImage.fromMediaImage(mediaImage, imageRotation);

        // 并行检测文本和条码
        Task<Text> textTask = textRecognizer.process(image);
        Task<List<Barcode>> barcodeTask = barcodeScanner.process(image);

        Tasks.whenAllComplete(textTask, barcodeTask)
                .addOnCompleteListener(task -> {
                    allRectsBuffer.clear();
                    lastDetectedItems.clear();

                    // 处理文本结果
                    if (textTask.isSuccessful() && textTask.getResult() != null) {
                        Text text = textTask.getResult();
                        for (Text.TextBlock block : text.getTextBlocks()) {
                            Rect boundingBox = block.getBoundingBox();
                            if (boundingBox != null) {
                                allRectsBuffer.add(boundingBox);
                                lastDetectedItems.add(new DetectedItem(
                                        DetectedItem.Type.TEXT_BLOCK,
                                        new Rect(boundingBox),
                                        block.getText()
                                ));
                            }
                        }
                    }

                    // 处理条码结果
                    if (barcodeTask.isSuccessful() && barcodeTask.getResult() != null) {
                        List<Barcode> barcodes = barcodeTask.getResult();
                        for (Barcode barcode : barcodes) {
                            Rect boundingBox = barcode.getBoundingBox();
                            if (boundingBox != null) {
                                allRectsBuffer.add(boundingBox);
                                DetectedItem.Type type = barcode.getFormat() == Barcode.FORMAT_QR_CODE
                                        ? DetectedItem.Type.QR_CODE
                                        : DetectedItem.Type.BARCODE;
                                lastDetectedItems.add(new DetectedItem(
                                        type,
                                        new Rect(boundingBox),
                                        barcode.getRawValue()
                                ));
                            }
                        }
                    }

                    Log.d(TAG, "总共收集到 " + allRectsBuffer.size() + " 个边界框");

                    // 聚合所有边界框
                    Rect documentRect = aggregateAllRects(allRectsBuffer);
                    if (documentRect != null) {
                        // 检测成功，在close之前复制图像数据
                        lastImageData = mediaImageToNV21(mediaImage);
                        lastImageWidth = imageProxy.getWidth();
                        lastImageHeight = imageProxy.getHeight();

                        imageProxy.close();

                        Log.d(TAG, "文档边界: " + documentRect);
                        Rect smoothedRect = smoothRect(documentRect);
                        if (smoothedRect != null) {
                            Rect previewRect = transformRect(smoothedRect);
                            handleDocumentDetected(previewRect, smoothedRect);
                        }
                    } else {
                        clearHistory();
                        runOnUiThread(() -> detectorView.clearRect());
                        lastDetectedRect = null;
                        lastDetectionTime = 0;
                        imageProxy.close();
                    }
                });
    }

    private Rect aggregateAllRects(List<Rect> rects) {
        if (rects.size() < 3) {
            Log.d(TAG, "检测框数量不足: " + rects.size());
            return null;
        }

        int n = rects.size();

        // 计算所有检测框的中心点
        int[] cx = new int[n];
        int[] cy = new int[n];
        for (int i = 0; i < n; i++) {
            Rect r = rects.get(i);
            cx[i] = r.centerX();
            cy[i] = r.centerY();
        }

        // 使用K-NN距离排序找肘部来确定连通阈值
        // K = 4 (DBSCAN推荐: 2*维度-1，2D情况下取4更稳健)
        int K = Math.min(4, n - 1);
        double[] knnDistances = new double[n];

        for (int i = 0; i < n; i++) {
            // 计算点i到所有其他点的距离
            double[] distances = new double[n - 1];
            int idx = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                int dx = cx[i] - cx[j];
                int dy = cy[i] - cy[j];
                distances[idx++] = Math.sqrt(dx * dx + dy * dy);
            }
            // 排序取第K个
            Arrays.sort(distances);
            knnDistances[i] = distances[Math.min(K - 1, distances.length - 1)];
        }

        // 对K-NN距离排序
        double[] sortedKnn = knnDistances.clone();
        Arrays.sort(sortedKnn);

        // 找肘部：计算二阶差分，找最大值点
        double connectThreshold;
        if (n <= 5) {
            // 点太少，直接用最大K-NN距离
            connectThreshold = sortedKnn[n - 1];
        } else {
            int elbowIndex = 0;
            double maxCurvature = 0;
            for (int i = 1; i < n - 1; i++) {
                // 二阶差分（曲率近似）
                double curvature = (sortedKnn[i + 1] - sortedKnn[i]) - (sortedKnn[i] - sortedKnn[i - 1]);
                if (curvature > maxCurvature) {
                    maxCurvature = curvature;
                    elbowIndex = i;
                }
            }
            // 肘部对应的距离值作为阈值，稍微放大一点确保连通
            connectThreshold = sortedKnn[elbowIndex] * 1.5;
        }

        double connectThresholdSq = connectThreshold * connectThreshold;

        Log.d(TAG, "K-NN肘部法阈值: " + (int) connectThreshold);

        // 找密度最高的点作为种子
        int bestSeed = 0;
        int maxNeighbors = 0;
        for (int i = 0; i < n; i++) {
            int neighbors = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                int dx = cx[i] - cx[j];
                int dy = cy[i] - cy[j];
                if (dx * dx + dy * dy <= connectThresholdSq) {
                    neighbors++;
                }
            }
            if (neighbors > maxNeighbors) {
                maxNeighbors = neighbors;
                bestSeed = i;
            }
        }

        // 从种子点开始BFS扩展，收集所有连通的检测框
        boolean[] visited = new boolean[n];
        List<Integer> cluster = new ArrayList<>();
        List<Integer> queue = new ArrayList<>();

        queue.add(bestSeed);
        visited[bestSeed] = true;

        int head = 0;
        while (head < queue.size()) {
            int curr = queue.get(head++);
            cluster.add(curr);

            for (int j = 0; j < n; j++) {
                if (visited[j]) continue;
                int dx = cx[curr] - cx[j];
                int dy = cy[curr] - cy[j];
                if (dx * dx + dy * dy <= connectThresholdSq) {
                    visited[j] = true;
                    queue.add(j);
                }
            }
        }

        Log.d(TAG, "连通聚类: " + cluster.size() + "/" + n);

        if (cluster.size() < 3) {
            return null;
        }

        // 计算聚类边界
        int finalLeft = Integer.MAX_VALUE;
        int finalTop = Integer.MAX_VALUE;
        int finalRight = Integer.MIN_VALUE;
        int finalBottom = Integer.MIN_VALUE;

        List<DetectedItem> filteredItems = new ArrayList<>();

        for (int idx : cluster) {
            if (idx < lastDetectedItems.size()) {
                DetectedItem item = lastDetectedItems.get(idx);
                Rect r = item.rect;
                finalLeft = Math.min(finalLeft, r.left);
                finalTop = Math.min(finalTop, r.top);
                finalRight = Math.max(finalRight, r.right);
                finalBottom = Math.max(finalBottom, r.bottom);
                filteredItems.add(item);
            }
        }

        lastDetectedItems.clear();
        lastDetectedItems.addAll(filteredItems);

        if (finalLeft >= finalRight || finalTop >= finalBottom) {
            return null;
        }

        int padding = 20;
        return new Rect(
                Math.max(0, finalLeft - padding),
                Math.max(0, finalTop - padding),
                finalRight + padding,
                finalBottom + padding
        );
    }

    private Rect smoothRect(Rect newRect) {
        rectHistory[rectHistoryIndex] = newRect;
        rectHistoryIndex = (rectHistoryIndex + 1) % SMOOTH_WINDOW_SIZE;
        rectHistoryCount = Math.min(rectHistoryCount + 1, SMOOTH_WINDOW_SIZE);

        // 第一帧直接返回，不需要等待
        if (rectHistoryCount == 1) {
            return newRect;
        }

        // 计算平均值
        int left = 0, top = 0, right = 0, bottom = 0;
        for (int i = 0; i < rectHistoryCount; i++) {
            if (rectHistory[i] != null) {
                left += rectHistory[i].left;
                top += rectHistory[i].top;
                right += rectHistory[i].right;
                bottom += rectHistory[i].bottom;
            }
        }

        return new Rect(
                left / rectHistoryCount,
                top / rectHistoryCount,
                right / rectHistoryCount,
                bottom / rectHistoryCount
        );
    }

    private void clearHistory() {
        for (int i = 0; i < SMOOTH_WINDOW_SIZE; i++) {
            rectHistory[i] = null;
        }
        rectHistoryIndex = 0;
        rectHistoryCount = 0;
    }

    private Rect transformRect(Rect sourceRect) {
        if (imageAnalysisSize == null) {
            return sourceRect;
        }

        // ML Kit返回的坐标是基于旋转后的图像
        // 需要根据旋转角度确定正确的源尺寸
        int srcWidth, srcHeight;
        if (imageRotation == 90 || imageRotation == 270) {
            srcWidth = imageAnalysisSize.getHeight();
            srcHeight = imageAnalysisSize.getWidth();
        } else {
            srcWidth = imageAnalysisSize.getWidth();
            srcHeight = imageAnalysisSize.getHeight();
        }

        Matrix matrix = new Matrix();

        // 计算从旋转后图像坐标到预览视图坐标的变换
        RectF source = new RectF(0, 0, srcWidth, srcHeight);
        RectF target = new RectF(0, 0, previewView.getWidth(), previewView.getHeight());

        matrix.setRectToRect(source, target, Matrix.ScaleToFit.CENTER);

        RectF rectF = new RectF(sourceRect);
        matrix.mapRect(rectF);

        return new Rect(
                (int) rectF.left,
                (int) rectF.top,
                (int) rectF.right,
                (int) rectF.bottom
        );
    }

    private void handleDocumentDetected(Rect previewRect, Rect originalRect) {
        runOnUiThread(() -> detectorView.setDocumentRect(previewRect));

        // 基于绿框中心位置的相对变化判断稳定性
        // 移动超过绿框尺寸的10%认为是用户在移动，重置计时
        // 否则认为是ML Kit的正常抖动，继续累计时间
        boolean isStable = true;
        if (lastDetectedRect != null) {
            int lastCenterX = lastDetectedRect.centerX();
            int lastCenterY = lastDetectedRect.centerY();
            int currCenterX = previewRect.centerX();
            int currCenterY = previewRect.centerY();

            int deltaX = Math.abs(currCenterX - lastCenterX);
            int deltaY = Math.abs(currCenterY - lastCenterY);

            // 阈值：绿框尺寸的10%
            int thresholdX = previewRect.width() / 10;
            int thresholdY = previewRect.height() / 10;

            if (deltaX > thresholdX || deltaY > thresholdY) {
                isStable = false;
                Log.d(TAG, "位置变化过大: dx=" + deltaX + "/" + thresholdX + ", dy=" + deltaY + "/" + thresholdY);
            }
        }

        long currentTime = System.currentTimeMillis();
        if (isStable) {
            if (lastDetectionTime == 0) {
                lastDetectionTime = currentTime;
                Log.d(TAG, "开始稳定计时");
            } else {
                long elapsed = currentTime - lastDetectionTime;
                Log.d(TAG, "稳定时间: " + elapsed + "ms / " + STABLE_DURATION_MS + "ms");
                if (elapsed >= STABLE_DURATION_MS && !isCapturing) {
                    Log.d(TAG, "触发拍照");
                    captureDocument(originalRect);
                }
            }
        } else {
            lastDetectionTime = 0;
        }

        lastDetectedRect = previewRect;
    }

    private void captureDocument(Rect documentRect) {
        isCapturing = true;
        clearHistory();

        runOnUiThread(() -> detectorView.clearRect());

        if (lastImageData == null || lastDetectedItems.isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "数据丢失", Toast.LENGTH_SHORT).show());
            isCapturing = false;
            return;
        }

        runOnUiThread(() -> {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
            }
        });

        YuvImage yuvImage = new YuvImage(lastImageData, android.graphics.ImageFormat.NV21,
                lastImageWidth, lastImageHeight, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, lastImageWidth, lastImageHeight), 100, out);
        byte[] imageBytes = out.toByteArray();
        Bitmap fullBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

        Bitmap rotatedBitmap;
        if (imageRotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(imageRotation);
            rotatedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0,
                    fullBitmap.getWidth(), fullBitmap.getHeight(), matrix, true);
            fullBitmap.recycle();
        } else {
            rotatedBitmap = fullBitmap;
        }

        List<DetectedItem> items = new ArrayList<>(lastDetectedItems);

        runOnUiThread(() -> {
            previewView.setVisibility(View.GONE);
            detectorView.setVisibility(View.GONE);
            resultView.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.VISIBLE);
            resultView.setResult(rotatedBitmap, documentRect, items);
        });
    }

    private byte[] mediaImageToNV21(Image image) {
        Image.Plane[] planes = image.getPlanes();
        
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        
        byte[] nv21 = new byte[ySize + uSize + vSize];
        
        yBuffer.get(nv21, 0, ySize);
        
        int uvPixelStride = planes[1].getPixelStride();
        
        if (uvPixelStride == 2) {
            vBuffer.get(nv21, ySize, vSize);
        } else {
            if (yuvConvertBuffer == null || yuvConvertBuffer.length < vSize + uSize) {
                yuvConvertBuffer = new byte[vSize + uSize];
            }
            
            vBuffer.get(yuvConvertBuffer, 0, vSize);
            uBuffer.get(yuvConvertBuffer, vSize, uSize);
            
            int uvIndex = ySize;
            for (int i = 0; i < vSize; i++) {
                nv21[uvIndex++] = yuvConvertBuffer[i];
                nv21[uvIndex++] = yuvConvertBuffer[vSize + i];
            }
        }
        
        return nv21;
    }

    private void cancelAndRestart() {
        // 隐藏结果页面和取消按钮
        resultView.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);

        // 显示预览
        previewView.setVisibility(View.VISIBLE);
        detectorView.setVisibility(View.VISIBLE);

        // 重置状态
        isCapturing = false;
        lastDetectedRect = null;
        lastDetectionTime = 0;
        clearHistory();

        // 重新启动相机
        startCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        textRecognizer.close();
        barcodeScanner.close();
    }
}
