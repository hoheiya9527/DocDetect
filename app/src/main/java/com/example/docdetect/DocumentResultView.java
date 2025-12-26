package com.example.docdetect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class DocumentResultView extends View {
    
    private Bitmap bitmap;
    private Rect documentRect;
    private List<DetectedItem> detectedItems = new ArrayList<>();
    
    private Paint strokePaint;
    private Paint fillPaint;
    
    // 基础变换（让文档区域居中适应屏幕）
    private float baseScale = 1f;
    private float baseOffsetX = 0f;
    private float baseOffsetY = 0f;
    
    // 用户手势变换（缩放和平移）
    private float userScale = 1f;
    private float userOffsetX = 0f;
    private float userOffsetY = 0f;
    
    // 手势检测
    private ScaleGestureDetector scaleDetector;
    private float lastTouchX, lastTouchY;
    private int activePointerId = -1;
    private boolean isScaling = false;
    
    private Toast currentToast;
    
    // 缩放限制
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 5f;
    
    public DocumentResultView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        strokePaint = new Paint();
        strokePaint.setColor(0xFF0000FF);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(4f);
        
        fillPaint = new Paint();
        fillPaint.setColor(0x800000FF);
        fillPaint.setStyle(Paint.Style.FILL);
        
        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleListener());
    }
    
    public void setResult(Bitmap bitmap, Rect documentRect, List<DetectedItem> items) {
        this.bitmap = bitmap;
        this.documentRect = documentRect;
        this.detectedItems = items;
        userScale = 1f;
        userOffsetX = 0f;
        userOffsetY = 0f;
        
        if (getWidth() > 0 && getHeight() > 0) {
            calculateBaseTransform();
            if (documentRect != null) {
                focusOnDocumentRect();
            }
        }
        invalidate();
    }
    
    public void setDocumentBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        this.documentRect = null;
        userScale = 1f;
        userOffsetX = 0f;
        userOffsetY = 0f;
        calculateBaseTransform();
        invalidate();
    }
    
    public void setDetectedItems(List<DetectedItem> items) {
        this.detectedItems = items;
        invalidate();
    }
    
    private void calculateBaseTransform() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        
        // 始终按完整图片计算缩放，确保整张图都可见
        float scaleX = (float) getWidth() / bitmap.getWidth();
        float scaleY = (float) getHeight() / bitmap.getHeight();
        baseScale = Math.min(scaleX, scaleY);
        baseOffsetX = (getWidth() - bitmap.getWidth() * baseScale) / 2f;
        baseOffsetY = (getHeight() - bitmap.getHeight() * baseScale) / 2f;
    }

    private void focusOnDocumentRect() {
        if (documentRect == null || bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }

        // 计算文档区域在base变换后的屏幕坐标
        float docLeft = documentRect.left * baseScale + baseOffsetX;
        float docTop = documentRect.top * baseScale + baseOffsetY;
        float docRight = documentRect.right * baseScale + baseOffsetX;
        float docBottom = documentRect.bottom * baseScale + baseOffsetY;
        
        float docWidth = docRight - docLeft;
        float docHeight = docBottom - docTop;
        float docCenterX = (docLeft + docRight) / 2f;
        float docCenterY = (docTop + docBottom) / 2f;
        
        // 计算让文档区域适应屏幕需要的缩放（留10%边距）
        float targetWidth = getWidth() * 0.9f;
        float targetHeight = getHeight() * 0.9f;
        float scaleX = targetWidth / docWidth;
        float scaleY = targetHeight / docHeight;
        userScale = Math.min(scaleX, scaleY);
        userScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, userScale));
        
        // 屏幕中心
        float screenCenterX = getWidth() / 2f;
        float screenCenterY = getHeight() / 2f;
        
        // userScale是围绕屏幕中心缩放的
        // 缩放后文档中心的位置 = (docCenter - screenCenter) * userScale + screenCenter
        // 我们要让它等于screenCenter，所以：
        // (docCenter - screenCenter) * userScale + screenCenter + userOffset = screenCenter
        // userOffset = -(docCenter - screenCenter) * userScale
        userOffsetX = -(docCenterX - screenCenterX) * userScale;
        userOffsetY = -(docCenterY - screenCenterY) * userScale;
    }
    
    // 将图像坐标转换为屏幕坐标
    private float toScreenX(float imageX) {
        return (imageX * baseScale + baseOffsetX) * userScale + userOffsetX + getWidth() * (1 - userScale) / 2f;
    }
    
    private float toScreenY(float imageY) {
        return (imageY * baseScale + baseOffsetY) * userScale + userOffsetY + getHeight() * (1 - userScale) / 2f;
    }
    
    // 将屏幕坐标转换为图像坐标
    private float toImageX(float screenX) {
        float adjusted = (screenX - userOffsetX - getWidth() * (1 - userScale) / 2f) / userScale;
        return (adjusted - baseOffsetX) / baseScale;
    }
    
    private float toImageY(float screenY) {
        float adjusted = (screenY - userOffsetY - getHeight() * (1 - userScale) / 2f) / userScale;
        return (adjusted - baseOffsetY) / baseScale;
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        calculateBaseTransform();
        if (documentRect != null) {
            focusOnDocumentRect();
        }
        invalidate();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (bitmap == null) {
            return;
        }
        
        // 计算组合变换矩阵
        Matrix matrix = new Matrix();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate(baseOffsetX, baseOffsetY);
        matrix.postScale(userScale, userScale, getWidth() / 2f, getHeight() / 2f);
        matrix.postTranslate(userOffsetX, userOffsetY);
        
        canvas.drawBitmap(bitmap, matrix, null);
        
        // 绘制检测框（根据缩放调整线宽）
        float adjustedStrokeWidth = 4f / userScale;
        strokePaint.setStrokeWidth(Math.max(1f, adjustedStrokeWidth));
        
        for (DetectedItem item : detectedItems) {
            Rect rect = item.rect;
            
            float left = toScreenX(rect.left);
            float top = toScreenY(rect.top);
            float right = toScreenX(rect.right);
            float bottom = toScreenY(rect.bottom);
            
            if (item.isSelected) {
                canvas.drawRect(left, top, right, bottom, fillPaint);
            }
            canvas.drawRect(left, top, right, bottom, strokePaint);
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 先让缩放检测器处理
        scaleDetector.onTouchEvent(event);
        
        int action = event.getActionMasked();
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                isScaling = false;
                break;
                
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && activePointerId >= 0) {
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex >= 0) {
                        float x = event.getX(pointerIndex);
                        float y = event.getY(pointerIndex);
                        
                        // 只有缩放后才允许平移
                        if (userScale > 1.01f) {
                            userOffsetX += x - lastTouchX;
                            userOffsetY += y - lastTouchY;
                            constrainOffset();
                            invalidate();
                        }
                        
                        lastTouchX = x;
                        lastTouchY = y;
                    }
                }
                break;
                
            case MotionEvent.ACTION_UP:
                // 如果不是缩放操作，检查点击
                if (!isScaling && !scaleDetector.isInProgress()) {
                    handleClick(event.getX(), event.getY());
                }
                activePointerId = -1;
                break;
                
            case MotionEvent.ACTION_CANCEL:
                activePointerId = -1;
                break;
                
            case MotionEvent.ACTION_POINTER_UP:
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    if (newPointerIndex < event.getPointerCount()) {
                        lastTouchX = event.getX(newPointerIndex);
                        lastTouchY = event.getY(newPointerIndex);
                        activePointerId = event.getPointerId(newPointerIndex);
                    }
                }
                break;
        }
        
        return true;
    }
    
    private void handleClick(float x, float y) {
        // 转换为图像坐标
        float imageX = toImageX(x);
        float imageY = toImageY(y);
        
        for (DetectedItem item : detectedItems) {
            Rect rect = item.rect;
            
            if (imageX >= rect.left && imageX <= rect.right && 
                imageY >= rect.top && imageY <= rect.bottom) {
                item.isSelected = !item.isSelected;
                invalidate();
                
                if (currentToast != null) {
                    currentToast.cancel();
                }
                String content = item.content != null ? item.content : "";
                currentToast = Toast.makeText(getContext(), content, Toast.LENGTH_SHORT);
                currentToast.show();
                return;
            }
        }
    }
    
    private void constrainOffset() {
        // 限制平移范围，不要让图片完全移出屏幕
        float maxOffsetX = getWidth() * (userScale - 1) / 2f + 100;
        float maxOffsetY = getHeight() * (userScale - 1) / 2f + 100;
        
        userOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, userOffsetX));
        userOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, userOffsetY));
    }
    
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            isScaling = true;
            return true;
        }
        
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float newScale = userScale * scaleFactor;
            
            // 限制缩放范围
            newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
            
            userScale = newScale;
            constrainOffset();
            invalidate();
            return true;
        }
    }
}
