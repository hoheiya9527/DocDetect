package com.urovo.templatedetector.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.urovo.templatedetector.model.MatchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 匹配结果展示视图
 * 在图片上绘制匹配到的区域框和标签
 */
public class MatchResultView extends View {

    private static final int COLOR_REGION_SUCCESS = Color.parseColor("#4CAF50");
    private static final int COLOR_REGION_FAILED = Color.parseColor("#F44336");
    private static final int COLOR_REGION_PENDING = Color.parseColor("#2196F3");
    private static final int FILL_ALPHA = 40;

    private Bitmap bitmap;
    private Matrix imageMatrix = new Matrix();
    private RectF imageRect = new RectF();
    private RectF viewRect = new RectF();

    private List<RegionOverlay> regionOverlays = new ArrayList<>();
    private boolean hasMatch = false;

    private Paint imagePaint;
    private Paint regionPaint;
    private Paint regionFillPaint;
    private Paint textPaint;
    private Paint textBgPaint;

    public MatchResultView(Context context) {
        super(context);
        init();
    }

    public MatchResultView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MatchResultView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        imagePaint.setFilterBitmap(true);

        regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        regionPaint.setStyle(Paint.Style.STROKE);
        regionPaint.setStrokeWidth(4f);

        regionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        regionFillPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);

        textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textBgPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * 设置匹配结果
     */
    public void setResult(Bitmap bitmap, MatchResult result) {
        this.bitmap = bitmap;
        this.hasMatch = result != null && result.isSuccess();
        
        regionOverlays.clear();
        
        if (bitmap != null) {
            imageRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        if (result != null && result.getTransformedRegions() != null) {
            for (MatchResult.TransformedRegion region : result.getTransformedRegions()) {
                RegionOverlay overlay = new RegionOverlay();
                overlay.name = region.getRegion().getName();
                overlay.bounds = region.getTransformedBounds();
                overlay.corners = region.getTransformedCorners();
                overlay.hasContent = region.hasContent();
                overlay.content = region.getContent();
                regionOverlays.add(overlay);
            }
        }

        requestLayout();
        invalidate();
    }

    /**
     * 设置无匹配结果（仅显示图片）
     */
    public void setNoMatch(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.hasMatch = false;
        this.regionOverlays.clear();
        
        if (bitmap != null) {
            imageRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        requestLayout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewRect.set(0, 0, w, h);
        updateImageMatrix();
    }

    private void updateImageMatrix() {
        if (bitmap == null || viewRect.isEmpty()) {
            return;
        }

        imageMatrix.reset();

        float scaleX = viewRect.width() / imageRect.width();
        float scaleY = viewRect.height() / imageRect.height();
        float scale = Math.min(scaleX, scaleY);

        float dx = (viewRect.width() - imageRect.width() * scale) / 2f;
        float dy = (viewRect.height() - imageRect.height() * scale) / 2f;

        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(dx, dy);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 绘制图片
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, imageMatrix, imagePaint);
        }

        // 绘制区域
        for (RegionOverlay overlay : regionOverlays) {
            drawRegion(canvas, overlay);
        }
    }

    private void drawRegion(Canvas canvas, RegionOverlay overlay) {
        if (overlay.bounds == null) {
            return;
        }

        // 变换坐标
        RectF viewBounds = new RectF();
        imageMatrix.mapRect(viewBounds, overlay.bounds);

        // 选择颜色
        int color = overlay.hasContent ? COLOR_REGION_SUCCESS : COLOR_REGION_FAILED;

        // 绘制填充
        regionFillPaint.setColor(color);
        regionFillPaint.setAlpha(FILL_ALPHA);
        canvas.drawRect(viewBounds, regionFillPaint);

        // 绘制边框
        regionPaint.setColor(color);
        canvas.drawRect(viewBounds, regionPaint);

        // 绘制标签背景
        float textWidth = textPaint.measureText(overlay.name);
        float textHeight = textPaint.getTextSize();
        float padding = 8f;
        
        RectF labelBg = new RectF(
                viewBounds.left,
                viewBounds.top - textHeight - padding * 2,
                viewBounds.left + textWidth + padding * 2,
                viewBounds.top
        );
        
        // 确保标签在视图内
        if (labelBg.top < 0) {
            labelBg.offset(0, -labelBg.top + viewBounds.height() + padding);
        }

        textBgPaint.setColor(color);
        canvas.drawRect(labelBg, textBgPaint);

        // 绘制标签文字
        canvas.drawText(overlay.name, labelBg.left + padding, labelBg.bottom - padding, textPaint);
    }

    /**
     * 区域覆盖层数据
     */
    private static class RegionOverlay {
        String name;
        RectF bounds;
        PointF[] corners;
        boolean hasContent;
        String content;
    }
}
