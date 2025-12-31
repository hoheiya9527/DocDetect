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
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.urovo.templatedetector.model.DetectedRegion;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域绘制视图
 * 支持在图片上绘制、选择、调整矩形区域
 * 支持显示自动检测的区域供用户点击选择
 */
public class RegionDrawView extends View {

    private static final String TAG = "RegionDrawView";

    /** 区域边框颜色 */
    private static final int COLOR_REGION_NORMAL = Color.parseColor("#2196F3");
    private static final int COLOR_REGION_SELECTED = Color.parseColor("#FF5722");
    private static final int COLOR_REGION_DRAWING = Color.parseColor("#4CAF50");
    /** 检测到的区域边框颜色（蓝色） */
    private static final int COLOR_DETECTED = Color.parseColor("#2196F3");
    /** 检测区域选中后的填充颜色（半透明蓝色） */
    private static final int COLOR_DETECTED_SELECTED_FILL = Color.parseColor("#802196F3");
    
    /** 区域填充透明度 */
    private static final int FILL_ALPHA = 40;
    /** 检测区域未选中时的填充透明度 */
    private static final int DETECTED_FILL_ALPHA = 20;
    
    /** 最小区域尺寸 */
    private static final float MIN_REGION_SIZE = 50f;

    /**
     * 可绘制区域数据（用于模板区域显示）
     */
    public static class DrawableRegion {
        private long id;
        private String name;
        private RectF bounds;
        private boolean selected;
        private int color;

        public DrawableRegion(long id, String name, RectF bounds) {
            this.id = id;
            this.name = name;
            this.bounds = new RectF(bounds);
            this.selected = false;
            this.color = COLOR_REGION_NORMAL;
        }

        public long getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public RectF getBounds() { return bounds; }
        public boolean isSelected() { return selected; }
        public void setSelected(boolean selected) { 
            this.selected = selected;
            this.color = selected ? COLOR_REGION_SELECTED : COLOR_REGION_NORMAL;
        }
    }

    /**
     * 区域操作回调
     */
    public interface OnRegionListener {
        void onRegionCreated(RectF bounds);
        void onRegionSelected(DrawableRegion region);
        void onRegionMoved(DrawableRegion region, RectF newBounds);
        void onRegionDeleted(DrawableRegion region);
    }

    /**
     * 检测区域点击回调
     */
    public interface OnDetectedRegionClickListener {
        void onDetectedRegionClicked(DetectedRegion region, boolean isSelected);
    }

    private Bitmap bitmap;
    private Matrix imageMatrix = new Matrix();
    private Matrix inverseMatrix = new Matrix();
    private RectF imageRect = new RectF();
    private RectF viewRect = new RectF();

    private List<DrawableRegion> regions = new ArrayList<>();
    private DrawableRegion selectedRegion;
    
    /** 检测到的区域列表 */
    private List<DetectedRegion> detectedRegions = new ArrayList<>();

    private Paint imagePaint;
    private Paint regionPaint;
    private Paint regionFillPaint;
    private Paint textPaint;
    private Paint detectedPaint;
    private Paint detectedFillPaint;

    // 绘制状态
    private boolean isDrawingMode = false;
    private boolean isDrawing = false;
    private PointF drawStart = new PointF();
    private RectF drawingRect = new RectF();

    // 拖拽状态
    private boolean isDragging = false;
    private PointF dragStart = new PointF();
    private RectF dragOriginalBounds = new RectF();

    private OnRegionListener listener;
    private OnDetectedRegionClickListener detectedRegionClickListener;

    public RegionDrawView(Context context) {
        super(context);
        init();
    }

    public RegionDrawView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RegionDrawView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
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
        textPaint.setTextSize(32f);
        textPaint.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        
        detectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detectedPaint.setStyle(Paint.Style.STROKE);
        detectedPaint.setStrokeWidth(3f);
        detectedPaint.setColor(COLOR_DETECTED);
        
        detectedFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        detectedFillPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * 设置图片
     */
    public void setImageBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        if (bitmap != null) {
            imageRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }
        requestLayout();
        invalidate();
    }

    /**
     * 设置区域列表
     */
    public void setRegions(List<DrawableRegion> regions) {
        this.regions.clear();
        if (regions != null) {
            this.regions.addAll(regions);
        }
        selectedRegion = null;
        invalidate();
    }

    /**
     * 添加区域
     */
    public void addRegion(DrawableRegion region) {
        regions.add(region);
        invalidate();
    }

    /**
     * 移除区域
     */
    public void removeRegion(DrawableRegion region) {
        regions.remove(region);
        if (selectedRegion == region) {
            selectedRegion = null;
        }
        invalidate();
    }

    /**
     * 清空所有区域
     */
    public void clearRegions() {
        regions.clear();
        selectedRegion = null;
        invalidate();
    }

    /**
     * 设置绘制模式
     */
    public void setDrawingMode(boolean enabled) {
        this.isDrawingMode = enabled;
        if (!enabled) {
            isDrawing = false;
        }
        invalidate();
    }

    /**
     * 是否处于绘制模式
     */
    public boolean isDrawingMode() {
        return isDrawingMode;
    }

    /**
     * 获取选中的区域
     */
    public DrawableRegion getSelectedRegion() {
        return selectedRegion;
    }

    /**
     * 设置回调
     */
    public void setOnRegionListener(OnRegionListener listener) {
        this.listener = listener;
    }

    /**
     * 设置检测区域点击回调
     */
    public void setOnDetectedRegionClickListener(OnDetectedRegionClickListener listener) {
        this.detectedRegionClickListener = listener;
    }

    /**
     * 设置检测到的区域列表
     */
    public void setDetectedRegions(List<DetectedRegion> regions) {
        this.detectedRegions.clear();
        if (regions != null) {
            this.detectedRegions.addAll(regions);
        }
        invalidate();
    }

    /**
     * 获取已选中的检测区域列表
     */
    public List<DetectedRegion> getSelectedDetectedRegions() {
        List<DetectedRegion> selected = new ArrayList<>();
        for (DetectedRegion region : detectedRegions) {
            if (region.isSelected()) {
                selected.add(region);
            }
        }
        return selected;
    }

    /**
     * 清空检测区域
     */
    public void clearDetectedRegions() {
        detectedRegions.clear();
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

        imageMatrix.invert(inverseMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (bitmap != null) {
            canvas.drawBitmap(bitmap, imageMatrix, imagePaint);
        }

        // 绘制检测到的区域
        for (DetectedRegion region : detectedRegions) {
            drawDetectedRegion(canvas, region);
        }

        // 绘制已有区域
        for (DrawableRegion region : regions) {
            drawRegion(canvas, region);
        }

        // 绘制正在绘制的区域
        if (isDrawing && !drawingRect.isEmpty()) {
            RectF viewBounds = new RectF();
            imageMatrix.mapRect(viewBounds, drawingRect);
            
            regionPaint.setColor(COLOR_REGION_DRAWING);
            regionFillPaint.setColor(COLOR_REGION_DRAWING);
            regionFillPaint.setAlpha(FILL_ALPHA);
            
            canvas.drawRect(viewBounds, regionFillPaint);
            canvas.drawRect(viewBounds, regionPaint);
        }
    }

    private void drawDetectedRegion(Canvas canvas, DetectedRegion region) {
        RectF bounds = region.getBoundingBox();
        if (bounds == null) return;
        
        RectF viewBounds = new RectF();
        imageMatrix.mapRect(viewBounds, bounds);

        if (region.isSelected()) {
            detectedFillPaint.setColor(COLOR_DETECTED_SELECTED_FILL);
            canvas.drawRect(viewBounds, detectedFillPaint);
        } else {
            detectedFillPaint.setColor(COLOR_DETECTED);
            detectedFillPaint.setAlpha(DETECTED_FILL_ALPHA);
            canvas.drawRect(viewBounds, detectedFillPaint);
        }
        
        canvas.drawRect(viewBounds, detectedPaint);
    }

    private void drawRegion(Canvas canvas, DrawableRegion region) {
        RectF viewBounds = new RectF();
        imageMatrix.mapRect(viewBounds, region.bounds);

        int color = region.selected ? COLOR_REGION_SELECTED : COLOR_REGION_NORMAL;
        
        regionPaint.setColor(color);
        regionFillPaint.setColor(color);
        regionFillPaint.setAlpha(FILL_ALPHA);

        canvas.drawRect(viewBounds, regionFillPaint);
        canvas.drawRect(viewBounds, regionPaint);

        if (region.name != null && !region.name.isEmpty()) {
            float textX = viewBounds.left + 8;
            float textY = viewBounds.top + textPaint.getTextSize() + 4;
            canvas.drawText(region.name, textX, textY, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }

        float viewX = event.getX();
        float viewY = event.getY();

        float[] pts = {viewX, viewY};
        inverseMatrix.mapPoints(pts);
        float imageX = pts[0];
        float imageY = pts[1];

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                return handleActionDown(viewX, viewY, imageX, imageY);
            case MotionEvent.ACTION_MOVE:
                return handleActionMove(viewX, viewY, imageX, imageY);
            case MotionEvent.ACTION_UP:
                return handleActionUp(viewX, viewY, imageX, imageY);
            case MotionEvent.ACTION_CANCEL:
                isDrawing = false;
                isDragging = false;
                invalidate();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private boolean handleActionDown(float viewX, float viewY, float imageX, float imageY) {
        if (isDrawingMode) {
            isDrawing = true;
            drawStart.set(imageX, imageY);
            drawingRect.set(imageX, imageY, imageX, imageY);
            return true;
        } else {
            // 检查是否点击了检测到的区域
            DetectedRegion hitDetected = findDetectedRegionAt(viewX, viewY);
            if (hitDetected != null) {
                hitDetected.toggleSelected();
                invalidate();
                
                if (detectedRegionClickListener != null) {
                    detectedRegionClickListener.onDetectedRegionClicked(hitDetected, hitDetected.isSelected());
                }
                return true;
            }
            
            // 检查是否点击了已有区域
            DrawableRegion hitRegion = findRegionAt(viewX, viewY);
            
            if (hitRegion != null) {
                selectRegion(hitRegion);
                isDragging = true;
                dragStart.set(imageX, imageY);
                dragOriginalBounds.set(hitRegion.bounds);
                return true;
            } else {
                selectRegion(null);
                return true;
            }
        }
    }

    private DetectedRegion findDetectedRegionAt(float viewX, float viewY) {
        for (int i = detectedRegions.size() - 1; i >= 0; i--) {
            DetectedRegion region = detectedRegions.get(i);
            RectF bounds = region.getBoundingBox();
            if (bounds == null) continue;
            
            RectF viewBounds = new RectF();
            imageMatrix.mapRect(viewBounds, bounds);
            
            if (viewBounds.contains(viewX, viewY)) {
                return region;
            }
        }
        return null;
    }

    private boolean handleActionMove(float viewX, float viewY, float imageX, float imageY) {
        if (isDrawing) {
            drawingRect.left = Math.min(drawStart.x, imageX);
            drawingRect.top = Math.min(drawStart.y, imageY);
            drawingRect.right = Math.max(drawStart.x, imageX);
            drawingRect.bottom = Math.max(drawStart.y, imageY);
            drawingRect.intersect(imageRect);
            invalidate();
            return true;
        } else if (isDragging && selectedRegion != null) {
            float dx = imageX - dragStart.x;
            float dy = imageY - dragStart.y;
            
            RectF newBounds = new RectF(dragOriginalBounds);
            newBounds.offset(dx, dy);
            
            if (newBounds.left < 0) newBounds.offset(-newBounds.left, 0);
            if (newBounds.top < 0) newBounds.offset(0, -newBounds.top);
            if (newBounds.right > imageRect.right) newBounds.offset(imageRect.right - newBounds.right, 0);
            if (newBounds.bottom > imageRect.bottom) newBounds.offset(0, imageRect.bottom - newBounds.bottom);
            
            selectedRegion.bounds.set(newBounds);
            invalidate();
            return true;
        }
        return false;
    }

    private boolean handleActionUp(float viewX, float viewY, float imageX, float imageY) {
        if (isDrawing) {
            isDrawing = false;
            
            if (drawingRect.width() >= MIN_REGION_SIZE && drawingRect.height() >= MIN_REGION_SIZE) {
                if (listener != null) {
                    listener.onRegionCreated(new RectF(drawingRect));
                }
            }
            
            drawingRect.setEmpty();
            invalidate();
            return true;
        } else if (isDragging && selectedRegion != null) {
            isDragging = false;
            
            if (listener != null) {
                listener.onRegionMoved(selectedRegion, new RectF(selectedRegion.bounds));
            }
            return true;
        }
        return false;
    }

    private DrawableRegion findRegionAt(float viewX, float viewY) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            DrawableRegion region = regions.get(i);
            RectF viewBounds = new RectF();
            imageMatrix.mapRect(viewBounds, region.bounds);
            
            if (viewBounds.contains(viewX, viewY)) {
                return region;
            }
        }
        return null;
    }

    private void selectRegion(DrawableRegion region) {
        if (selectedRegion != null) {
            selectedRegion.setSelected(false);
        }
        
        selectedRegion = region;
        
        if (selectedRegion != null) {
            selectedRegion.setSelected(true);
            if (listener != null) {
                listener.onRegionSelected(selectedRegion);
            }
        }
        
        invalidate();
    }

    /**
     * 删除选中的区域
     */
    public void deleteSelectedRegion() {
        if (selectedRegion != null) {
            DrawableRegion toDelete = selectedRegion;
            removeRegion(toDelete);
            if (listener != null) {
                listener.onRegionDeleted(toDelete);
            }
        }
    }

    /**
     * 根据ID选中区域
     */
    public boolean selectRegionById(long regionId) {
        for (DrawableRegion region : regions) {
            if (region.getId() == regionId) {
                selectRegion(region);
                return true;
            }
        }
        return false;
    }

    /**
     * 获取区域数量
     */
    public int getRegionCount() {
        return regions.size();
    }

    /**
     * 获取指定位置的区域
     */
    public DrawableRegion getRegionAt(int index) {
        if (index >= 0 && index < regions.size()) {
            return regions.get(index);
        }
        return null;
    }

    /**
     * 设置检测区域的选中状态
     */
    public void setDetectedRegionSelected(String detectedId, boolean selected) {
        for (DetectedRegion region : detectedRegions) {
            if (region.getId().equals(detectedId)) {
                region.setSelected(selected);
                invalidate();
                break;
            }
        }
    }
}
