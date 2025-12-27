package com.example.codedetect.ar;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.codedetect.model.LayoutPose;
import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

/**
 * ARCore管理器
 * 负责ARCore会话的初始化、配置和生命周期管理
 */
public class ARCoreManager {

    private static final String TAG = "ARCoreManager";

    private Session session;
    private Anchor layoutAnchor;

    private TrackingStateCallback trackingCallback;

    public interface TrackingStateCallback {
        void onTrackingStateChanged(@NonNull TrackingState state);
    }

    /**
     * 初始化ARCore会话
     * 注意：调用此方法前，调用者应已通过ArCoreApk.requestInstall()确认ARCore已安装
     * @param activity 必须传入Activity，ARCore需要Activity来获取设备校准数据
     */
    public boolean initializeSession(@NonNull Activity activity) {
        try {
            if (session == null) {
                // 创建会话 - 必须传入Activity
                Log.i(TAG, "Creating ARCore session...");
                session = new Session(activity);
                Log.i(TAG, "ARCore session created successfully");

                // 配置会话
                Config config = new Config(session);

                // 禁用平面检测（我们只需要Motion Tracking）
                config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);

                // 尝试启用深度（如果设备支持）
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                    Log.i(TAG, "Depth mode enabled");
                } else {
                    config.setDepthMode(Config.DepthMode.DISABLED);
                    Log.i(TAG, "Depth mode not supported");
                }

                // 启用自动对焦
                config.setFocusMode(Config.FocusMode.AUTO);
                
                // 使用最新相机图像模式
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);

                session.configure(config);

                Log.i(TAG, "ARCore session initialized successfully");
                return true;
            }
            return true;

        } catch (UnavailableArcoreNotInstalledException e) {
            Log.e(TAG, "ARCore not installed", e);
            return false;
        } catch (UnavailableApkTooOldException e) {
            Log.e(TAG, "ARCore APK too old, please update Google Play Services for AR", e);
            return false;
        } catch (UnavailableSdkTooOldException e) {
            Log.e(TAG, "ARCore SDK too old, please update the app", e);
            return false;
        } catch (UnavailableDeviceNotCompatibleException e) {
            Log.e(TAG, "Device not compatible with ARCore. This may be a device calibration issue.", e);
            Log.e(TAG, "Please ensure Google Play Services for AR is up to date.");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize ARCore session: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 恢复会话
     */
    public void resume() {
        if (session != null) {
            try {
                session.resume();
                Log.i(TAG, "ARCore session resumed");
            } catch (CameraNotAvailableException e) {
                Log.e(TAG, "Camera not available", e);
            }
        }
    }

    /**
     * 暂停会话
     */
    public void pause() {
        if (session != null) {
            session.pause();
            Log.i(TAG, "ARCore session paused");
        }
    }

    /**
     * 更新ARCore帧
     */
    @Nullable
    public Frame update() {
        if (session == null) {
            return null;
        }

        try {
            Frame frame = session.update();

            // 检查追踪状态
            Camera camera = frame.getCamera();
            TrackingState trackingState = camera.getTrackingState();

            if (trackingCallback != null) {
                trackingCallback.onTrackingStateChanged(trackingState);
            }

            return frame;

        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available during update", e);
            return null;
        }
    }

    /**
     * 从版面姿态创建或更新Anchor
     */
    public void createOrUpdateAnchor(@NonNull LayoutPose layoutPose) {
        if (session == null) {
            Log.w(TAG, "Cannot create anchor: session is null");
            return;
        }

        try {
            // 将LayoutPose转换为ARCore Pose
            float[] translation = layoutPose.getTranslation();
            float[] rotation = layoutPose.getRotation();

            // 从旋转向量创建四元数（简化版）
            float[] quaternion = rotationVectorToQuaternion(rotation);

            Pose pose = new Pose(translation, quaternion);

            // 移除旧的Anchor
            if (layoutAnchor != null) {
                layoutAnchor.detach();
            }

            // 创建新的Anchor
            layoutAnchor = session.createAnchor(pose);

            Log.i(TAG, "Layout anchor created/updated at: " +
                    translation[0] + ", " + translation[1] + ", " + translation[2]);

        } catch (Exception e) {
            Log.e(TAG, "Failed to create anchor", e);
        }
    }

    /**
     * 获取当前的版面Anchor
     */
    @Nullable
    public Anchor getLayoutAnchor() {
        return layoutAnchor;
    }

    /**
     * 获取ARCore会话
     */
    @Nullable
    public Session getSession() {
        return session;
    }

    /**
     * 设置追踪状态回调
     */
    public void setTrackingStateCallback(TrackingStateCallback callback) {
        this.trackingCallback = callback;
    }

    /**
     * 将旋转向量转换为四元数（简化版）
     */
    private float[] rotationVectorToQuaternion(float[] rvec) {
        float angle = (float) Math.sqrt(rvec[0] * rvec[0] +
                rvec[1] * rvec[1] +
                rvec[2] * rvec[2]);

        if (angle < 0.0001f) {
            return new float[]{0, 0, 0, 1};
        }

        float s = (float) Math.sin(angle / 2.0f) / angle;
        float c = (float) Math.cos(angle / 2.0f);

        return new float[]{
                rvec[0] * s,
                rvec[1] * s,
                rvec[2] * s,
                c
        };
    }

    /**
     * 释放资源
     */
    public void release() {
        if (layoutAnchor != null) {
            layoutAnchor.detach();
            layoutAnchor = null;
        }

        if (session != null) {
            session.close();
            session = null;
            Log.i(TAG, "ARCore session released");
        }
    }
}
