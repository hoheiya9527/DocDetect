package com.urovo.templatedetector.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 覆盖层View
 * 用于在相机预览上绘制检测框和内容区域
 * <p>
 * 线程安全说明：
 * - 数据修改方法使用 synchronized 保护
 * - onDraw() 中创建数据的本地副本，避免渲染过程中数据被修改
 * - 使用 invalidate 节流机制，避免 GPU 渲染队列积压
 */
public class OverlayView extends View {

    // 颜色定义
    private static final int COLOR_DETECTION_BOX = Color.parseColor("#00FF00");  // 绿色
    private static final int COLOR_CONTENT_BORDER = Color.parseColor("#2196F3"); // 蓝色
    private static final int COLOR_CONTENT_SELECTED = Color.parseColor("#2196F3"); // 半透明蓝色
    private static final int COLOR_CORNER_POINT = Color.WHITE;

    // 线条宽度
    private static final float STROKE_WIDTH_DETECTION = 4f;
    private static final float STROKE_WIDTH_CONTENT = 3f;
    private static final float CORNER_RADIUS = 8f;

    // invalidate 节流：降低到30fps，减少GPU压力，防止内存损坏
    private static final long MIN_INVALIDATE_INTERVAL_MS = 33;

    // Paint对象
    private Paint detectionBoxPaint;
    private Paint contentBorderPaint;
    private Paint contentFillPaint;
    private Paint textPaint;

    // 检测框数据（使用 synchronized 保护）
    private final Object dataLock = new Object();
    private RectF detectionBox;
    private PointF[] detectionCorners;
    private float detectionAlpha = 0f;
    
    // 内容区域数据
    private List<ContentRegion> contentRegions = new ArrayList<>();
    
    /**
     * 内容区域数据结构
     */
    public static class ContentRegion {
        public final String id;
        public final RectF bounds;
        public final PointF[] corners;
        public final String label;
        public final boolean hasContent;
        
        public ContentRegion(String id, RectF bounds, PointF[] corners, String label, boolean hasContent) {
            this.id = id;
            this.bounds = bounds;
            this.corners = corners;
            this.label = label;
            this.hasContent = hasContent;
        }
    }

    // 对焦动画
    private float focusX = -1f;
    private float focusY = -1f;
    private float focusAlpha = 0f;
    private ValueAnimator focusAnimator;
    private Paint focusPaint;
    private float scaleX, scaleY = 1f;
    private int sourceWidth = 1;
    private int sourceHeight = 1;
    // fitCenter 模式下的偏移量
    private int offsetX = 0;
    private int offsetY = 0;
    private float imageScale = 1f;
    // 是否使用自定义坐标设置（防止被onSizeChanged覆盖）
    private boolean useCustomCoordinates = false;

    // invalidate 节流
    private long lastInvalidateTime = 0;
    private boolean pendingInvalidate = false;

    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 检测框画笔（绿色）
        detectionBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detectionBoxPaint.setColor(COLOR_DETECTION_BOX);
        detectionBoxPaint.setStyle(Paint.Style.STROKE);
        detectionBoxPaint.setStrokeWidth(STROKE_WIDTH_DETECTION);

        // 内容边框画笔（蓝色）
        contentBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        contentBorderPaint.setColor(COLOR_CONTENT_BORDER);
        contentBorderPaint.setStyle(Paint.Style.STROKE);
        contentBorderPaint.setStrokeWidth(STROKE_WIDTH_CONTENT);

        // 内容填充画笔（半透明蓝色）
        contentFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        contentFillPaint.setColor(COLOR_CONTENT_SELECTED);
        contentFillPaint.setStyle(Paint.Style.FILL);

        // 文字画笔
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);

        // 对焦动画画笔
        focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        focusPaint.setColor(Color.WHITE);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(2f);
    }

    /**
     * 设置源图像尺寸（用于坐标转换）
     */
    public void setSourceSize(int width, int height) {
        synchronized (dataLock) {
            this.sourceWidth = width;
            this.sourceHeight = height;
            this.offsetX = 0;
            this.offsetY = 0;
            this.imageScale = 1f;
            this.useCustomCoordinates = false; // 使用默认坐标计算
            updateScaleLocked();
        }
    }

    /**
     * 设置 FILL_CENTER 模式的坐标转换参数
     * 注意：此方法不单独触发重绘，由后续的 setDetectionBox/setContentRegions 触发
     *
     * @param modelWidth  模型输出宽度
     * @param modelHeight 模型输出高度
     * @param scaleX      X 方向总缩放（模型坐标 -> View 坐标）
     * @param scaleY      Y 方向总缩放（模型坐标 -> View 坐标）
     * @param cropOffsetX X 方向裁剪偏移（正值表示左侧被裁剪）
     * @param cropOffsetY Y 方向裁剪偏移（正值表示顶部被裁剪）
     */
    public void setFillCenterCoordinates(int modelWidth, int modelHeight, float scaleX, float scaleY, int cropOffsetX, int cropOffsetY) {
        synchronized (dataLock) {
            this.sourceWidth = modelWidth;
            this.sourceHeight = modelHeight;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = -cropOffsetX;  // 负值，因为裁剪后坐标需要向左/上偏移
            this.offsetY = -cropOffsetY;
            this.useCustomCoordinates = true;
        }
        // 不单独触发重绘，坐标系统和绘制数据应同步更新后再重绘
    }

    /**
     * 设置源图像尺寸和偏移（用于 fitCenter 模式的坐标转换）
     *
     * @param width   源图像宽度
     * @param height  源图像高度
     * @param offsetX 图像在 View 中的 X 偏移
     * @param offsetY 图像在 View 中的 Y 偏移
     * @param scale   图像缩放比例
     */
    public void setSourceSizeWithOffset(int width, int height, int offsetX, int offsetY, float scale) {
        synchronized (dataLock) {
            this.sourceWidth = width;
            this.sourceHeight = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.imageScale = scale;
            this.useCustomCoordinates = true; // 标记使用自定义坐标
            // 不调用 updateScale()，直接使用传入的 scale
            this.scaleX = scale;
            this.scaleY = scale;
        }
        throttledInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 只有在未使用自定义坐标时才更新缩放比例
        synchronized (dataLock) {
            if (!useCustomCoordinates) {
                updateScaleLocked();
            }
        }
    }

    private void updateScale() {
        synchronized (dataLock) {
            updateScaleLocked();
        }
    }

    // 必须在持有 dataLock 时调用
    private void updateScaleLocked() {
        if (sourceWidth > 0 && sourceHeight > 0 && getWidth() > 0 && getHeight() > 0) {
            scaleX = (float) getWidth() / sourceWidth;
            scaleY = (float) getHeight() / sourceHeight;
        }
    }

    /**
     * 设置检测框（绿色）
     */
    public void setDetectionBox(RectF box, PointF[] corners) {
        synchronized (dataLock) {
            this.detectionBox = box != null ? new RectF(box) : null;
            this.detectionCorners = corners != null ? copyCorners(corners) : null;
            this.detectionAlpha = 1f; // 直接设置为不透明，不使用动画
        }
        throttledInvalidate();
    }

    /**
     * 隐藏检测框
     */
    public void hideDetectionBox() {
        synchronized (dataLock) {
            // 直接清除数据并重绘，不使用动画
            detectionBox = null;
            detectionCorners = null;
            detectionAlpha = 0f;
        }
        throttledInvalidate();
    }

    /**
     * 设置内容区域（蓝色框）
     * @param regions 内容区域列表
     */
    public void setContentRegions(List<ContentRegion> regions) {
        synchronized (dataLock) {
            this.contentRegions = regions != null ? new ArrayList<>(regions) : new ArrayList<>();
        }
        throttledInvalidate();
    }

    /**
     * 清除内容区域
     */
    public void clearContentRegions() {
        synchronized (dataLock) {
            this.contentRegions.clear();
        }
        throttledInvalidate();
    }

    /**
    /**
     * 清除所有绘制
     */
    public void clear() {
        synchronized (dataLock) {
            detectionBox = null;
            detectionCorners = null;
            detectionAlpha = 0f;
        }

        // 只取消对焦动画（其他动画已移除）
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }

        throttledInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            super.onDraw(canvas);

            // 创建数据的本地副本，避免渲染过程中数据被修改导致 GPU 驱动崩溃
            RectF localDetectionBox;
            PointF[] localDetectionCorners;
            float localDetectionAlpha;
            float localScaleX, localScaleY;
            int localOffsetX, localOffsetY;
            List<ContentRegion> localContentRegions;

            synchronized (dataLock) {
                localDetectionBox = detectionBox != null ? new RectF(detectionBox) : null;
                localDetectionCorners = detectionCorners != null ? copyCorners(detectionCorners) : null;
                localDetectionAlpha = detectionAlpha;
                localScaleX = scaleX;
                localScaleY = scaleY;
                localOffsetX = offsetX;
                localOffsetY = offsetY;
                localContentRegions = new ArrayList<>(contentRegions);
            }

            // 绘制检测框
            drawDetectionBox(canvas, localDetectionBox, localDetectionCorners, localDetectionAlpha, localScaleX, localScaleY, localOffsetX, localOffsetY);
            // 绘制内容区域
            drawContentRegions(canvas, localContentRegions, localScaleX, localScaleY, localOffsetX, localOffsetY);
            // 绘制对焦动画
            drawFocusAnimation(canvas);
            
        } catch (Exception e) {
            // 防止GPU渲染异常导致崩溃
            android.util.Log.e("OverlayView", ">> onDraw异常，跳过本次绘制", e);
        }
    }

    /**
     * 复制角点数组
     */
    private PointF[] copyCorners(PointF[] corners) {
        if (corners == null) return null;
        PointF[] copy = new PointF[corners.length];
        for (int i = 0; i < corners.length; i++) {
            copy[i] = new PointF(corners[i].x, corners[i].y);
        }
        return copy;
    }

    /**
     * 节流的 invalidate，避免 GPU 渲染队列积压
     */
    private void throttledInvalidate() {
        long now = System.currentTimeMillis();
        if (now - lastInvalidateTime >= MIN_INVALIDATE_INTERVAL_MS) {
            lastInvalidateTime = now;
            pendingInvalidate = false;
            try {
                postInvalidate();
            } catch (Exception e) {
                android.util.Log.e("OverlayView", ">> postInvalidate异常", e);
            }
        } else if (!pendingInvalidate) {
            pendingInvalidate = true;
            postDelayed(() -> {
                try {
                    pendingInvalidate = false;
                    lastInvalidateTime = System.currentTimeMillis();
                    invalidate();
                } catch (Exception e) {
                    android.util.Log.e("OverlayView", ">> 延迟invalidate异常", e);
                }
            }, MIN_INVALIDATE_INTERVAL_MS - (now - lastInvalidateTime));
        }
    }

    /**
     * 绘制对焦动画
     */
    private void drawFocusAnimation(Canvas canvas) {
        if (focusAlpha <= 0 || focusX < 0 || focusY < 0) {
            return;
        }

        focusPaint.setAlpha((int) (255 * focusAlpha));

        // 绘制对焦框（收缩动画）
        float size = 60f * (1f - focusAlpha * 0.3f); // 从60dp收缩到42dp
        float halfSize = size / 2f;

        // 外框
        canvas.drawRect(focusX - halfSize, focusY - halfSize, focusX + halfSize, focusY + halfSize, focusPaint);

        // 内部十字线
        float crossSize = size * 0.3f;
        canvas.drawLine(focusX - crossSize, focusY, focusX + crossSize, focusY, focusPaint);
        canvas.drawLine(focusX, focusY - crossSize, focusX, focusY + crossSize, focusPaint);
    }

    /**
     * 绘制检测框
     */
    private void drawDetectionBox(Canvas canvas, RectF box, PointF[] corners, float alpha, float scaleX, float scaleY, int offsetX, int offsetY) {
        if (box == null || alpha <= 0) {
            return;
        }

        detectionBoxPaint.setAlpha((int) (255 * alpha));

        if (corners != null && corners.length == 4) {
            // 绘制四边形
            Path path = new Path();
            PointF first = transformPoint(corners[0], scaleX, scaleY, offsetX, offsetY);
            path.moveTo(first.x, first.y);
            for (int i = 1; i < 4; i++) {
                PointF p = transformPoint(corners[i], scaleX, scaleY, offsetX, offsetY);
                path.lineTo(p.x, p.y);
            }
            path.close();
            canvas.drawPath(path, detectionBoxPaint);
        } else {
            // 绘制矩形
            RectF transformedBox = transformRect(box, scaleX, scaleY, offsetX, offsetY);
            canvas.drawRect(transformedBox, detectionBoxPaint);
        }
    }

    /**
     * 绘制内容区域（蓝色框）
     */
    private void drawContentRegions(Canvas canvas, List<ContentRegion> regions, 
            float scaleX, float scaleY, int offsetX, int offsetY) {
        if (regions == null || regions.isEmpty()) {
            return;
        }

        for (ContentRegion region : regions) {
            // 根据是否有内容设置不同透明度
            int alpha = region.hasContent ? 200 : 120;
            contentBorderPaint.setAlpha(alpha);
            
            if (region.corners != null && region.corners.length == 4) {
                // 绘制四边形
                Path path = new Path();
                PointF first = transformPoint(region.corners[0], scaleX, scaleY, offsetX, offsetY);
                path.moveTo(first.x, first.y);
                for (int i = 1; i < 4; i++) {
                    PointF p = transformPoint(region.corners[i], scaleX, scaleY, offsetX, offsetY);
                    path.lineTo(p.x, p.y);
                }
                path.close();
                canvas.drawPath(path, contentBorderPaint);
                
                // 绘制标签
                if (region.label != null && !region.label.isEmpty()) {
                    drawRegionLabel(canvas, region.label, first.x, first.y - 8, region.hasContent);
                }
            } else if (region.bounds != null) {
                // 绘制矩形
                RectF transformedBox = transformRect(region.bounds, scaleX, scaleY, offsetX, offsetY);
                canvas.drawRect(transformedBox, contentBorderPaint);
                
                // 绘制标签
                if (region.label != null && !region.label.isEmpty()) {
                    drawRegionLabel(canvas, region.label, transformedBox.left, transformedBox.top - 8, region.hasContent);
                }
            }
        }
    }

    /**
     * 绘制区域标签
     */
    private void drawRegionLabel(Canvas canvas, String label, float x, float y, boolean hasContent) {
        textPaint.setTextSize(24f);
        textPaint.setColor(hasContent ? COLOR_CONTENT_BORDER : Color.GRAY);
        canvas.drawText(label, x, y, textPaint);
    }

    /**
     * 转换点坐标（考虑偏移量）- 使用传入的参数
     */
    private PointF transformPoint(PointF point, float scaleX, float scaleY, int offsetX, int offsetY) {
        return new PointF(point.x * scaleX + offsetX, point.y * scaleY + offsetY);
    }

    /**
     * 转换矩形坐标（考虑偏移量）- 使用传入的参数
     */
    private RectF transformRect(RectF rect, float scaleX, float scaleY, int offsetX, int offsetY) {
        return new RectF(rect.left * scaleX + offsetX, rect.top * scaleY + offsetY, rect.right * scaleX + offsetX, rect.bottom * scaleY + offsetY);
    }

    /**
     * 反向转换点坐标（屏幕坐标到源坐标，考虑偏移量）
     */
    private PointF inverseTransformPoint(float x, float y) {
        synchronized (dataLock) {
            return new PointF((x - offsetX) / scaleX, (y - offsetY) / scaleY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false; // 不消费事件
    }

    /**
     * 显示对焦动画
     */
    public void showFocusAnimation(float x, float y) {
        focusX = x;
        focusY = y;

        if (focusAnimator != null) {
            focusAnimator.cancel();
        }

        focusAnimator = ValueAnimator.ofFloat(0f, 1f, 0f);
        focusAnimator.setDuration(800);
        focusAnimator.setInterpolator(new DecelerateInterpolator());
        focusAnimator.addUpdateListener(animation -> {
            focusAlpha = (Float) animation.getAnimatedValue();
            // 对焦动画使用直接 invalidate，因为频率较低
            invalidate();
        });
        focusAnimator.start();
    }
}
