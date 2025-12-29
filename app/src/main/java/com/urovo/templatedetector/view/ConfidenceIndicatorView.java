package com.urovo.templatedetector.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 置信度指示器视图
 * 显示当前检测置信度值
 */
public class ConfidenceIndicatorView extends View {

    private Paint textPaint;
    private Paint backgroundPaint;
    private RectF backgroundRect;
    
    private double confidence = 0.0;
    private String confidenceText = "0.000";
    
    // 颜色配置
    private static final int BACKGROUND_COLOR = 0x80000000; // 半透明黑色
    private static final int TEXT_COLOR_HIGH = 0xFF00FF00;  // 绿色（高置信度）
    private static final int TEXT_COLOR_MED = 0xFFFFFF00;   // 黄色（中等置信度）
    private static final int TEXT_COLOR_LOW = 0xFFFF0000;   // 红色（低置信度）
    
    // 阈值配置
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.8;
    private static final double MED_CONFIDENCE_THRESHOLD = 0.5;
    
    public ConfidenceIndicatorView(Context context) {
        super(context);
        init();
    }

    public ConfidenceIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ConfidenceIndicatorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 初始化画笔
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(dpToPx(14));
        textPaint.setColor(TEXT_COLOR_LOW);
        textPaint.setTextAlign(Paint.Align.CENTER);
        
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(BACKGROUND_COLOR);
        
        backgroundRect = new RectF();
    }

    /**
     * 更新置信度值
     */
    public void updateConfidence(double confidence) {
        this.confidence = confidence;
        // 显示为3位小数格式 (0.700)
        this.confidenceText = String.format("%.3f", confidence);
        
        // 根据置信度设置文字颜色
        if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            textPaint.setColor(TEXT_COLOR_HIGH);
        } else if (confidence >= MED_CONFIDENCE_THRESHOLD) {
            textPaint.setColor(TEXT_COLOR_MED);
        } else {
            textPaint.setColor(TEXT_COLOR_LOW);
        }
        
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (getVisibility() != VISIBLE) {
            return;
        }
        
        // 计算文字尺寸
        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float textWidth = textPaint.measureText(confidenceText);
        float textHeight = fontMetrics.bottom - fontMetrics.top;
        
        // 计算背景矩形
        float padding = dpToPx(8);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        
        backgroundRect.set(
            centerX - textWidth / 2 - padding,
            centerY - textHeight / 2 - padding,
            centerX + textWidth / 2 + padding,
            centerY + textHeight / 2 + padding
        );
        
        // 绘制圆角背景
        float cornerRadius = dpToPx(4);
        canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint);
        
        // 绘制文字
        float textY = centerY - (fontMetrics.top + fontMetrics.bottom) / 2;
        canvas.drawText(confidenceText, centerX, textY, textPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 计算所需尺寸 - 按3位小数格式计算宽度
        float textWidth = textPaint.measureText("0.000");
        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float textHeight = fontMetrics.bottom - fontMetrics.top;
        
        float padding = dpToPx(8);
        int width = (int) (textWidth + padding * 2);
        int height = (int) (textHeight + padding * 2);
        
        setMeasuredDimension(width, height);
    }

    /**
     * dp转px
     */
    private float dpToPx(float dp) {
        return dp * getContext().getResources().getDisplayMetrics().density;
    }
}