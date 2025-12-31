package com.urovo.templatedetector.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.view.PreviewView;
import androidx.fragment.app.FragmentActivity;

import com.urovo.templatedetector.R;
import com.urovo.templatedetector.camera.CameraController;
import com.urovo.templatedetector.model.CameraSettings;

/**
 * 复合相机预览组件
 * 封装相机预览、覆盖层、分辨率/置信度指示器、变焦控制、设置按钮等
 */
public class CameraPreviewLayout extends FrameLayout {

    private static final String TAG = "CameraPreviewLayout";

    // 子视图
    private PreviewView previewView;
    private ImageView imageView;
    private OverlayView overlayView;
    private ResolutionIndicatorView resolutionIndicator;
    private ConfidenceIndicatorView confidenceIndicator;
    private TextView autoCaptureIndicator;
    private ZoomControlView zoomControl;
    private TextView zoomIndicator;
    private FrameLayout loadingContainer;
    private TextView loadingText;
    private ImageButton btnSettings;

    // 相机控制器
    private CameraController cameraController;

    // 回调接口
    private OnSettingsClickListener settingsClickListener;
    private OnRegionClickListener regionClickListener;

    public interface OnSettingsClickListener {
        void onSettingsClick(CameraSettings currentSettings);
    }

    public interface OnRegionClickListener {
        void onRegionClick(String regionId);
    }

    public CameraPreviewLayout(@NonNull Context context) {
        super(context);
        init(context);
    }

    public CameraPreviewLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CameraPreviewLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_camera_preview, this, true);

        previewView = findViewById(R.id.cameraPreviewView);
        imageView = findViewById(R.id.cameraImageView);
        overlayView = findViewById(R.id.cameraOverlayView);
        resolutionIndicator = findViewById(R.id.cameraResolutionIndicator);
        confidenceIndicator = findViewById(R.id.cameraConfidenceIndicator);
        autoCaptureIndicator = findViewById(R.id.cameraAutoCaptureIndicator);
        zoomControl = findViewById(R.id.cameraZoomControl);
        zoomIndicator = findViewById(R.id.cameraZoomIndicator);
        loadingContainer = findViewById(R.id.cameraLoadingContainer);
        loadingText = findViewById(R.id.cameraLoadingText);
        btnSettings = findViewById(R.id.cameraBtnSettings);

        setupListeners();
    }

    private void setupListeners() {
        // 设置按钮点击
        btnSettings.setOnClickListener(v -> {
            if (settingsClickListener != null && cameraController != null) {
                settingsClickListener.onSettingsClick(cameraController.getCurrentSettings());
            }
        });

        // 变焦控制监听
        zoomControl.setOnZoomChangeListener(zoomRatio -> {
            if (cameraController != null) {
                cameraController.setZoomRatio(zoomRatio);
            }
        });

        // 变焦倍数显示监听
        zoomControl.setOnZoomDisplayListener((zoomRatio, show) -> {
            showZoomIndicator(zoomRatio, show);
        });

    }

    // ==================== 触摸处理 ====================
    
    // 触摸状态
    private float touchStartX, touchStartY;
    private boolean hasMoved = false;
    private boolean isZoomDragging = false;
    private boolean isRightEdgeGesture = false;

    // 右侧边缘触发区域宽度（dp）
    private static final int RIGHT_EDGE_WIDTH_DP = 48;
    // 移动阈值（px）
    private static final int MOVE_THRESHOLD = 10;
    // 垂直滑动触发阈值（px）
    private static final int VERTICAL_SWIPE_THRESHOLD = 20;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // 拦截所有事件，统一在 onTouchEvent 处理
        // 因为 OverlayView 覆盖在 PreviewView 上，必须由父容器统一处理
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return handleTouchDown(x, y);
            case MotionEvent.ACTION_MOVE:
                return handleTouchMove(x, y);
            case MotionEvent.ACTION_UP:
                return handleTouchUp(x, y);
            case MotionEvent.ACTION_CANCEL:
                return handleTouchCancel();
        }
        return super.onTouchEvent(event);
    }

    private boolean handleTouchDown(float x, float y) {
        touchStartX = x;
        touchStartY = y;
        hasMoved = false;
        isZoomDragging = false;
        isRightEdgeGesture = false;

        // 1. 设置按钮点击
        if (isTouchInView(btnSettings, x, y)) {
            btnSettings.performClick();
            return true;
        }

        // 2. 变焦条可见时，点击变焦条区域开始拖动
        if (zoomControl.isShowing() && isTouchInView(zoomControl, x, y)) {
            startZoomDrag(x, y);
            return true;
        }

        // 3. 检测右侧边缘（用于触发变焦条显示）
        isRightEdgeGesture = isInRightEdge(x);
        return true;
    }

    private boolean handleTouchMove(float x, float y) {
        float deltaX = Math.abs(x - touchStartX);
        float deltaY = Math.abs(y - touchStartY);

        // 正在拖动变焦条
        if (isZoomDragging) {
            updateZoomFromTouch(y);
            return true;
        }

        // 右侧边缘垂直滑动 → 显示变焦条并开始拖动
        if (isRightEdgeGesture && !hasMoved) {
            if (deltaY > VERTICAL_SWIPE_THRESHOLD && deltaY > deltaX * 1.5f) {
                startZoomDrag(x, y);
                return true;
            }
        }

        // 标记已移动
        if (deltaX > MOVE_THRESHOLD || deltaY > MOVE_THRESHOLD) {
            hasMoved = true;
        }
        return true;
    }

    private boolean handleTouchUp(float x, float y) {
        // 结束变焦拖动
        if (isZoomDragging) {
            endZoomDrag();
            return true;
        }

        // 点击对焦（未移动时）
        if (!hasMoved) {
            focusOnPoint(x, y);
        }
        return true;
    }

    private boolean handleTouchCancel() {
        if (isZoomDragging) {
            endZoomDrag();
        }
        return true;
    }

    // ==================== 变焦拖动 ====================

    private void startZoomDrag(float x, float y) {
        isZoomDragging = true;
        hasMoved = true;
        zoomControl.show();
        zoomControl.setDragging(true);
        updateZoomFromTouch(y);
    }

    private void updateZoomFromTouch(float y) {
        // 将触摸 Y 坐标转换为 ZoomControlView 本地坐标
        float localY = toZoomControlLocalY(y);
        float zoom = zoomControl.yPositionToZoom(localY);
        zoomControl.updateZoom(zoom);
    }

    private void endZoomDrag() {
        isZoomDragging = false;
        zoomControl.setDragging(false);
    }

    /**
     * 将 Layout 坐标转换为 ZoomControlView 本地坐标
     */
    private float toZoomControlLocalY(float layoutY) {
        int[] zoomLocation = new int[2];
        zoomControl.getLocationInWindow(zoomLocation);
        int[] layoutLocation = new int[2];
        getLocationInWindow(layoutLocation);
        return layoutY - (zoomLocation[1] - layoutLocation[1]);
    }

    // ==================== 触摸区域检测 ====================

    private boolean isInRightEdge(float x) {
        int width = getWidth();
        if (width <= 0) return false;
        float density = getResources().getDisplayMetrics().density;
        float edgeWidth = RIGHT_EDGE_WIDTH_DP * density;
        return x >= width - edgeWidth;
    }

    private boolean isTouchInView(View view, float x, float y) {
        if (view == null || view.getVisibility() != VISIBLE) {
            return false;
        }
        int[] viewLocation = new int[2];
        view.getLocationInWindow(viewLocation);
        int[] layoutLocation = new int[2];
        getLocationInWindow(layoutLocation);

        float left = viewLocation[0] - layoutLocation[0];
        float top = viewLocation[1] - layoutLocation[1];
        float right = left + view.getWidth();
        float bottom = top + view.getHeight();

        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /**
     * 设置相机控制器并初始化
     */
    public void setCameraController(CameraController controller, FragmentActivity activity) {
        this.cameraController = controller;

        // 设置状态回调
        cameraController.setStateCallback(new CameraController.StateCallback() {
            @Override
            public void onStateChanged(CameraController.CameraState state) {
                Log.d(TAG, ">> Camera state changed: " + state);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, ">> Camera error: " + message);
            }

            @Override
            public void onCameraReady() {
                Log.d(TAG, ">> Camera ready");
                post(() -> {
                    updateZoomControl();
                    updateResolutionIndicator();
                });
            }
        });

        // 设置对焦回调
        cameraController.setFocusCallback(new CameraController.FocusCallback() {
            @Override
            public void onFocusStart(float x, float y) {
                post(() -> overlayView.showFocusAnimation(x, y));
            }

            @Override
            public void onFocusComplete(boolean success) {
                Log.d(TAG, ">> Focus complete: " + success);
            }
        });

        // 初始化相机
        cameraController.initialize(activity, previewView);
    }

    /**
     * 获取 PreviewView（用于外部直接初始化相机）
     */
    public PreviewView getPreviewView() {
        return previewView;
    }

    /**
     * 获取 OverlayView
     */
    public OverlayView getOverlayView() {
        return overlayView;
    }

    /**
     * 获取 ImageView（用于显示校正后的图像）
     */
    public ImageView getImageView() {
        return imageView;
    }

    // ==================== 预览控制 ====================

    /**
     * 显示相机预览
     */
    public void showPreview() {
        previewView.setVisibility(VISIBLE);
        imageView.setVisibility(GONE);
        overlayView.setVisibility(VISIBLE);
    }

    /**
     * 显示静态图像
     */
    public void showImage(Bitmap bitmap) {
        previewView.setVisibility(GONE);
        imageView.setVisibility(VISIBLE);
        imageView.setImageBitmap(bitmap);
    }

    /**
     * 清除图像
     */
    public void clearImage() {
        imageView.setImageBitmap(null);
    }

    // ==================== 检测框控制 ====================

    /**
     * 显示检测框
     */
    public void showDetectionBox(RectF box, PointF[] corners) {
        overlayView.setDetectionBox(box, corners);
    }

    /**
     * 隐藏检测框
     */
    public void hideDetectionBox() {
        overlayView.hideDetectionBox();
    }

    /**
     * 设置内容区域（蓝色框）
     * @param regions 内容区域列表
     */
    public void setContentRegions(java.util.List<OverlayView.ContentRegion> regions) {
        overlayView.setContentRegions(regions);
    }

    /**
     * 清除内容区域
     */
    public void clearContentRegions() {
        overlayView.clearContentRegions();
    }

    /**
     * 设置 FILL_CENTER 模式的坐标系统
     */
    public void setFillCenterCoordinates(int modelWidth, int modelHeight, int imageWidth, int imageHeight) {
        int viewWidth = overlayView.getWidth();
        int viewHeight = overlayView.getHeight();

        if (viewWidth > 0 && viewHeight > 0 && imageWidth > 0 && imageHeight > 0) {
            float imageAspect = (float) imageWidth / imageHeight;
            float viewAspect = (float) viewWidth / viewHeight;

            float scale;
            int cropOffsetX = 0;
            int cropOffsetY = 0;

            if (imageAspect > viewAspect) {
                scale = (float) viewHeight / imageHeight;
                int scaledImageWidth = (int) (imageWidth * scale);
                cropOffsetX = (scaledImageWidth - viewWidth) / 2;
            } else {
                scale = (float) viewWidth / imageWidth;
                int scaledImageHeight = (int) (imageHeight * scale);
                cropOffsetY = (scaledImageHeight - viewHeight) / 2;
            }

            float modelToImageScaleX = (float) imageWidth / modelWidth;
            float modelToImageScaleY = (float) imageHeight / modelHeight;
            float totalScaleX = modelToImageScaleX * scale;
            float totalScaleY = modelToImageScaleY * scale;

            overlayView.setFillCenterCoordinates(modelWidth, modelHeight,
                    totalScaleX, totalScaleY, cropOffsetX, cropOffsetY);
        }
    }

    /**
     * 设置源图像尺寸和偏移（用于校正图像显示）
     */
    public void setSourceSizeWithOffset(int imageWidth, int imageHeight, int offsetX, int offsetY, float scale) {
        overlayView.setSourceSizeWithOffset(imageWidth, imageHeight, offsetX, offsetY, scale);
    }

    // ==================== 指示器控制 ====================

    /**
     * 更新置信度显示
     */
    public void updateConfidence(double confidence) {
        confidenceIndicator.updateConfidence(confidence);
    }

    /**
     * 更新分辨率显示
     */
    public void updateResolution(Size resolution) {
        resolutionIndicator.setAnalysisResolution(resolution);
    }

    /**
     * 设置置信度指示器可见性
     */
    public void setConfidenceIndicatorVisible(boolean visible) {
        confidenceIndicator.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * 设置分辨率指示器可见性
     */
    public void setResolutionIndicatorVisible(boolean visible) {
        resolutionIndicator.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * 设置设置按钮可见性
     */
    public void setSettingsButtonVisible(boolean visible) {
        btnSettings.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * 设置变焦控制可见性
     */
    public void setZoomControlEnabled(boolean enabled) {
        zoomControl.setVisibility(enabled ? VISIBLE : GONE);
    }

    /**
     * 设置自动捕获指示器可见性
     */
    public void setAutoCaptureIndicatorVisible(boolean visible) {
        autoCaptureIndicator.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * 更新自动捕获状态显示
     */
    public void updateAutoCaptureState(boolean enabled) {
        autoCaptureIndicator.setText(enabled ? R.string.auto_capture_on : R.string.auto_capture_off);
    }

    // ==================== 加载状态 ====================

    /**
     * 显示加载状态
     */
    public void showLoading(String message) {
        loadingContainer.setVisibility(VISIBLE);
        loadingText.setText(message);
    }

    /**
     * 隐藏加载状态
     */
    public void hideLoading() {
        loadingContainer.setVisibility(GONE);
    }

    // ==================== 回调设置 ====================

    /**
     * 设置设置按钮点击监听
     */
    public void setOnSettingsClickListener(OnSettingsClickListener listener) {
        this.settingsClickListener = listener;
    }

    /**
     * 设置区域点击监听
     */
    public void setOnRegionClickListener(OnRegionClickListener listener) {
        this.regionClickListener = listener;
    }

    // ==================== 内部方法 ====================

    private void focusOnPoint(float x, float y) {
        if (cameraController != null) {
            cameraController.focusOnPoint(x, y);
        }
    }

    private void updateZoomControl() {
        if (cameraController == null) return;

        float minZoom = cameraController.getMinZoomRatio();
        float maxZoom = cameraController.getMaxZoomRatio();
        float currentZoom = cameraController.getCurrentZoomRatio();

        zoomControl.setZoomRange(minZoom, maxZoom);
        zoomControl.setCurrentZoom(currentZoom);
    }

    private void updateResolutionIndicator() {
        if (cameraController == null) return;

        Size actualResolution = cameraController.getActualAnalysisResolution();
        resolutionIndicator.setAnalysisResolution(actualResolution);
        Log.d(TAG, ">> Updated resolution indicator: " + actualResolution.getWidth() + "x" + actualResolution.getHeight());
    }

    private void showZoomIndicator(float zoomRatio, boolean show) {
        if (show) {
            zoomIndicator.setText(String.format("%.1fx", zoomRatio));
            zoomIndicator.setVisibility(VISIBLE);
            zoomIndicator.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        } else {
            zoomIndicator.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> zoomIndicator.setVisibility(GONE))
                    .start();
        }
    }

    /**
     * 应用相机设置
     */
    public void applySettings(CameraSettings settings) {
        if (cameraController != null) {
            cameraController.applySettings(settings);
            updateZoomControl();
            updateResolutionIndicator();
        }
    }

    /**
     * 获取当前相机控制器
     */
    public CameraController getCameraController() {
        return cameraController;
    }
}
