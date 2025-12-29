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

import com.urovo.templatedetector.model.ContentRegion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 覆盖层View
 * 用于在相机预览上绘制检测框和内容区域
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

    // Paint对象
    private Paint detectionBoxPaint;
    private Paint contentBorderPaint;
    private Paint contentFillPaint;
    //    private Paint cornerPaint;
    private Paint textPaint;

    // 检测框数据
    private RectF detectionBox;
    private PointF[] detectionCorners;
    private float detectionAlpha = 0f;

    // 内容区域数据
    private List<ContentRegion> contentRegions = new ArrayList<>();
    private Map<String, Float> regionAlphas = new HashMap<>();

    // 对焦动画
    private float focusX = -1f;
    private float focusY = -1f;
    private float focusAlpha = 0f;
    private ValueAnimator focusAnimator;
    private Paint focusPaint;

    // 点击监听
    private OnRegionClickListener regionClickListener;
    private float scaleX, scaleY = 1f;
    private int sourceWidth = 1;
    private int sourceHeight = 1;
    // fitCenter 模式下的偏移量
    private int offsetX = 0;
    private int offsetY = 0;
    private float imageScale = 1f;
    // 是否使用自定义坐标设置（防止被onSizeChanged覆盖）
    private boolean useCustomCoordinates = false;

    public interface OnRegionClickListener {
        void onRegionClick(ContentRegion region);
    }

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

//        // 角点画笔
//        cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
//        cornerPaint.setColor(COLOR_CORNER_POINT);
//        cornerPaint.setStyle(Paint.Style.FILL);

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
        this.sourceWidth = width;
        this.sourceHeight = height;
        this.offsetX = 0;
        this.offsetY = 0;
        this.imageScale = 1f;
        this.useCustomCoordinates = false; // 使用默认坐标计算
        updateScale();
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
        this.sourceWidth = width;
        this.sourceHeight = height;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.imageScale = scale;
        this.useCustomCoordinates = true; // 标记使用自定义坐标
        // 不调用 updateScale()，直接使用传入的 scale
        this.scaleX = scale;
        this.scaleY = scale;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 只有在未使用自定义坐标时才更新缩放比例
        if (!useCustomCoordinates) {
            updateScale();
        }
    }

    private void updateScale() {
        if (sourceWidth > 0 && sourceHeight > 0 && getWidth() > 0 && getHeight() > 0) {
            scaleX = (float) getWidth() / sourceWidth;
            scaleY = (float) getHeight() / sourceHeight;
        }
    }

    /**
     * 设置检测框（绿色）
     */
    public void setDetectionBox(RectF box, PointF[] corners) {
        this.detectionBox = box;
        this.detectionCorners = corners;
        this.detectionAlpha = 1f; // 直接设置为不透明，不使用动画
        invalidate();
    }

    /**
     * 隐藏检测框
     */
    public void hideDetectionBox() {
        // 直接清除数据并重绘，不使用动画
        detectionBox = null;
        detectionCorners = null;
        detectionAlpha = 0f;

        invalidate();
    }

    /**
     * 设置内容区域（蓝色边框）
     */
    public void setContentRegions(List<ContentRegion> regions) {
        android.util.Log.d("OverlayView", "setContentRegions called, regions count=" + regions.size());
        
        // 取消所有动画，避免频繁重绘
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }
        
        this.contentRegions = new ArrayList<>(regions);

        // 初始化透明度
        regionAlphas.clear();
        for (ContentRegion region : regions) {
            regionAlphas.put(region.getId(), 1f);
            android.util.Log.d("OverlayView", "setContentRegions: region=" + region.getId() +
                    ", box=" + region.getBoundingBox() +
                    ", type=" + region.getType());
        }

        invalidate();
    }

    /**
     * 更新区域选择状态
     */
    public void updateSelection(String regionId, boolean selected) {
        for (ContentRegion region : contentRegions) {
            if (region.getId().equals(regionId)) {
                region.setSelected(selected);
                // 直接设置透明度，不使用动画
                regionAlphas.put(regionId, 1f);
                invalidate();
                break;
            }
        }
    }

    /**
     * 清除所有绘制
     */
    public void clear() {
        detectionBox = null;
        detectionCorners = null;
        detectionAlpha = 0f;
        contentRegions.clear();
        regionAlphas.clear();

        // 只取消对焦动画（其他动画已移除）
        if (focusAnimator != null) {
            focusAnimator.cancel();
        }

        invalidate();
    }

    /**
     * 设置区域点击监听
     */
    public void setOnRegionClickListener(OnRegionClickListener listener) {
        this.regionClickListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 绘制检测框
        drawDetectionBox(canvas);

        // 绘制内容区域
        drawContentRegions(canvas);

        // 绘制对焦动画
        drawFocusAnimation(canvas);
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
        canvas.drawRect(focusX - halfSize, focusY - halfSize,
                focusX + halfSize, focusY + halfSize, focusPaint);

        // 内部十字线
        float crossSize = size * 0.3f;
        canvas.drawLine(focusX - crossSize, focusY, focusX + crossSize, focusY, focusPaint);
        canvas.drawLine(focusX, focusY - crossSize, focusX, focusY + crossSize, focusPaint);
    }

    /**
     * 绘制检测框
     */
    private void drawDetectionBox(Canvas canvas) {
        if (detectionBox == null || detectionAlpha <= 0) {
            return;
        }

        detectionBoxPaint.setAlpha((int) (255 * detectionAlpha));

        if (detectionCorners != null && detectionCorners.length == 4) {
            // 绘制四边形
            Path path = new Path();
            PointF first = transformPoint(detectionCorners[0]);
            path.moveTo(first.x, first.y);
            for (int i = 1; i < 4; i++) {
                PointF p = transformPoint(detectionCorners[i]);
                path.lineTo(p.x, p.y);
            }
            path.close();
            canvas.drawPath(path, detectionBoxPaint);
        } else {
            // 绘制矩形
            RectF transformedBox = transformRect(detectionBox);
            canvas.drawRect(transformedBox, detectionBoxPaint);
        }
    }

    /**
     * 绘制内容区域
     */
    private void drawContentRegions(Canvas canvas) {
        for (ContentRegion region : contentRegions) {
            RectF box = region.getBoundingBox();
            if (box == null) continue;

            android.util.Log.d("OverlayView", "drawContentRegions: region=" + region.getId() +
                    ", original box=" + box +
                    ", scaleX=" + scaleX + ", scaleY=" + scaleY +
                    ", offsetX=" + offsetX + ", offsetY=" + offsetY);

            RectF transformedBox = transformRect(box);
            android.util.Log.d("OverlayView", "drawContentRegions: transformed box=" + transformedBox);

            Float alpha = regionAlphas.get(region.getId());
            if (alpha == null) alpha = 1f;

            // 绘制填充（如果选中）
            if (region.isSelected()) {
                // 使用更明显的透明度：160 (约63%) 让选中效果更清晰
                contentFillPaint.setAlpha((int) (160 * alpha));
                canvas.drawRect(transformedBox, contentFillPaint);
            }

            // 绘制边框
            contentBorderPaint.setAlpha((int) (255 * alpha));
            if (region.isSelected()) {
                contentBorderPaint.setStrokeWidth(STROKE_WIDTH_CONTENT + 2);
            } else {
                contentBorderPaint.setStrokeWidth(STROKE_WIDTH_CONTENT);
            }
            canvas.drawRect(transformedBox, contentBorderPaint);

            // 绘制类型标签
            String label = region.getType() == ContentRegion.ContentType.OCR ? "OCR" : region.getFormat();
            textPaint.setAlpha((int) (255 * alpha));
            // 确保标签不会绘制在框外
            float labelX = Math.max(transformedBox.left + 4, 4);
            float labelY = Math.max(transformedBox.top - 4, textPaint.getTextSize());
            canvas.drawText(label, labelX, labelY, textPaint);
        }
    }

    /**
     * 转换点坐标（考虑偏移量）
     */
    private PointF transformPoint(PointF point) {
        return new PointF(point.x * scaleX + offsetX, point.y * scaleY + offsetY);
    }

    /**
     * 转换矩形坐标（考虑偏移量）
     */
    private RectF transformRect(RectF rect) {
        return new RectF(
                rect.left * scaleX + offsetX,
                rect.top * scaleY + offsetY,
                rect.right * scaleX + offsetX,
                rect.bottom * scaleY + offsetY
        );
    }

    /**
     * 反向转换点坐标（屏幕坐标到源坐标，考虑偏移量）
     */
    private PointF inverseTransformPoint(float x, float y) {
        return new PointF((x - offsetX) / scaleX, (y - offsetY) / scaleY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 只处理内容区域的点击，其他区域不拦截触摸事件
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // 检查是否点击了内容区域
            float x = event.getX();
            float y = event.getY();
            PointF sourcePoint = inverseTransformPoint(x, y);
            for (ContentRegion region : contentRegions) {
                if (region.contains(sourcePoint.x, sourcePoint.y)) {
                    return true; // 消费事件，等待 ACTION_UP
                }
            }
            return false; // 不消费事件，让其他View处理
        }

        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX();
            float y = event.getY();

            // 检查是否点击了内容区域
            PointF sourcePoint = inverseTransformPoint(x, y);
            for (ContentRegion region : contentRegions) {
                if (region.contains(sourcePoint.x, sourcePoint.y)) {
                    if (regionClickListener != null) {
                        regionClickListener.onRegionClick(region);
                    }
                    return true;
                }
            }
        }
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
            invalidate();
        });
        focusAnimator.start();
    }
}
