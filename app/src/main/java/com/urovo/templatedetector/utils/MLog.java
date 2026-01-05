package com.urovo.templatedetector.utils;

import android.util.Log;

/**
 * 统一日志管理类
 * 所有日志内容使用'>>'作为前缀以便过滤查看
 */
public class MLog {

    private static final String LOG_PREFIX = ">> ";
    private static boolean debugEnabled = true;

    private MLog() {
        // 工具类，禁止实例化
    }

    /**
     * 设置调试模式
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /**
     * Debug日志
     */
    public static void d(String tag, String msg) {
        if (debugEnabled) {
            Log.d(tag, LOG_PREFIX + msg);
        }
    }

    /**
     * Info日志
     */
    public static void i(String tag, String msg) {
        Log.i(tag, LOG_PREFIX + msg);
    }

    /**
     * Warning日志
     */
    public static void w(String tag, String msg) {
        Log.w(tag, LOG_PREFIX + msg);
    }

    /**
     * Error日志
     */
    public static void e(String tag, String msg) {
        Log.e(tag, LOG_PREFIX + msg);
    }

    /**
     * Error日志（带异常）
     */
    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, LOG_PREFIX + msg, tr);
    }

    /**
     * Verbose日志
     */
    public static void v(String tag, String msg) {
        if (debugEnabled) {
            Log.v(tag, LOG_PREFIX + msg);
        }
    }
}