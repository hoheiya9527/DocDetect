package com.example.docdetect;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.ArrayList;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 纯ARCore实现的条码AR追踪Activity
 */
public class BarcodeARActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "BarcodeARActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private GLSurfaceView glSurfaceView;
    private Session session;
    private boolean userRequestedInstall = true;

    // 背景渲染
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PointRenderer pointRenderer = new PointRenderer();

    // 条码扫描
    private BarcodeScanner barcodeScanner;
    private long lastScanTime = 0;
    private static final long SCAN_INTERVAL_MS = 200; // 扫描间隔

    // 锚点管理 - 使用同步锁保证原子操作
    private final CopyOnWriteArrayList<AnchorData> anchors = new CopyOnWriteArrayList<>();
    private final Set<String> registeredBarcodes = new HashSet<>(); // 已注册的条码（包括pending和已创建）
    private final Object barcodeLock = new Object();
    private static final int MAX_ANCHORS = 50;
    // 锚点颜色
    private static final float[] ANCHOR_COLOR_TRACKING = {0.0f, 1.0f, 0.0f, 1.0f}; // 绿色 - 正常追踪
    private static final float[] ANCHOR_COLOR_STOPPED = {1.0f, 1.0f, 0.0f, 0.8f}; // 黄色 - 失效
    private static final float ANCHOR_SIZE = 80.0f;

    // 投影矩阵
    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];

    // 图像尺寸（用于坐标转换）
    private int imageWidth = 0;
    private int imageHeight = 0;
    
    // 模糊检测阈值（Laplacian方差，越大越清晰）
    private static final double BLUR_THRESHOLD = 100.0;

    /**
     * 锚点数据
     */
    private static class AnchorData {
        final Anchor anchor;
        final String barcodeValue;
        final long createTime;
        // 当前帧的屏幕坐标（每帧更新）
        float screenX;
        float screenY;
        // 最后一次有效追踪的位置
        float lastValidScreenX;
        float lastValidScreenY;

        AnchorData(Anchor anchor, String barcodeValue) {
            this.anchor = anchor;
            this.barcodeValue = barcodeValue;
            this.createTime = System.currentTimeMillis();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 创建根布局
        FrameLayout rootLayout = new FrameLayout(this);

        // 初始化GLSurfaceView
        glSurfaceView = new GLSurfaceView(this);
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        rootLayout.addView(glSurfaceView);

        // 添加清除按钮
        Button clearButton = new Button(this);
        clearButton.setText("清除");
        clearButton.setTextSize(16);
        clearButton.setTextColor(0xFFFFFFFF);
        clearButton.setAllCaps(false);

        // 圆角背景
        android.graphics.drawable.GradientDrawable buttonBg = new android.graphics.drawable.GradientDrawable();
        buttonBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        buttonBg.setCornerRadius(50);
        buttonBg.setColor(0xAAE53935); // 半透明红色
        clearButton.setBackground(buttonBg);
        clearButton.setPadding(60, 30, 60, 30);
        clearButton.setElevation(8);

        clearButton.setOnClickListener(v -> glSurfaceView.queueEvent(this::clearAllAnchors));
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        buttonParams.bottomMargin = 200;
        rootLayout.addView(clearButton, buttonParams);

        setContentView(rootLayout);

        // 初始化条码扫描器
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called, session=" + session);

        if (!checkCameraPermission()) {
            Log.i(TAG, "Requesting camera permission");
            requestCameraPermission();
            return;
        }

        if (session == null) {
            try {
                // 只在第一次请求安装
                if (userRequestedInstall) {
                    Log.i(TAG, "Checking ARCore install status...");
                    ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance()
                            .requestInstall(this, true);
                    Log.i(TAG, "ARCore install status: " + installStatus);

                    if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                        Log.i(TAG, "ARCore install requested, waiting...");
                        userRequestedInstall = false;
                        return;
                    }
                }

                // 直接尝试创建Session
                Log.i(TAG, "Creating ARCore session...");
                session = new Session(this);
                Log.i(TAG, "Session created: " + session);

                // 选择最高分辨率的相机配置
                CameraConfigFilter filter = new CameraConfigFilter(session)
                        .setFacingDirection(CameraConfig.FacingDirection.BACK);
                List<CameraConfig> cameraConfigs = session.getSupportedCameraConfigs(filter);

                // 按CPU图像分辨率降序排序，选择最高的
                CameraConfig bestConfig = null;
                int maxPixels = 0;
                for (CameraConfig config : cameraConfigs) {
                    int pixels = config.getImageSize().getWidth() * config.getImageSize().getHeight();
                    Log.i(TAG, "CameraConfig: " + config.getImageSize().getWidth() + "x" + config.getImageSize().getHeight());
                    if (pixels > maxPixels) {
                        maxPixels = pixels;
                        bestConfig = config;
                    }
                }
                if (bestConfig != null) {
                    session.setCameraConfig(bestConfig);
                    Log.i(TAG, "Selected camera config: " + bestConfig.getImageSize().getWidth() + "x" + bestConfig.getImageSize().getHeight());
                }

                Config config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);
                session.configure(config);

                Log.i(TAG, "ARCore session configured successfully");

            } catch (UnavailableUserDeclinedInstallationException e) {
                Log.e(TAG, "User declined ARCore installation", e);
                Toast.makeText(this, "需要安装ARCore", Toast.LENGTH_LONG).show();
                finish();
                return;
            } catch (Exception e) {
                Log.e(TAG, "ARCore init error: " + e.getClass().getName() + " - " + e.getMessage(), e);
                Toast.makeText(this, "ARCore错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        try {
            Log.i(TAG, "Resuming session...");
            session.resume();
            Log.i(TAG, "Session resumed");
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available", e);
            Toast.makeText(this, "相机不可用", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        glSurfaceView.onResume();
        Log.i(TAG, "GLSurfaceView resumed");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            glSurfaceView.onPause();
            session.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理所有锚点
        for (AnchorData data : anchors) {
            data.anchor.detach();
        }
        anchors.clear();
        synchronized (barcodeLock) {
            registeredBarcodes.clear();
        }

        if (session != null) {
            session.close();
            session = null;
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }

    // GLSurfaceView.Renderer 实现

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        backgroundRenderer.createOnGlThread(this);
        pointRenderer.createOnGlThread(this);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        if (session != null) {
            session.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
            Frame frame = session.update();
            Camera camera = frame.getCamera();

            // 绘制相机背景
            backgroundRenderer.draw(frame);

            // 检查追踪状态
            if (camera.getTrackingState() == TrackingState.TRACKING) {
                // 获取投影和视图矩阵
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
                camera.getViewMatrix(viewMatrix, 0);

                // 定期扫描条码
                long now = System.currentTimeMillis();
                if (now - lastScanTime > SCAN_INTERVAL_MS) {
                    lastScanTime = now;
                    scanBarcodesFromFrame(frame);
                }

                // 渲染所有锚点
                renderAnchors();
            }

        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available", e);
        }
    }

    /**
     * 渲染所有锚点
     * STOPPED状态显示黄色，TRACKING状态显示绿色，不自动删除
     */
    private void renderAnchors() {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        for (AnchorData data : anchors) {
            Anchor anchor = data.anchor;
            TrackingState state = anchor.getTrackingState();

            try {
                Pose pose = anchor.getPose();
                float[] position = new float[]{
                        pose.tx(), pose.ty(), pose.tz()
                };

                // 根据状态选择颜色：TRACKING绿色，其他黄色
                float[] color = (state == TrackingState.TRACKING) ? ANCHOR_COLOR_TRACKING : ANCHOR_COLOR_STOPPED;
                pointRenderer.draw(position, viewMatrix, projectionMatrix, color, ANCHOR_SIZE);

                // 更新屏幕坐标
                float[] screenPos = worldToScreen(position, viewWidth, viewHeight);
                data.screenX = screenPos[0];
                data.screenY = screenPos[1];
                if (state == TrackingState.TRACKING) {
                    data.lastValidScreenX = screenPos[0];
                    data.lastValidScreenY = screenPos[1];
                }
            } catch (Exception e) {
                // 锚点获取位置失败，跳过渲染但不删除
            }
        }
    }

    /**
     * 清除所有锚点 - 由用户手动触发
     */
    private void clearAllAnchors() {
        for (AnchorData data : anchors) {
            data.anchor.detach();
        }
        anchors.clear();
        synchronized (barcodeLock) {
            registeredBarcodes.clear();
        }
        Log.i(TAG, "All anchors cleared by user");
        runOnUiThread(() -> Toast.makeText(this, "已清除所有锚点", Toast.LENGTH_SHORT).show());
    }

    /**
     * 将3D世界坐标投影到2D屏幕坐标
     */
    private float[] worldToScreen(float[] worldPos, int viewWidth, int viewHeight) {
        float[] viewPos = new float[4];
        float[] projPos = new float[4];

        // 应用视图矩阵
        viewPos[0] = viewMatrix[0] * worldPos[0] + viewMatrix[4] * worldPos[1] + viewMatrix[8] * worldPos[2] + viewMatrix[12];
        viewPos[1] = viewMatrix[1] * worldPos[0] + viewMatrix[5] * worldPos[1] + viewMatrix[9] * worldPos[2] + viewMatrix[13];
        viewPos[2] = viewMatrix[2] * worldPos[0] + viewMatrix[6] * worldPos[1] + viewMatrix[10] * worldPos[2] + viewMatrix[14];
        viewPos[3] = 1.0f;

        // 应用投影矩阵
        projPos[0] = projectionMatrix[0] * viewPos[0] + projectionMatrix[4] * viewPos[1] + projectionMatrix[8] * viewPos[2] + projectionMatrix[12] * viewPos[3];
        projPos[1] = projectionMatrix[1] * viewPos[0] + projectionMatrix[5] * viewPos[1] + projectionMatrix[9] * viewPos[2] + projectionMatrix[13] * viewPos[3];
        projPos[2] = projectionMatrix[2] * viewPos[0] + projectionMatrix[6] * viewPos[1] + projectionMatrix[10] * viewPos[2] + projectionMatrix[14] * viewPos[3];
        projPos[3] = projectionMatrix[3] * viewPos[0] + projectionMatrix[7] * viewPos[1] + projectionMatrix[11] * viewPos[2] + projectionMatrix[15] * viewPos[3];

        // 透视除法，转换到NDC
        if (projPos[3] != 0) {
            projPos[0] /= projPos[3];
            projPos[1] /= projPos[3];
        }

        // NDC到屏幕坐标
        float screenX = (projPos[0] + 1.0f) * 0.5f * viewWidth;
        float screenY = (1.0f - projPos[1]) * 0.5f * viewHeight;

        return new float[]{screenX, screenY};
    }

    /**
     * 检查指定屏幕坐标是否与已有锚点位置重叠
     * @param screenX 屏幕X坐标
     * @param screenY 屏幕Y坐标
     * @param threshold 距离阈值（像素）
     * @return true表示位置已被占用
     */
    private boolean isScreenPositionOccupied(float screenX, float screenY, float threshold) {
        for (AnchorData data : anchors) {
            // 使用最后有效位置进行比较（即使锚点暂停/停止也参与去重）
            float existingX = data.lastValidScreenX != 0 ? data.lastValidScreenX : data.screenX;
            float existingY = data.lastValidScreenY != 0 ? data.lastValidScreenY : data.screenY;

            float dx = existingX - screenX;
            float dy = existingY - screenY;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            Log.d(TAG, "Position check: new(" + String.format("%.1f", screenX) + "," + String.format("%.1f", screenY) +
                    ") vs existing[" + data.barcodeValue + "](" + String.format("%.1f", existingX) + "," + String.format("%.1f", existingY) +
                    ") distance=" + String.format("%.1f", distance) + " threshold=" + String.format("%.1f", threshold));
            if (distance < threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从ARCore Frame中扫描条码
     */
    private void scanBarcodesFromFrame(Frame frame) {
        try {
            Image image = frame.acquireCameraImage();
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                image.close();
                return;
            }

            // 保存图像尺寸
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();

            // 模糊检测：计算Laplacian方差
            double sharpness = calculateSharpness(image);
            if (sharpness < BLUR_THRESHOLD) {
                Log.d(TAG, "Image too blurry, skip. sharpness=" + String.format("%.1f", sharpness));
                image.close();
                return;
            }

            // 使用rotation=0，ML Kit返回原始图像坐标
            InputImage inputImage = InputImage.fromMediaImage(image, 0);

            barcodeScanner.process(inputImage)
                    .addOnSuccessListener(this::onBarcodesDetected)
                    .addOnFailureListener(e -> Log.e(TAG, "Barcode scan failed", e))
                    .addOnCompleteListener(task -> image.close());

        } catch (NotYetAvailableException e) {
            // 图像还没准备好，忽略
        }
    }
    
    /**
     * 计算图像清晰度（Laplacian方差）
     * @return 方差值，越大越清晰
     */
    private double calculateSharpness(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        int rowStride = yPlane.getRowStride();
        
        // 创建灰度Mat（只用Y通道）
        Mat grayMat = new Mat(imageHeight, imageWidth, CvType.CV_8UC1);
        byte[] yData = new byte[yBuffer.remaining()];
        yBuffer.get(yData);
        
        // 处理行步长
        if (rowStride == imageWidth) {
            grayMat.put(0, 0, yData);
        } else {
            for (int i = 0; i < imageHeight; i++) {
                grayMat.put(i, 0, yData, i * rowStride, imageWidth);
            }
        }
        
        // 缩小图像加速计算
        Mat smallMat = new Mat();
        Imgproc.resize(grayMat, smallMat, new org.opencv.core.Size(imageWidth / 4, imageHeight / 4));
        
        // 计算Laplacian
        Mat laplacianMat = new Mat();
        Imgproc.Laplacian(smallMat, laplacianMat, CvType.CV_64F);
        
        // 计算方差
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(laplacianMat, mean, stddev);
        double variance = Math.pow(stddev.get(0, 0)[0], 2);
        
        // 释放资源
        grayMat.release();
        smallMat.release();
        laplacianMat.release();
        mean.release();
        stddev.release();
        
        return variance;
    }

    /**
     * 条码检测回调
     */
    private void onBarcodesDetected(List<Barcode> barcodes) {
        if (barcodes.isEmpty()) {
            return;
        }

        Log.i(TAG, "Detected " + barcodes.size() + " barcodes in this frame");

        for (Barcode barcode : barcodes) {
            String value = barcode.getRawValue();
            if (value == null || value.isEmpty()) continue;

            // 获取条码中心点（原始图像像素坐标）
            android.graphics.Rect bounds = barcode.getBoundingBox();
            if (bounds == null) {
                continue;
            }

            float centerX = bounds.centerX();
            float centerY = bounds.centerY();

            Log.i(TAG, "Barcode detected: " + value + " at pixel(" + centerX + ", " + centerY + ")");

            // 计算条码尺寸（用于动态阈值）
            float barcodeWidth = bounds.width();
            float barcodeHeight = bounds.height();

            // 在GL线程中创建或更新锚点
            final String fv = value;
            final float fx = centerX;
            final float fy = centerY;
            final float fw = barcodeWidth;
            final float fh = barcodeHeight;

            glSurfaceView.queueEvent(() -> createOrUpdateAnchor(fx, fy, fw, fh, fv));
        }
    }

    /**
     * 根据条码值查找已有锚点
     */
    private AnchorData findAnchorByValue(String barcodeValue) {
        for (AnchorData data : anchors) {
            if (data.barcodeValue.equals(barcodeValue)) {
                return data;
            }
        }
        return null;
    }

    /**
     * 创建或更新锚点 - 必须在GL线程调用
     * 如果同一条码的锚点已存在且为STOPPED状态，则删除旧的并创建新的
     */
    private void createOrUpdateAnchor(float pixelX, float pixelY, float barcodeWidth, float barcodeHeight, String barcodeValue) {
        if (session == null || imageWidth == 0 || imageHeight == 0) {
            return;
        }

        try {
            Frame frame = session.update();
            Camera camera = frame.getCamera();
            if (camera.getTrackingState() != TrackingState.TRACKING) {
                return;
            }

            int viewWidth = glSurfaceView.getWidth();
            int viewHeight = glSurfaceView.getHeight();

            // 坐标转换
            float normalizedX = pixelX / imageWidth;
            float normalizedY = pixelY / imageHeight;
            float[] normalizedCoords = new float[]{normalizedX, normalizedY};
            float[] viewCoords = new float[2];
            frame.transformCoordinates2d(
                    Coordinates2d.IMAGE_NORMALIZED,
                    normalizedCoords,
                    Coordinates2d.VIEW,
                    viewCoords
            );
            float screenX = viewCoords[0];
            float screenY = viewCoords[1];

            // 查找已有锚点
            AnchorData existingAnchor = findAnchorByValue(barcodeValue);
            
            if (existingAnchor != null) {
                // 已有锚点
                if (existingAnchor.anchor.getTrackingState() == TrackingState.STOPPED) {
                    // 旧锚点已失效，删除并重新创建
                    existingAnchor.anchor.detach();
                    anchors.remove(existingAnchor);
                    Log.i(TAG, "Replacing STOPPED anchor for: " + barcodeValue);
                } else {
                    // 锚点仍有效，不需要更新
                    return;
                }
            } else {
                // 新条码，检查位置是否被其他条码占用
                float minBarcodeDim = Math.min(barcodeWidth, barcodeHeight);
                float avgScale = ((float) viewWidth / imageWidth + (float) viewHeight / imageHeight) / 2.0f;
                float threshold = Math.max(minBarcodeDim * avgScale * 0.5f, 20.0f);
                
                if (isScreenPositionOccupied(screenX, screenY, threshold)) {
                    Log.i(TAG, "Position occupied, skip: " + barcodeValue);
                    return;
                }
                
                // 注册新条码
                synchronized (barcodeLock) {
                    registeredBarcodes.add(barcodeValue);
                }
            }

            // 创建新锚点
            float approximateDistanceMeters = 0.5f;
            List<HitResult> hitResults = frame.hitTestInstantPlacement(screenX, screenY, approximateDistanceMeters);

            if (hitResults.isEmpty()) {
                Log.w(TAG, "hitTestInstantPlacement failed for: " + barcodeValue);
                return;
            }

            InstantPlacementPoint point = (InstantPlacementPoint) hitResults.get(0).getTrackable();
            Anchor anchor = point.createAnchor(point.getPose());

            if (anchor != null) {
                if (anchors.size() >= MAX_ANCHORS) {
                    Log.w(TAG, "Max anchors reached, skip: " + barcodeValue);
                    anchor.detach();
                    return;
                }
                AnchorData anchorData = new AnchorData(anchor, barcodeValue);
                anchorData.screenX = screenX;
                anchorData.screenY = screenY;
                anchorData.lastValidScreenX = screenX;
                anchorData.lastValidScreenY = screenY;
                anchors.add(anchorData);
                Log.i(TAG, "Anchor created for: " + barcodeValue + ", total: " + anchors.size());
            }

        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create anchor: " + e.getMessage(), e);
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
