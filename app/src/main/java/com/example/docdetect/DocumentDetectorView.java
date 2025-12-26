package com.example.docdetect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

public class DocumentDetectorView extends View {
    
    private Paint paint;
    private Rect documentRect;
    
    public DocumentDetectorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        paint = new Paint();
        paint.setColor(0xFF00FF00); // 绿色
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8f);
    }
    
    public void setDocumentRect(Rect rect) {
        this.documentRect = rect;
        invalidate();
    }
    
    public void clearRect() {
        this.documentRect = null;
        invalidate();
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (documentRect != null) {
            canvas.drawRect(documentRect, paint);
        }
    }
}
