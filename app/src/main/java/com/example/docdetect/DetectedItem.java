package com.example.docdetect;

import android.graphics.Rect;

public class DetectedItem {
    public enum Type {
        TEXT_BLOCK,
        BARCODE,
        QR_CODE
    }
    
    public final Type type;
    public final Rect rect;
    public final String content;
    public boolean isSelected;
    
    public DetectedItem(Type type, Rect rect, String content) {
        this.type = type;
        this.rect = rect;
        this.content = content;
        this.isSelected = false;
    }
}
