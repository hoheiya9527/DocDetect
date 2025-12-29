package com.urovo.templatedetector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import android.widget.ImageButton;
import com.urovo.templatedetector.camera.CameraController;
import com.urovo.templatedetector.extractor.ContentExtractor;
import com.urovo.templatedetector.init.AppInitializer;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.model.ContentRegion;
import com.urovo.templatedetector.presenter.IMainPresenter;
import com.urovo.templatedetector.presenter.IMainView;
import com.urovo.templatedetector.presenter.MainPresenter;
import com.urovo.templatedetector.view.ContentRegionAdapter;
import com.urovo.templatedetector.view.CameraSettingsDialog;
import com.urovo.templatedetector.view.OverlayView;
import com.urovo.templatedetector.view.ResolutionIndicatorView;
import com.urovo.templatedetector.view.ZoomControlView;

import java.util.List;

/**
 * 面单扫描Activity
 * 实现IMainView接口，作为MVP架构中的View层
 */
public class LabelScanActivity extends AppCompatActivity implements IMainView {

    private static final String TAG = "LabelScanActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;

    // Views
    private PreviewView previewView;
    private ImageView correctedImageView;
    private OverlayView overlayView;
    private ResolutionIndicatorView resolutionIndicator;
    private ZoomControlView zoomControl;
    private TextView zoomIndicator;
    private FrameLayout loadingContainer;
    private TextView loadingText;
    private TextView guidanceText;
    private RecyclerView contentRecyclerView;
    private MaterialButton btnConfirm;
    private MaterialButton btnCancel;
    private MaterialButton btnComplete;
    private ImageButton btnSettings;
    private com.urovo.templatedetector.view.ConfidenceIndicatorView confidenceIndicator;

    // Presenter
    private IMainPresenter presenter;

    // Adapter
    private ContentRegionAdapter contentAdapter;

    // 手势检测
    // 移除ScaleGestureDetector，现在使用ZoomControlView

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_label_scan);

        // 检查组件是否已初始化
        AppInitializer initializer = AppInitializer.getInstance(this);
        if (!initializer.isInitialized()) {
            Toast.makeText(this, R.string.initializing, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 设置窗口边距
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        initPresenter();
        initGestureDetector();

        // 检查相机权限
        if (checkCameraPermission()) {
            initCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        correctedImageView = findViewById(R.id.correctedImageView);
        overlayView = findViewById(R.id.overlayView);
        resolutionIndicator = findViewById(R.id.resolutionIndicator);
        confidenceIndicator = findViewById(R.id.confidenceIndicator);
        zoomControl = findViewById(R.id.zoomControl);
        zoomIndicator = findViewById(R.id.zoomIndicator);
        loadingContainer = findViewById(R.id.loadingContainer);
        loadingText = findViewById(R.id.loadingText);
        guidanceText = findViewById(R.id.guidanceText);
        contentRecyclerView = findViewById(R.id.contentRecyclerView);
        btnConfirm = findViewById(R.id.btnConfirm);
        btnCancel = findViewById(R.id.btnCancel);
        btnComplete = findViewById(R.id.btnComplete);
        btnSettings = findViewById(R.id.btnSettings);

        // 设置RecyclerView
        contentAdapter = new ContentRegionAdapter();
        contentRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        contentRecyclerView.setAdapter(contentAdapter);

        // 设置点击监听
        contentAdapter.setOnItemClickListener(region -> {
            presenter.onRegionClick(region.getId());
        });

        overlayView.setOnRegionClickListener(region -> {
            presenter.onRegionClick(region.getId());
        });

        btnConfirm.setOnClickListener(v -> presenter.onConfirmCapture());
        btnCancel.setOnClickListener(v -> presenter.onCancel());
        btnComplete.setOnClickListener(v -> presenter.onComplete());
        btnSettings.setOnClickListener(v -> showSettingsDialog());

        // 设置变焦控制监听
        zoomControl.setOnZoomChangeListener(zoomRatio -> {
            presenter.onZoomChanged(zoomRatio);
        });

        // 设置变焦倍数显示监听
        zoomControl.setOnZoomDisplayListener(new ZoomControlView.OnZoomDisplayListener() {
            @Override
            public void onZoomDisplay(float zoomRatio, boolean show) {
                showZoomIndicator(zoomRatio, show);
            }
        });
    }

    private void initPresenter() {
        // 使用 AppInitializer 中已初始化的组件
        AppInitializer initializer = AppInitializer.getInstance(this);
        
        CameraController cameraController = new CameraController(this);
        ContentExtractor contentExtractor = new ContentExtractor(this);
        // 设置已初始化的组件
        contentExtractor.setInitializedComponents(initializer.getOcrEngine(), initializer.getBarcodeDecoder());

        presenter = new MainPresenter(this, cameraController, initializer.getLabelDetector(), contentExtractor);
        presenter.attachView(this);
        presenter.onCreate();

        // 设置帧回调
        cameraController.setFrameCallback((image, rotationDegrees) -> {
            presenter.onFrameAvailable(image, rotationDegrees);
        });
    }

    private void initCamera() {
        CameraController cameraController = ((MainPresenter) presenter).getCameraController();
        if (cameraController != null) {
            // 设置状态回调，在相机完全初始化后更新UI
            cameraController.setStateCallback(new CameraController.StateCallback() {
                @Override
                public void onStateChanged(CameraController.CameraState state) {
                    Log.d(TAG, "Camera state changed: " + state);
                }

                @Override
                public void onError(String message) {
                    Log.e(TAG, "Camera error: " + message);
                    showCameraError(message);
                }

                @Override
                public void onCameraReady() {
                    Log.d(TAG, "Camera ready, updating zoom control");
                    // 在主线程中更新UI
                    runOnUiThread(() -> {
                        updateZoomControl(cameraController);
                        updateResolutionIndicator(cameraController);
                    });
                }
            });

            // 设置对焦回调
            cameraController.setFocusCallback(new CameraController.FocusCallback() {
                @Override
                public void onFocusStart(float x, float y) {
                    runOnUiThread(() -> {
                        if (overlayView != null) {
                            overlayView.showFocusAnimation(x, y);
                        }
                    });
                }

                @Override
                public void onFocusComplete(boolean success) {
                    Log.d(TAG, "Focus complete: " + success);
                }
            });

            cameraController.initialize(this, previewView);
        }
    }

    private void updateZoomControl(CameraController cameraController) {
        float minZoom = cameraController.getMinZoomRatio();
        float maxZoom = cameraController.getMaxZoomRatio();
        float currentZoom = cameraController.getCurrentZoomRatio();
        
        zoomControl.setZoomRange(minZoom, maxZoom);
        zoomControl.setCurrentZoom(currentZoom);
    }

    private void updateResolutionIndicator(CameraController cameraController) {
        Size actualResolution = cameraController.getActualAnalysisResolution();
        resolutionIndicator.setAnalysisResolution(actualResolution);
        Log.d(TAG, "Updated resolution indicator: " + actualResolution.getWidth() + "x" + actualResolution.getHeight());
    }

    private void initGestureDetector() {
        // 设置PreviewView的触摸处理，支持对焦和变焦控制条触发
        previewView.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY;
            private boolean isRightEdgeGesture = false;
            private boolean hasMoved = false;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        isRightEdgeGesture = zoomControl.isInRightTriggerArea(startX, startY);
                        hasMoved = false;
                        return isRightEdgeGesture; // 只有在右侧区域才消费事件
                        
                    case MotionEvent.ACTION_MOVE:
                        if (isRightEdgeGesture) {
                            float deltaX = Math.abs(event.getX() - startX);
                            float deltaY = Math.abs(event.getY() - startY);
                            
                            // 检测是否为上下滑动手势（垂直移动大于水平移动）
                            if (!hasMoved && deltaY > 30 && deltaY > deltaX * 1.5f) {
                                hasMoved = true;
                                // 显示变焦控制条
                                zoomControl.show();
                                return false; // 不消费MOVE事件，让ZoomControlView处理
                            }
                            return isRightEdgeGesture;
                        }
                        return false;
                        
                    case MotionEvent.ACTION_UP:
                        if (isRightEdgeGesture && !hasMoved) {
                            // 右侧点击但没有滑动，执行对焦
                            CameraController cameraController = ((MainPresenter) presenter).getCameraController();
                            if (cameraController != null) {
                                cameraController.focusOnPoint(event.getX(), event.getY());
                            }
                        } else if (!isRightEdgeGesture) {
                            // 左侧区域点击，执行对焦
                            CameraController cameraController = ((MainPresenter) presenter).getCameraController();
                            if (cameraController != null) {
                                cameraController.focusOnPoint(event.getX(), event.getY());
                            }
                        }
                        return false;
                        
                    case MotionEvent.ACTION_CANCEL:
                        return false;
                }
                return false;
            }
        });
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
                showCameraError(getString(R.string.error_camera_permission));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        presenter.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        presenter.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        presenter.onDestroy();
        presenter.detachView();
    }

    // ==================== IMainView 实现 ====================

    @Override
    public void setSourceSize(int width, int height) {
        runOnUiThread(() -> {
            overlayView.setSourceSize(width, height);
        });
    }

    @Override
    public void showCameraPreview() {
        runOnUiThread(() -> {
            previewView.setVisibility(View.VISIBLE);
            correctedImageView.setVisibility(View.GONE);
            btnConfirm.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.GONE);
            btnComplete.setVisibility(View.GONE);
            btnConfirm.setText(R.string.capture);
        });
    }

    @Override
    public void stopCameraPreview() {
        runOnUiThread(() -> {
            // 预览停止由Presenter控制
        });
    }

    @Override
    public void showCameraError(String message) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.error)
                    .setMessage(message)
                    .setPositiveButton(R.string.retry, (dialog, which) -> {
                        if (checkCameraPermission()) {
                            initCamera();
                        } else {
                            requestCameraPermission();
                        }
                    })
                    .setNegativeButton(R.string.cancel, (dialog, which) -> finish())
                    .show();
        });
    }

    @Override
    public void showDetectionBox(RectF box, PointF[] corners) {
        runOnUiThread(() -> {
            overlayView.setDetectionBox(box, corners);
        });
    }

    @Override
    public void hideDetectionBox() {
        runOnUiThread(() -> {
            overlayView.hideDetectionBox();
        });
    }

    @Override
    public void updateConfidenceDisplay(double confidence) {
        runOnUiThread(() -> {
            confidenceIndicator.updateConfidence(confidence);
        });
    }

    @Override
    public void showCorrectedImage(Bitmap image) {
        Log.d(TAG, "showCorrectedImage called, image=" + image.getWidth() + "x" + image.getHeight());
        runOnUiThread(() -> {
            Log.d(TAG, "showCorrectedImage: hiding previewView, showing correctedImageView");
            previewView.setVisibility(View.GONE);
            correctedImageView.setVisibility(View.VISIBLE);
            correctedImageView.setImageBitmap(image);
            
            // 立即清除所有覆盖层绘制，包括检测框
            overlayView.clear();
            
            // 等待 ImageView 布局完成后计算实际显示区域和偏移量
            correctedImageView.post(() -> {
                int imageWidth = image.getWidth();
                int imageHeight = image.getHeight();
                
                // 计算 fitCenter 模式下图像的实际显示区域
                int viewWidth = correctedImageView.getWidth();
                int viewHeight = correctedImageView.getHeight();
                
                if (viewWidth <= 0 || viewHeight <= 0) {
                    Log.w(TAG, "showCorrectedImage: invalid view size, retrying...");
                    // 如果视图尺寸无效，延迟重试
                    correctedImageView.postDelayed(() -> {
                        if (correctedImageView.getWidth() > 0 && correctedImageView.getHeight() > 0) {
                            setupOverlayCoordinates(image);
                        }
                    }, 50);
                    return;
                }
                
                setupOverlayCoordinates(image);
            });
        });
    }
    
    private void setupOverlayCoordinates(Bitmap image) {
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        int viewWidth = correctedImageView.getWidth();
        int viewHeight = correctedImageView.getHeight();
        
        float scale = Math.min(
            (float) viewWidth / imageWidth,
            (float) viewHeight / imageHeight
        );
        
        int scaledWidth = (int) (imageWidth * scale);
        int scaledHeight = (int) (imageHeight * scale);
        
        // 计算偏移量（居中显示）
        int offsetX = (viewWidth - scaledWidth) / 2;
        int offsetY = (viewHeight - scaledHeight) / 2;
        
        Log.d(TAG, "setupOverlayCoordinates: view=" + viewWidth + "x" + viewHeight + 
              ", image=" + imageWidth + "x" + imageHeight +
              ", scaled=" + scaledWidth + "x" + scaledHeight +
              ", offset=" + offsetX + "," + offsetY +
              ", scale=" + scale);
        
        // 设置 OverlayView 的坐标系统
        overlayView.setSourceSizeWithOffset(imageWidth, imageHeight, offsetX, offsetY, scale);
        
        Log.d(TAG, "setupOverlayCoordinates: coordinate system updated");
    }

    @Override
    public void showContentRegions(List<ContentRegion> regions) {
        Log.d(TAG, "showContentRegions called, regions count=" + regions.size());
        runOnUiThread(() -> {
            // 确保检测框已经隐藏
            overlayView.hideDetectionBox();
            
            overlayView.setContentRegions(regions);
            
            // 提交新列表的副本，确保 DiffUtil 能检测到变化
            contentAdapter.submitList(new ArrayList<>(regions));
            
            // 更新按钮状态
            btnConfirm.setVisibility(View.GONE);
            btnCancel.setVisibility(View.VISIBLE);
            btnComplete.setVisibility(View.VISIBLE);
            
            Log.d(TAG, "showContentRegions: done");
        });
    }

    @Override
    public void updateRegionSelection(String regionId, boolean selected) {
        runOnUiThread(() -> {
            overlayView.updateSelection(regionId, selected);
            // 重新提交列表副本，让 DiffUtil 检测 selected 状态变化
            // 不要手动调用 notifyItemChanged，会与 submitList 冲突
            List<ContentRegion> currentList = contentAdapter.getCurrentList();
            if (!currentList.isEmpty()) {
                contentAdapter.submitList(new ArrayList<>(currentList));
            }
        });
    }

    @Override
    public void clearContentRegions() {
        runOnUiThread(() -> {
            overlayView.clear();
            contentAdapter.submitList(null);
            // 清除 ImageView 中的图像，释放内存
            correctedImageView.setImageBitmap(null);
            // 重新显示相机预览并重置按钮状态
            showCameraPreview();
        });
    }

    @Override
    public void showGuidanceText(String text) {
        runOnUiThread(() -> {
            guidanceText.setText(text);
        });
    }

    @Override
    public void showSelectedContent(List<ContentRegion> selected) {
        runOnUiThread(() -> {
            // 更新列表显示选中状态
            contentAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void showLoading(String message) {
        runOnUiThread(() -> {
            loadingContainer.setVisibility(View.VISIBLE);
            loadingText.setText(message);
        });
    }

    @Override
    public void hideLoading() {
        runOnUiThread(() -> {
            loadingContainer.setVisibility(View.GONE);
        });
    }

    @Override
    public void showToast(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void finishWithResult(List<ContentRegion> selectedRegions) {
        // 返回结果
        StringBuilder result = new StringBuilder();
        for (ContentRegion region : selectedRegions) {
            result.append(region.getFormattedDisplay()).append("\n");
        }
        
        // 显示结果对话框
        new AlertDialog.Builder(this)
                .setTitle(R.string.extraction_result)
                .setMessage(result.toString())
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    // 可以通过Intent返回结果
                    finish();
                })
                .show();
    }

    // ==================== 设置对话框 ====================

    private void showSettingsDialog() {
        CameraController cameraController = ((MainPresenter) presenter).getCameraController();
        if (cameraController == null) {
            return;
        }
        
        CameraSettingsDialog dialog = CameraSettingsDialog.newInstance(
                cameraController.getCurrentSettings());
        dialog.setOnSettingsChangedListener(settings -> {
            cameraController.applySettings(settings);
            // 更新UI显示
            updateZoomControl(cameraController);
            updateResolutionIndicator(cameraController);
        });
        dialog.show(getSupportFragmentManager(), "camera_settings");
    }

    /**
     * 显示或隐藏变焦倍数指示器
     */
    private void showZoomIndicator(float zoomRatio, boolean show) {
        if (show) {
            zoomIndicator.setText(String.format("%.1fx", zoomRatio));
            zoomIndicator.setVisibility(View.VISIBLE);
            zoomIndicator.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        } else {
            zoomIndicator.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> zoomIndicator.setVisibility(View.GONE))
                    .start();
        }
    }
}
