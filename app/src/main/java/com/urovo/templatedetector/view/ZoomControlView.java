package com.urovo.templatedetector.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 变焦控制视图
 * 纯 UI 组件，只负责显示和变焦值转换
 * 触摸事件由父容器 CameraPreviewLayout 统一处理
 */
public class ZoomControlView extends View {

    private static final int BACKGROUND_COLOR = 0x60000000;
    private static final int TRACK_COLOR = 0x60FFFFFF;
    private static final int THUMB_COLOR = Color.WHITE;

    private static final long AUTO_HIDE_DELAY = 2000L;
    private static final long ANIMATION_DURATION = 300L;

    private Paint backgroundPaint;
    private Paint trackPaint;
    private Paint thumbPaint;

    private float minZoom = 1.0f;
    private float maxZoom = 10.0f;
    private float currentZoom = 1.0f;

    private final RectF backgroundRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();

    private boolean isShowing = false;
    private boolean isDragging = false;
    private ValueAnimator visibilityAnimator;
    private final Runnable autoHideRunnable = this::hide;

    private OnZoomChangeListener zoomChangeListener;
    private OnZoomDisplayListener zoomDisplayListener;

    public interface OnZoomChangeListener {
        void onZoomChanged(float zoomRatio);
    }

    public interface OnZoomDisplayListener {
        void onZoomDisplay(float zoomRatio, boolean show);
    }

    public ZoomControlView(Context context) {
        super(context);
        init();
    }

    public ZoomControlView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoomControlView(Context context, android.util.AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setAlpha(0f);
        isShowing = false;

        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(BACKGROUND_COLOR);

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(TRACK_COLOR);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(THUMB_COLOR);
        thumbPaint.setShadowLayer(3f, 0f, 1f, 0x40000000);
    }

    // ==================== 公开 API ====================

    public void setZoomRange(float minZoom, float maxZoom) {
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.currentZoom = Math.max(minZoom, Math.min(maxZoom, currentZoom));
        updateRects();
        invalidate();
    }

    public void setCurrentZoom(float zoom) {
        float newZoom = Math.max(minZoom, Math.min(maxZoom, zoom));
        if (Math.abs(newZoom - currentZoom) > 0.001f) {
            currentZoom = newZoom;
            updateRects();
            invalidate();
        }
    }

    public float getCurrentZoom() {
        return currentZoom;
    }

    /**
     * 获取轨道区域（相对于 View 自身坐标系）
     */
    public RectF getTrackBounds() {
        return new RectF(trackRect);
    }

    /**
     * 将 Y 坐标（相对于 View 自身）转换为变焦值
     */
    public float yPositionToZoom(float localY) {
        if (trackRect.height() <= 0) return currentZoom;
        
        float normalized = (trackRect.bottom - localY) / trackRect.height();
        normalized = Math.max(0f, Math.min(1f, normalized));
        return normalizedToZoom(normalized);
    }

    /**
     * 设置拖动状态（控制自动隐藏）
     */
    public void setDragging(boolean dragging) {
        this.isDragging = dragging;
        if (dragging) {
            cancelAutoHide();
            if (zoomDisplayListener != null) {
                zoomDisplayListener.onZoomDisplay(currentZoom, true);
            }
        } else {
            scheduleAutoHide();
            if (zoomDisplayListener != null) {
                zoomDisplayListener.onZoomDisplay(currentZoom, false);
            }
        }
    }

    public boolean isShowing() {
        return isShowing;
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public void setOnZoomDisplayListener(OnZoomDisplayListener listener) {
        this.zoomDisplayListener = listener;
    }

    /**
     * 更新变焦值并通知监听器
     */
    public void updateZoom(float zoom) {
        float newZoom = Math.max(minZoom, Math.min(maxZoom, zoom));
        if (Math.abs(newZoom - currentZoom) > 0.01f) {
            currentZoom = newZoom;
            updateRects();
            invalidate();
            
            if (zoomChangeListener != null) {
                zoomChangeListener.onZoomChanged(currentZoom);
            }
            if (isDragging && zoomDisplayListener != null) {
                zoomDisplayListener.onZoomDisplay(currentZoom, true);
            }
        }
    }

    // ==================== 显示/隐藏 ====================

    public void show() {
        if (isShowing) {
            if (!isDragging) {
                scheduleAutoHide();
            }
            return;
        }

        isShowing = true;
        cancelAutoHide();
        animateAlpha(1f, () -> {
            if (!isDragging) {
                scheduleAutoHide();
            }
        });
    }

    public void hide() {
        if (!isShowing) return;
        
        isShowing = false;
        cancelAutoHide();
        animateAlpha(0f, null);
    }

    private void animateAlpha(float targetAlpha, Runnable onEnd) {
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
        }

        visibilityAnimator = ValueAnimator.ofFloat(getAlpha(), targetAlpha);
        visibilityAnimator.setDuration(ANIMATION_DURATION);
        visibilityAnimator.setInterpolator(new DecelerateInterpolator());
        visibilityAnimator.addUpdateListener(a -> setAlpha((Float) a.getAnimatedValue()));
        if (onEnd != null) {
            visibilityAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onEnd.run();
                }
            });
        }
        visibilityAnimator.start();
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        postDelayed(autoHideRunnable, AUTO_HIDE_DELAY);
    }

    private void cancelAutoHide() {
        removeCallbacks(autoHideRunnable);
    }

    // ==================== 变焦值映射 ====================

    /**
     * 归一化值(0-1) → 变焦值
     * 使用平方根映射，低倍率区域更细腻，高倍率区域快速变化
     */
    private float normalizedToZoom(float normalized) {
        if (normalized <= 0f) return minZoom;
        if (normalized >= 1f) return maxZoom;
        
        // 平方根映射：低段细腻，高段快速
        // zoom = minZoom + (maxZoom - minZoom) * normalized^2
        float squared = normalized * normalized;
        return minZoom + (maxZoom - minZoom) * squared;
    }

    /**
     * 变焦值 → 归一化值(0-1)
     */
    private float zoomToNormalized(float zoom) {
        if (zoom <= minZoom) return 0f;
        if (zoom >= maxZoom) return 1f;
        
        // 反向映射：normalized = sqrt((zoom - minZoom) / (maxZoom - minZoom))
        float linear = (zoom - minZoom) / (maxZoom - minZoom);
        return (float) Math.sqrt(linear);
    }

    // ==================== 绘制 ====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateRects();
    }

    private void updateRects() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // 背景
        int bgMargin = 8;
        int bgWidth = 64;
        int bgLeft = (width - bgWidth) / 2;
        backgroundRect.set(bgLeft, bgMargin, bgLeft + bgWidth, height - bgMargin);

        // 轨道
        int trackMargin = 40;
        int trackWidth = 4;
        int trackLeft = (width - trackWidth) / 2;
        trackRect.set(trackLeft, trackMargin, trackLeft + trackWidth, height - trackMargin);

        // 滑块
        float normalized = zoomToNormalized(currentZoom);
        float thumbY = trackRect.bottom - normalized * trackRect.height();
        int thumbSize = 36;
        thumbRect.set(
            (width - thumbSize) / 2f,
            thumbY - thumbSize / 2f,
            (width + thumbSize) / 2f,
            thumbY + thumbSize / 2f
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(backgroundRect, 16f, 16f, backgroundPaint);
        canvas.drawRoundRect(trackRect, 2f, 2f, trackPaint);
        canvas.drawOval(thumbRect, thumbPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAutoHide();
        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
        }
    }
}
