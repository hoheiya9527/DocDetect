package com.urovo.templatedetector.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.urovo.templatedetector.R;
import com.urovo.templatedetector.camera.CameraController;
import com.urovo.templatedetector.detector.LabelDetector;
import com.urovo.templatedetector.init.AppInitializer;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.model.DetectionResult;
import com.urovo.templatedetector.util.ImageEnhancer;
import com.urovo.templatedetector.view.CameraPreviewLayout;
import com.urovo.templatedetector.view.CameraSettingsDialog;

import org.opencv.core.Mat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * 模板捕获界面
 * 用于拍摄面单并创建模板
 */
public class TemplateCaptureActivity extends AppCompatActivity {

    private static final String TAG = "TemplateCaptureActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String RESULT_IMAGE_PATH = "image_path";

    private CameraPreviewLayout cameraPreviewLayout;
    private TextView guidanceText;
    private MaterialButton btnCapture;
    private MaterialButton btnRetake;
    private MaterialButton btnConfirm;

    private CameraController cameraController;
    private LabelDetector labelDetector;

    private long categoryId = -1;
    private Bitmap correctedBitmap;
    private String savedImagePath;

    // 检测状态
    private volatile boolean isProcessingFrame = false;
    private volatile boolean isAutoCapturing = false; // 防止重复触发自动捕获
    private DetectionResult lastDetectionResult;
    private byte[] capturedRgbaData;
    private int capturedWidth;
    private int capturedHeight;
    private int capturedRotation;

    // 自动捕获设置
    private boolean autoCaptureEnabled = true;
    private double autoCaptureThreshold = 0.998;

    // 自动捕获稳定性检测
    private static final int STABLE_FRAME_COUNT = 5; // 需要连续稳定的帧数
    private int stableFrameCounter = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_template_capture);

        categoryId = getIntent().getLongExtra(EXTRA_CATEGORY_ID, -1);

        AppInitializer initializer = AppInitializer.getInstance(this);
        if (!initializer.isInitialized()) {
            Toast.makeText(this, R.string.initializing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        labelDetector = initializer.getLabelDetector();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();

        if (checkCameraPermission()) {
            initCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void initViews() {
        cameraPreviewLayout = findViewById(R.id.cameraPreviewLayout);
        guidanceText = findViewById(R.id.guidanceText);
        btnCapture = findViewById(R.id.btnCapture);
        btnRetake = findViewById(R.id.btnRetake);
        btnConfirm = findViewById(R.id.btnConfirm);

        btnCapture.setOnClickListener(v -> captureImage());
        btnRetake.setOnClickListener(v -> retake());
        btnConfirm.setOnClickListener(v -> confirmAndProceed());

        // 设置设置按钮点击监听
        cameraPreviewLayout.setOnSettingsClickListener(this::showSettingsDialog);

        // 显示置信度指示器和自动捕获状态
        cameraPreviewLayout.setConfidenceIndicatorVisible(true);
        cameraPreviewLayout.setAutoCaptureIndicatorVisible(true);
        cameraPreviewLayout.updateAutoCaptureState(autoCaptureEnabled);

        showPreviewMode();
    }

    private void initCamera() {
        cameraController = new CameraController(this);
        cameraController.setFrameCallback(this::processFrame);
        cameraPreviewLayout.setCameraController(cameraController, this);
        
        // 同步自动捕获设置
        CameraSettings settings = cameraController.getCurrentSettings();
        if (settings != null) {
            autoCaptureEnabled = settings.isAutoCapture();
            autoCaptureThreshold = settings.getAutoCaptureThreshold();
            cameraPreviewLayout.updateAutoCaptureState(autoCaptureEnabled);
        }
    }

    private void processFrame(androidx.camera.core.ImageProxy image, int rotationDegrees) {
        if (isProcessingFrame) {
            image.close();
            return;
        }

        isProcessingFrame = true;

        try {
            byte[] rgbaData = ImageEnhancer.extractRgba(image);
            if (rgbaData == null) {
                image.close();
                isProcessingFrame = false;
                return;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            DetectionResult result = labelDetector.detect(image, rotationDegrees);

            if (result != null && result.isDetected() && result.getConfidence() > 0.5) {
                lastDetectionResult = result;
                capturedRgbaData = rgbaData;
                capturedWidth = width;
                capturedHeight = height;
                capturedRotation = rotationDegrees;

                int rotatedWidth = (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;
                int rotatedHeight = (rotationDegrees == 90 || rotationDegrees == 270) ? width : height;

                double confidence = result.getConfidence();

                runOnUiThread(() -> {
                    cameraPreviewLayout.setFillCenterCoordinates(result.getModelWidth(), result.getModelHeight(),
                            rotatedWidth, rotatedHeight);
                    cameraPreviewLayout.showDetectionBox(result.getBoundingBox(), result.getCornerPoints());
                    cameraPreviewLayout.updateConfidence(confidence);
                });

                // 自动捕获检查：需要连续多帧稳定达到阈值
                if (autoCaptureEnabled && !isAutoCapturing && confidence >= autoCaptureThreshold) {
                    stableFrameCounter++;
                    if (stableFrameCounter >= STABLE_FRAME_COUNT) {
                        isAutoCapturing = true;
                        runOnUiThread(this::captureImage);
                    }
                } else {
                    stableFrameCounter = 0; // 重置计数器
                }
            } else {
                stableFrameCounter = 0; // 未检测到时重置
                runOnUiThread(() -> {
                    cameraPreviewLayout.hideDetectionBox();
                    cameraPreviewLayout.updateConfidence(0);
                });
            }

        } catch (Exception e) {
            Log.e(TAG, ">> Frame processing failed", e);
        } finally {
            image.close();
            isProcessingFrame = false;
        }
    }

    private void captureImage() {
        if (lastDetectionResult == null || capturedRgbaData == null) {
            Toast.makeText(this, R.string.guidance_preview, Toast.LENGTH_SHORT).show();
            return;
        }

        cameraPreviewLayout.showLoading(getString(R.string.loading));
        cameraController.stopPreview();

        new Thread(() -> {
            Mat colorMat = null;
            Mat rotatedMat = null;
            Mat correctedMat = null;

            try {
                colorMat = ImageEnhancer.rgbaToColorMat(capturedRgbaData, capturedWidth, capturedHeight);
                if (colorMat == null) {
                    runOnUiThread(() -> {
                        cameraPreviewLayout.hideLoading();
                        showError(getString(R.string.error_processing));
                    });
                    return;
                }

                rotatedMat = ImageEnhancer.rotateMat(colorMat, capturedRotation);
                correctedMat = labelDetector.extractAndCorrectMat(rotatedMat, lastDetectionResult);

                if (correctedMat == null) {
                    runOnUiThread(() -> {
                        cameraPreviewLayout.hideLoading();
                        showError(getString(R.string.error_processing));
                    });
                    return;
                }

                // 始终保存彩色图像用于模板匹配
                // 图像增强只在内容识别时应用
                correctedBitmap = ImageEnhancer.matToBitmap(correctedMat);

                runOnUiThread(() -> {
                    cameraPreviewLayout.hideLoading();
                    showCapturedMode();
                    cameraPreviewLayout.showImage(correctedBitmap);
                });

            } catch (Exception e) {
                Log.e(TAG, ">> Capture failed", e);
                runOnUiThread(() -> {
                    cameraPreviewLayout.hideLoading();
                    showError(getString(R.string.error_processing));
                });
            } finally {
                if (colorMat != null) colorMat.release();
                if (rotatedMat != null && rotatedMat != colorMat) rotatedMat.release();
                if (correctedMat != null) correctedMat.release();
                capturedRgbaData = null;
            }
        }).start();
    }

    private void retake() {
        if (correctedBitmap != null && !correctedBitmap.isRecycled()) {
            correctedBitmap.recycle();
            correctedBitmap = null;
        }
        lastDetectionResult = null;
        isAutoCapturing = false;
        stableFrameCounter = 0; // 重置稳定帧计数
        cameraPreviewLayout.clearImage();
        showPreviewMode();
        cameraController.startPreview();
    }

    private void confirmAndProceed() {
        if (correctedBitmap == null) {
            return;
        }

        cameraPreviewLayout.showLoading(getString(R.string.loading));

        new Thread(() -> {
            File cacheDir = new File(getCacheDir(), "template_capture");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }

            File imageFile = new File(cacheDir, UUID.randomUUID().toString() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                correctedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                savedImagePath = imageFile.getAbsolutePath();

                runOnUiThread(() -> {
                    cameraPreviewLayout.hideLoading();
                    Intent intent = new Intent(this, TemplateEditorActivity.class);
                    intent.putExtra(TemplateEditorActivity.EXTRA_MODE, TemplateEditorActivity.MODE_CREATE);
                    intent.putExtra(TemplateEditorActivity.EXTRA_CATEGORY_ID, categoryId);
                    intent.putExtra(TemplateEditorActivity.EXTRA_IMAGE_PATH, savedImagePath);
                    startActivityForResult(intent, 1);
                });

            } catch (IOException e) {
                Log.e(TAG, ">> Failed to save image", e);
                runOnUiThread(() -> {
                    cameraPreviewLayout.hideLoading();
                    showError(getString(R.string.error_processing));
                });
            }
        }).start();
    }

    private void showSettingsDialog(CameraSettings currentSettings) {
        CameraSettingsDialog dialog = CameraSettingsDialog.newInstance(currentSettings);
        dialog.setOnSettingsChangedListener(settings -> {
            cameraPreviewLayout.applySettings(settings);
            // 更新自动捕获设置
            autoCaptureEnabled = settings.isAutoCapture();
            autoCaptureThreshold = settings.getAutoCaptureThreshold();
            cameraPreviewLayout.updateAutoCaptureState(autoCaptureEnabled);
        });
        dialog.show(getSupportFragmentManager(), "camera_settings");
    }

    private void showPreviewMode() {
        cameraPreviewLayout.showPreview();
        cameraPreviewLayout.getOverlayView().setVisibility(View.VISIBLE);
        btnCapture.setVisibility(View.VISIBLE);
        btnRetake.setVisibility(View.GONE);
        btnConfirm.setVisibility(View.GONE);
        guidanceText.setText(R.string.guidance_preview);
    }

    private void showCapturedMode() {
        cameraPreviewLayout.getOverlayView().setVisibility(View.GONE);
        btnCapture.setVisibility(View.GONE);
        btnRetake.setVisibility(View.VISIBLE);
        btnConfirm.setVisibility(View.VISIBLE);
        guidanceText.setText(R.string.template_confirm_image);
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initCamera();
            } else {
                showError(getString(R.string.error_camera_permission));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraController != null && btnCapture.getVisibility() == View.VISIBLE) {
            cameraController.startPreview();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraController != null) {
            cameraController.stopPreview();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraController != null) {
            cameraController.release();
        }
        if (correctedBitmap != null && !correctedBitmap.isRecycled()) {
            correctedBitmap.recycle();
        }
    }
}
