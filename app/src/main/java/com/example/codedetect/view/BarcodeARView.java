package com.example.codedetect.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.codedetect.model.BarcodeInfo;
import com.google.ar.core.TrackingState;

import java.util.ArrayList;
import java.util.List;

/**
 * 条码AR视图
 * 在相机预览上叠加显示条码检测框和追踪状态
 */
public class BarcodeARView extends View {
    
    private final Paint barcodeBoxPaint;
    private final Paint cornerPaint;
    private final Paint statusTextPaint;
    
    private List<BarcodeInfo> detectedBarcodes = new ArrayList<>();
    private TrackingState trackingState = TrackingState.PAUSED;
    private String statusMessage = "";
    
    public BarcodeARView(Context context) {
        this(context, null);
    }
    
    public BarcodeARView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        
        // 条码边框画笔
        barcodeBoxPaint = new Paint();
        barcodeBoxPaint.setColor(Color.GREEN);
        barcodeBoxPaint.setStyle(Paint.Style.STROKE);
        barcodeBoxPaint.setStrokeWidth(4f);
        barcodeBoxPaint.setAntiAlias(true);
        
        // 角点画笔
        cornerPaint = new Paint();
        cornerPaint.setColor(Color.YELLOW);
        cornerPaint.setStyle(Paint.Style.FILL);
        cornerPaint.setAntiAlias(true);
        
        // 状态文字画笔
        statusTextPaint = new Paint();
        statusTextPaint.setColor(Color.WHITE);
        statusTextPaint.setTextSize(40f);
        statusTextPaint.setAntiAlias(true);
        statusTextPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);
    }
    
    /**
     * 更新检测到的条码
     */
    public void updateBarcodes(@NonNull List<BarcodeInfo> barcodes) {
        this.detectedBarcodes = new ArrayList<>(barcodes);
        invalidate();
    }
    
    /**
     * 更新追踪状态
     */
    public void updateTrackingState(@NonNull TrackingState state) {
        this.trackingState = state;
        
        switch (state) {
            case TRACKING:
                statusMessage = "追踪中";
                barcodeBoxPaint.setColor(Color.GREEN);
                break;
            case PAUSED:
                statusMessage = "追踪暂停";
                barcodeBoxPaint.setColor(Color.YELLOW);
                break;
            case STOPPED:
                statusMessage = "追踪停止";
                barcodeBoxPaint.setColor(Color.RED);
                break;
        }
        
        invalidate();
    }
    
    /**
     * 设置状态消息
     */
    public void setStatusMessage(@NonNull String message) {
        this.statusMessage = message;
        invalidate();
    }
    
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制条码边框
        for (BarcodeInfo barcode : detectedBarcodes) {
            if (barcode.hasCornerPoints()) {
                drawBarcodeBox(canvas, barcode);
            }
        }
        
        // 绘制状态信息
        drawStatusInfo(canvas);
    }
    
    /**
     * 绘制条码边框
     */
    private void drawBarcodeBox(@NonNull Canvas canvas, @NonNull BarcodeInfo barcode) {
        android.graphics.Point[] corners = barcode.getCornerPoints();
        if (corners == null || corners.length != 4) {
            return;
        }
        
        // 绘制边框
        for (int i = 0; i < 4; i++) {
            int nextIndex = (i + 1) % 4;
            canvas.drawLine(
                    corners[i].x, corners[i].y,
                    corners[nextIndex].x, corners[nextIndex].y,
                    barcodeBoxPaint
            );
        }
        
        // 绘制角点
        for (android.graphics.Point corner : corners) {
            canvas.drawCircle(corner.x, corner.y, 8f, cornerPaint);
        }
        
        // 绘制条码内容
        if (corners[0] != null) {
            Paint textPaint = new Paint(statusTextPaint);
            textPaint.setTextSize(30f);
            canvas.drawText(
                    barcode.getRawValue(),
                    corners[0].x,
                    corners[0].y - 10,
                    textPaint
            );
        }
    }
    
    /**
     * 绘制状态信息
     */
    private void drawStatusInfo(@NonNull Canvas canvas) {
        float x = 20f;
        float y = 80f;
        
        // 追踪状态
        canvas.drawText("状态: " + statusMessage, x, y, statusTextPaint);
        
        // 检测到的条码数量
        y += 50f;
        canvas.drawText("条码数: " + detectedBarcodes.size(), x, y, statusTextPaint);
        
        // 追踪状态指示器
        y += 50f;
        Paint indicatorPaint = new Paint();
        indicatorPaint.setStyle(Paint.Style.FILL);
        
        switch (trackingState) {
            case TRACKING:
                indicatorPaint.setColor(Color.GREEN);
                break;
            case PAUSED:
                indicatorPaint.setColor(Color.YELLOW);
                break;
            case STOPPED:
                indicatorPaint.setColor(Color.RED);
                break;
        }
        
        canvas.drawCircle(x + 100f, y - 15f, 15f, indicatorPaint);
    }
    
    /**
     * 清除显示
     */
    public void clear() {
        detectedBarcodes.clear();
        statusMessage = "";
        invalidate();
    }
}
