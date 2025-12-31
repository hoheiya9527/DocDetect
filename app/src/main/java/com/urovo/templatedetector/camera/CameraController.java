package com.urovo.templatedetector.camera;

import android.content.Context;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.urovo.templatedetector.model.CameraSettings;
import com.urovo.templatedetector.util.CameraConfigManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相机控制器
 * 基于CameraX实现相机预览和参数控制
 */
public class CameraController {

    private static final String TAG = "CameraController";

    /**
     * 相机状态
     */
    public enum CameraState {
        IDLE,       // 空闲
        PREVIEWING, // 预览中
        PAUSED,     // 暂停
        ERROR       // 错误
    }

    /**
     * 帧回调接口
     */
    public interface FrameCallback {
        void onFrameAvailable(ImageProxy image, int rotationDegrees);
    }

    /**
     * 状态回调接口
     */
    public interface StateCallback {
        void onStateChanged(CameraState state);
        void onError(String message);
        void onCameraReady(); // 相机完全初始化完成
    }

    private final Context context;
    private final ExecutorService analysisExecutor;
    private final CameraConfigManager configManager;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private CameraControl cameraControl;
    private CameraInfo cameraInfo;
    private Preview preview;
    private ImageAnalysis imageAnalysis;
    private PreviewView previewView;
    private LifecycleOwner lifecycleOwner;

    private CameraState currentState = CameraState.IDLE;
    private CameraSettings currentSettings;
    private FrameCallback frameCallback;
    private FrameCallback originalFrameCallback; // 保存原始回调
    private StateCallback stateCallback;

    private float maxZoomRatio = 1.0f;
    private float minZoomRatio = 1.0f;

    public CameraController(Context context) {
        this.context = context.getApplicationContext();
        this.analysisExecutor = Executors.newSingleThreadExecutor();
        this.configManager = CameraConfigManager.getInstance(context);
        this.currentSettings = configManager.loadSettings();
        Log.d(TAG, "CameraController initialized, autoCapture=" + currentSettings.isAutoCapture() + ", threshold=" + currentSettings.getAutoCaptureThreshold());
    }

    /**
     * 初始化相机
     */
    public void initialize(LifecycleOwner owner, PreviewView previewView) {
        this.lifecycleOwner = owner;
        this.previewView = previewView;
        
        // 使用默认的 FILL_CENTER 模式，预览铺满整个 View

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
                updateState(CameraState.ERROR);
                if (stateCallback != null) {
                    stateCallback.onError(e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * 绑定相机用例
     */
    private void bindCameraUseCases() {
        Log.d(TAG, "bindCameraUseCases called, provider=" + (cameraProvider != null) + 
              ", owner=" + (lifecycleOwner != null) + ", view=" + (previewView != null));
        
        if (cameraProvider == null || lifecycleOwner == null || previewView == null) {
            Log.w(TAG, "Cannot bind camera use cases: missing dependencies");
            return;
        }

        // 解绑所有用例
        cameraProvider.unbindAll();

        // 相机选择器（后置摄像头）
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // 预览配置
        preview = new Preview.Builder()
                .setTargetResolution(currentSettings.getPreviewResolution())
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // 图像分析配置
        // 设置输出格式为 RGBA_8888
        // 这样 imageProxy.toBitmap() 可以直接获取正确格式的图像
        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(currentSettings.getAnalysisResolution())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, image -> {
            Log.d(TAG, "Frame received, state=" + currentState + ", callback=" + (frameCallback != null));
            if (currentState == CameraState.PREVIEWING && frameCallback != null) {
                Log.d(TAG, "Dispatching frame to callback");
                frameCallback.onFrameAvailable(image, image.getImageInfo().getRotationDegrees());
            } else {
                Log.d(TAG, "Closing frame without processing");
                image.close();
            }
        });
        
        Log.d(TAG, "ImageAnalysis analyzer set, frameCallback=" + (frameCallback != null));

        try {
            // 绑定用例
            camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );

            cameraControl = camera.getCameraControl();
            cameraInfo = camera.getCameraInfo();

            // 获取缩放范围
            ZoomState zoomState = cameraInfo.getZoomState().getValue();
            if (zoomState != null) {
                maxZoomRatio = zoomState.getMaxZoomRatio();
                minZoomRatio = zoomState.getMinZoomRatio();
                Log.d(TAG, "Zoom range initialized: " + minZoomRatio + " - " + maxZoomRatio);
            }

            // 应用初始设置
            applySettings(currentSettings);

            updateState(CameraState.PREVIEWING);
            Log.d(TAG, "Camera bound successfully");

            // 通知相机初始化完成
            if (stateCallback != null) {
                stateCallback.onCameraReady();
            }

        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            updateState(CameraState.ERROR);
            if (stateCallback != null) {
                stateCallback.onError(e.getMessage());
            }
        }
    }

    /**
     * 开始预览
     */
    public void startPreview() {
        Log.d(TAG, "startPreview called");
        
        // 恢复帧回调
        if (originalFrameCallback != null) {
            frameCallback = originalFrameCallback;
            Log.d(TAG, "Frame callback restored");
        }
        
        if (currentState == CameraState.PAUSED) {
            bindCameraUseCases();
        } else if (currentState == CameraState.IDLE && cameraProvider != null) {
            bindCameraUseCases();
        }
    }

    /**
     * 停止预览
     */
    public void stopPreview() {
        Log.d(TAG, "stopPreview called");
        
        // 立即清除帧回调，阻止新帧处理
        frameCallback = null;
        
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            updateState(CameraState.PAUSED);
        }
        
        Log.d(TAG, "stopPreview completed, frameCallback cleared");
    }

    /**
     * 设置帧回调
     */
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
        this.originalFrameCallback = callback; // 保存原始回调
        Log.d(TAG, "Frame callback set and saved");
    }

    /**
     * 设置状态回调
     */
    public void setStateCallback(StateCallback callback) {
        this.stateCallback = callback;
    }

    /**
     * 应用相机设置
     */
    public void applySettings(CameraSettings settings) {
        CameraSettings oldSettings = this.currentSettings.copy();
        this.currentSettings = settings.copy();
        
        // 保存设置到持久化存储
        configManager.saveSettings(currentSettings);

        if (cameraControl == null) {
            return;
        }

        // 检查是否需要重新绑定相机用例（分辨率改变）
        boolean needRebind = !oldSettings.getPreviewResolution().equals(settings.getPreviewResolution()) ||
                            !oldSettings.getAnalysisResolution().equals(settings.getAnalysisResolution());

        if (needRebind) {
            Log.d(TAG, "Resolution changed, rebinding camera use cases");
            // 重新绑定相机用例
            bindCameraUseCases();
            // 注意：bindCameraUseCases会调用onCameraReady回调来更新UI
        } else {
            // 只应用不需要重新绑定的设置
            // 应用缩放
            setZoomRatio(settings.getZoomRatio());

            // 应用曝光补偿
            setExposureCompensation(settings.getExposureCompensation());

            // 对焦模式通过触摸对焦实现
            if (settings.getFocusMode() == CameraSettings.FocusMode.CONTINUOUS) {
                // CameraX默认使用连续对焦
            }
        }
        
        Log.d(TAG, "Camera settings applied and saved");
    }

    /**
     * 获取当前设置
     */
    public CameraSettings getCurrentSettings() {
        return currentSettings.copy();
    }

    /**
     * 设置缩放比例
     */
    public void setZoomRatio(float ratio) {
        if (cameraControl == null) {
            return;
        }

        float clampedRatio = Math.max(minZoomRatio, Math.min(maxZoomRatio, ratio));
        cameraControl.setZoomRatio(clampedRatio);
        currentSettings.setZoomRatio(clampedRatio);
    }

    /**
     * 获取最大缩放比例
     */
    public float getMaxZoomRatio() {
        return maxZoomRatio;
    }

    /**
     * 获取最小缩放比例
     */
    public float getMinZoomRatio() {
        return minZoomRatio;
    }

    /**
     * 获取当前缩放比例
     */
    public float getCurrentZoomRatio() {
        if (cameraInfo == null) {
            return 1.0f;
        }
        ZoomState zoomState = cameraInfo.getZoomState().getValue();
        return zoomState != null ? zoomState.getZoomRatio() : 1.0f;
    }

    /**
     * 设置曝光补偿
     */
    public void setExposureCompensation(int value) {
        if (cameraControl == null || cameraInfo == null) {
            return;
        }

        ExposureState exposureState = cameraInfo.getExposureState();
        Range<Integer> range = exposureState.getExposureCompensationRange();
        
        int clampedValue = Math.max(range.getLower(), Math.min(range.getUpper(), value));
        cameraControl.setExposureCompensationIndex(clampedValue);
        currentSettings.setExposureCompensation(clampedValue);
    }

    /**
     * 对焦回调接口
     */
    public interface FocusCallback {
        void onFocusStart(float x, float y);
        void onFocusComplete(boolean success);
    }

    private FocusCallback focusCallback;

    /**
     * 设置对焦回调
     */
    public void setFocusCallback(FocusCallback callback) {
        this.focusCallback = callback;
    }

    /**
     * 触摸对焦
     */
    public void focusOnPoint(float x, float y) {
        if (cameraControl == null || previewView == null) {
            return;
        }

        // 通知开始对焦
        if (focusCallback != null) {
            focusCallback.onFocusStart(x, y);
        }

        MeteringPointFactory factory = previewView.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point)
                .build();
        
        ListenableFuture<FocusMeteringResult> future = cameraControl.startFocusAndMetering(action);
        future.addListener(() -> {
            try {
                FocusMeteringResult result = future.get();
                boolean success = result.isFocusSuccessful();
                if (focusCallback != null) {
                    focusCallback.onFocusComplete(success);
                }
            } catch (Exception e) {
                Log.e(TAG, "Focus failed", e);
                if (focusCallback != null) {
                    focusCallback.onFocusComplete(false);
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * 获取实际的分析分辨率
     * 返回ImageAnalysis实际使用的分辨率，可能与设置的目标分辨率不同
     */
    public Size getActualAnalysisResolution() {
        if (imageAnalysis == null) {
            return currentSettings.getAnalysisResolution();
        }
        
        // 获取ImageAnalysis实际使用的分辨率
        return imageAnalysis.getResolutionInfo() != null ? 
               imageAnalysis.getResolutionInfo().getResolution() : 
               currentSettings.getAnalysisResolution();
    }

    /**
     * 获取实际的预览分辨率
     */
    public Size getActualPreviewResolution() {
        if (preview == null) {
            return currentSettings.getPreviewResolution();
        }
        
        return preview.getResolutionInfo() != null ? 
               preview.getResolutionInfo().getResolution() : 
               currentSettings.getPreviewResolution();
    }

    /**
     * 获取当前状态
     */
    public CameraState getCurrentState() {
        return currentState;
    }

    /**
     * 获取支持的分辨率列表
     */
    public Size[] getSupportedResolutions() {
        return CameraSettings.getSupportedAnalysisResolutions();
    }

    /**
     * 获取曝光补偿范围
     */
    public Range<Integer> getExposureCompensationRange() {
        if (cameraInfo == null) {
            return new Range<>(-2, 2);
        }
        return cameraInfo.getExposureState().getExposureCompensationRange();
    }

    /**
     * 更新状态
     */
    private void updateState(CameraState state) {
        this.currentState = state;
        if (stateCallback != null) {
            stateCallback.onStateChanged(state);
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        analysisExecutor.shutdown();
        updateState(CameraState.IDLE);
        Log.d(TAG, "CameraController released");
    }
}
