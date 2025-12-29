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
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 变焦控制视图
 * 垂直滑动条，低段变焦更细腻，支持自动隐藏
 */
public class ZoomControlView extends View {

    private static final int BACKGROUND_COLOR = 0x60000000; // 降低透明度
    private static final int TRACK_COLOR = 0x60FFFFFF;      // 降低透明度
    private static final int THUMB_COLOR = Color.WHITE;     // 白色
    private static final int TEXT_COLOR = Color.WHITE;      // 白色

    // 自动隐藏相关
    private static final long AUTO_HIDE_DELAY = 2000; // 2秒后自动隐藏
    private static final long ANIMATION_DURATION = 300; // 动画时长
    private static final float RIGHT_EDGE_TRIGGER_RATIO = 0.8f; // 右侧80%区域触发显示

    private Paint backgroundPaint;
    private Paint trackPaint;
    private Paint thumbPaint;
    private Paint textPaint;

    private float minZoom = 1.0f;
    private float maxZoom = 10.0f;
    private float currentZoom = 1.0f;

    private RectF trackRect = new RectF();
    private RectF thumbRect = new RectF();
    private RectF backgroundRect = new RectF();

    private boolean isDragging = false;
    private float lastTouchY = 0f;

    // 显示状态控制
    private boolean isVisible = false;
    private float visibilityAlpha = 0f;
    private ValueAnimator visibilityAnimator;
    private Runnable autoHideRunnable;

    private OnZoomChangeListener zoomChangeListener;
    private OnVisibilityChangeListener visibilityChangeListener;
    private OnZoomDisplayListener zoomDisplayListener;

    public interface OnZoomChangeListener {
        void onZoomChanged(float zoomRatio);
    }

    public interface OnVisibilityChangeListener {
        void onVisibilityChanged(boolean visible);
    }

    public interface OnZoomDisplayListener {
        void onZoomDisplay(float zoomRatio, boolean show);
    }

    public ZoomControlView(Context context) {
        super(context);
        init();
    }

    public ZoomControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoomControlView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 确保View可以接收触摸事件
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        
        // 初始状态隐藏
        setAlpha(0f);
        isVisible = false;
        
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(BACKGROUND_COLOR);

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(TRACK_COLOR);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(THUMB_COLOR);
        thumbPaint.setShadowLayer(3f, 0f, 1f, 0x40000000);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(40f); // 减小字体
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(2f, 0f, 1f, 0x80000000);

        // 初始化自动隐藏任务
        autoHideRunnable = this::hide;
    }

    public void setZoomRange(float minZoom, float maxZoom) {
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.currentZoom = Math.max(minZoom, Math.min(maxZoom, currentZoom));
        updateRects();
        invalidate();
    }

    public void setCurrentZoom(float zoom) {
        this.currentZoom = Math.max(minZoom, Math.min(maxZoom, zoom));
        updateRects();
        invalidate();
    }

    public float getCurrentZoom() {
        return currentZoom;
    }

    public void setOnZoomChangeListener(OnZoomChangeListener listener) {
        this.zoomChangeListener = listener;
    }

    public void setOnVisibilityChangeListener(OnVisibilityChangeListener listener) {
        this.visibilityChangeListener = listener;
    }

    public void setOnZoomDisplayListener(OnZoomDisplayListener listener) {
        this.zoomDisplayListener = listener;
    }

    /**
     * 显示变焦控制条
     */
    public void show() {
        if (isVisible) {
            // 如果已经显示，重置自动隐藏计时器
            scheduleAutoHide();
            return;
        }

        isVisible = true;
        cancelAutoHide();

        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
        }

        visibilityAnimator = ValueAnimator.ofFloat(getAlpha(), 1f);
        visibilityAnimator.setDuration(ANIMATION_DURATION);
        visibilityAnimator.setInterpolator(new DecelerateInterpolator());
        visibilityAnimator.addUpdateListener(animation -> {
            float alpha = (Float) animation.getAnimatedValue();
            setAlpha(alpha);
            visibilityAlpha = alpha;
        });
        visibilityAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (visibilityChangeListener != null) {
                    visibilityChangeListener.onVisibilityChanged(true);
                }
                scheduleAutoHide();
            }
        });
        visibilityAnimator.start();
    }

    /**
     * 隐藏变焦控制条
     */
    public void hide() {
        if (!isVisible) return;

        isVisible = false;
        cancelAutoHide();

        if (visibilityAnimator != null) {
            visibilityAnimator.cancel();
        }

        visibilityAnimator = ValueAnimator.ofFloat(getAlpha(), 0f);
        visibilityAnimator.setDuration(ANIMATION_DURATION);
        visibilityAnimator.setInterpolator(new DecelerateInterpolator());
        visibilityAnimator.addUpdateListener(animation -> {
            float alpha = (Float) animation.getAnimatedValue();
            setAlpha(alpha);
            visibilityAlpha = alpha;
        });
        visibilityAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (visibilityChangeListener != null) {
                    visibilityChangeListener.onVisibilityChanged(false);
                }
            }
        });
        visibilityAnimator.start();
    }

    /**
     * 安排自动隐藏
     */
    private void scheduleAutoHide() {
        cancelAutoHide();
        postDelayed(autoHideRunnable, AUTO_HIDE_DELAY);
    }

    /**
     * 取消自动隐藏
     */
    private void cancelAutoHide() {
        removeCallbacks(autoHideRunnable);
    }

    /**
     * 检查触摸点是否在右侧触发区域
     */
    public boolean isInRightTriggerArea(float x, float y) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return false;

        float triggerLeft = width * RIGHT_EDGE_TRIGGER_RATIO;
        return x >= triggerLeft && y >= 0 && y <= height;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateRects();
    }

    private void updateRects() {
        int width = getWidth();
        int height = getHeight();
        
        if (width <= 0 || height <= 0) return;

        // 背景区域（更窄的背景）
        int bgMargin = 8;
        int bgWidth = 64; // 固定背景宽度
        int bgLeft = (width - bgWidth) / 2;
        backgroundRect.set(bgLeft, bgMargin, bgLeft + bgWidth, height - bgMargin);

        // 轨道区域（垂直居中，留出上下边距）
        int trackMargin = 40;
        int trackWidth = 4; // 更细的轨道
        int trackLeft = (width - trackWidth) / 2;
        trackRect.set(trackLeft, trackMargin, trackLeft + trackWidth, height - trackMargin);

        // 滑块位置（使用非线性映射，低段更细腻）
        float normalizedZoom = zoomToNormalized(currentZoom);
        float thumbY = trackRect.bottom - normalizedZoom * (trackRect.bottom - trackRect.top);
        
        int thumbSize = 36; // 更小的滑块
        thumbRect.set(
            (width - thumbSize) / 2f,
            thumbY - thumbSize / 2f,
            (width + thumbSize) / 2f,
            thumbY + thumbSize / 2f
        );
    }

    /**
     * 将变焦值转换为归一化值（0-1），使用优化的映射函数让1倍变焦更容易触发
     */
    private float zoomToNormalized(float zoom) {
        if (zoom <= minZoom) return 0f;
        if (zoom >= maxZoom) return 1f;
        
        // 使用分段函数：1倍附近使用线性映射，其他区域使用平方根
        float linear = (zoom - minZoom) / (maxZoom - minZoom);
        
        if (zoom <= 1.5f && minZoom <= 1.0f) {
            // 1倍到1.5倍区域使用线性映射，占用更多滑动空间
            float oneXNormalized = (1.0f - minZoom) / (maxZoom - minZoom);
            float onePointFiveXNormalized = (1.5f - minZoom) / (maxZoom - minZoom);
            
            if (zoom <= 1.0f) {
                // 1倍以下，使用平方根让操作更细腻
                return (float) Math.sqrt(linear / oneXNormalized) * 0.3f;
            } else {
                // 1倍到1.5倍，使用线性映射占用30%的滑动空间
                float ratio = (zoom - 1.0f) / 0.5f;
                return 0.3f + ratio * 0.4f; // 占用30%-70%的空间
            }
        } else {
            // 1.5倍以上使用压缩的平方根映射
            float adjustedLinear = Math.max(0, (zoom - 1.5f) / (maxZoom - 1.5f));
            return 0.7f + (float) Math.sqrt(adjustedLinear) * 0.3f;
        }
    }

    /**
     * 将归一化值转换为变焦值
     */
    private float normalizedToZoom(float normalized) {
        if (normalized <= 0f) return minZoom;
        if (normalized >= 1f) return maxZoom;
        
        if (normalized <= 0.3f && minZoom <= 1.0f) {
            // 0-30%区域对应1倍以下，使用平方函数
            float ratio = normalized / 0.3f;
            float linear = ratio * ratio;
            return minZoom + linear * (1.0f - minZoom);
        } else if (normalized <= 0.7f) {
            // 30%-70%区域对应1倍到1.5倍，使用线性映射
            float ratio = (normalized - 0.3f) / 0.4f;
            return 1.0f + ratio * 0.5f;
        } else {
            // 70%-100%区域对应1.5倍以上，使用平方函数
            float ratio = (normalized - 0.7f) / 0.3f;
            float linear = ratio * ratio;
            return 1.5f + linear * (maxZoom - 1.5f);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 绘制背景（圆角矩形）
        float cornerRadius = 16f;
        canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint);

        // 绘制轨道
        canvas.drawRoundRect(trackRect, 2f, 2f, trackPaint);

        // 绘制滑块
        canvas.drawOval(thumbRect, thumbPaint);

        // 不再在这里绘制变焦值文本，改为在预览中央显示
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 只有在可见状态下才处理触摸事件
        if (!isVisible && event.getAction() == MotionEvent.ACTION_DOWN) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 显示控制条并取消自动隐藏
                show();
                
                // 显示变焦倍数
                if (zoomDisplayListener != null) {
                    zoomDisplayListener.onZoomDisplay(currentZoom, true);
                }
                
                // 整个View都可以触摸
                isDragging = true;
                lastTouchY = y;
                
                // 如果点击在轨道区域，直接跳转到该位置
                if (y >= trackRect.top && y <= trackRect.bottom) {
                    float trackHeight = trackRect.bottom - trackRect.top;
                    float normalizedPosition = (trackRect.bottom - y) / trackHeight;
                    normalizedPosition = Math.max(0f, Math.min(1f, normalizedPosition));
                    
                    float newZoom = normalizedToZoom(normalizedPosition);
                    currentZoom = newZoom;
                    updateRects();
                    invalidate();
                    
                    if (zoomChangeListener != null) {
                        zoomChangeListener.onZoomChanged(currentZoom);
                    }
                    if (zoomDisplayListener != null) {
                        zoomDisplayListener.onZoomDisplay(currentZoom, true);
                    }
                }
                
                // 请求父容器不要拦截触摸事件
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    // 取消自动隐藏
                    cancelAutoHide();
                    
                    float deltaY = lastTouchY - y; // 向上为正
                    lastTouchY = y;
                    
                    // 计算新的归一化位置
                    float trackHeight = trackRect.bottom - trackRect.top;
                    if (trackHeight > 0) {
                        float currentNormalized = zoomToNormalized(currentZoom);
                        float deltaNormalized = deltaY / trackHeight;
                        float newNormalized = Math.max(0f, Math.min(1f, currentNormalized + deltaNormalized));
                        
                        // 转换为变焦值
                        float newZoom = normalizedToZoom(newNormalized);
                        if (Math.abs(newZoom - currentZoom) > 0.01f) {
                            currentZoom = newZoom;
                            updateRects();
                            invalidate();
                            
                            if (zoomChangeListener != null) {
                                zoomChangeListener.onZoomChanged(currentZoom);
                            }
                            if (zoomDisplayListener != null) {
                                zoomDisplayListener.onZoomDisplay(currentZoom, true);
                            }
                        }
                    }
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    isDragging = false;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    invalidate(); // 刷新显示，隐藏文字
                    
                    // 隐藏变焦倍数显示
                    if (zoomDisplayListener != null) {
                        zoomDisplayListener.onZoomDisplay(currentZoom, false);
                    }
                    
                    // 重新安排自动隐藏
                    scheduleAutoHide();
                    return true;
                }
                return false;
        }
        return false;
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