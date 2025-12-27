package com.example.docdetect;

import android.app.Application;
import android.util.Log;

import org.opencv.android.OpenCVLoader;

public class ARApplication extends Application {
    private static final String TAG = "ARApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        // 加载OpenCV库
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully");
        } else {
            Log.e(TAG, "OpenCV initialization failed");
        }
    }
}
