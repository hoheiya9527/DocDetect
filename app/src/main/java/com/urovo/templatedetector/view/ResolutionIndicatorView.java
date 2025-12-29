package com.urovo.templatedetector.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Size;
import android.view.View;

import com.urovo.templatedetector.model.CameraSettings;

/**
 * 分辨率指示器视图
 * 显示在预览左上角
 */
public class ResolutionIndicatorView extends View {

    private static final int BACKGROUND_COLOR = 0x70000000; // 适中透明度
    private static final int TEXT_COLOR = Color.WHITE;      // 白色文字

    private Paint backgroundPaint;
    private Paint textPaint;
    private RectF backgroundRect = new RectF();

    private Size analysisResolution;
    private String displayText = "";
    private float measuredTextWidth = 0f;

    public ResolutionIndicatorView(Context context) {
        super(context);
        init();
    }

    public ResolutionIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ResolutionIndicatorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(BACKGROUND_COLOR);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(24f); // 稍小字体
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(2f, 0f, 1f, 0x80000000);
        textPaint.setFakeBoldText(true); // 加粗文字
    }

    public void setAnalysisResolution(Size resolution) {
        this.analysisResolution = resolution;
        if (resolution != null) {
            this.displayText = CameraSettings.getResolutionDisplayName(resolution);
            this.measuredTextWidth = textPaint.measureText(displayText);
        } else {
            this.displayText = "";
            this.measuredTextWidth = 0f;
        }
        requestLayout(); // 重新测量布局
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (displayText.isEmpty()) {
            setMeasuredDimension(0, 0);
            return;
        }

        // 确保文字完整显示
        if (measuredTextWidth == 0f) {
            measuredTextWidth = textPaint.measureText(displayText);
        }

        // 添加足够的内边距
        int paddingH = 20; // 水平内边距
        int paddingV = 12; // 垂直内边距
        int width = (int) (measuredTextWidth + paddingH * 2);
        int height = (int) (textPaint.getTextSize() + paddingV * 2);

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        backgroundRect.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (displayText.isEmpty()) {
            return;
        }

        // 绘制背景（圆角矩形）
        float cornerRadius = 12f;
        canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint);

        // 绘制文字（精确居中）
        float textX = getWidth() / 2f;
        // 使用基线居中算法
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(displayText, textX, textY, textPaint);
    }
}