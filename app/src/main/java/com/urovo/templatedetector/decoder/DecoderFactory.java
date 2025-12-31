package com.urovo.templatedetector.decoder;

import android.content.Context;

import com.urovo.templatedetector.decoder.kyd.KydBarcodeDecoder;

/**
 * 解码器工厂
 * 使用MLKit条码解码器
 */
public class DecoderFactory {

    private static final String TAG = "DecoderFactory";

    public enum DecoderType {
        MLKIT,
        KYD
    }

    private DecoderFactory() {
    }

    /**
     * 创建解码器
     *
     * @param type    解码器类型（当前固定使用MLKit）
     * @param context 应用上下文
     * @return MLKit解码器实例
     */
    public static BarcodeDecoder create(DecoderType type, Context context) {
//        Log.d(TAG, "Creating MLKit barcode decoder");
//        return new MlkitBarcodeDecoder();
        return new KydBarcodeDecoder(context);
    }
}
