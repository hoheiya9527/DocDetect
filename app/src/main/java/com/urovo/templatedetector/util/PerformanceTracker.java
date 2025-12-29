package com.urovo.templatedetector.util;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 性能监控工具
 * 简单的耗时记录和统计
 */
public class PerformanceTracker {

    private static final String TAG = "PerformanceTracker";
    private static final boolean ENABLE_LOGGING = true; // 可配置的日志开关

    /**
     * 性能指标类型
     */
    public enum MetricType {
        FRAME_PROCESSING("帧处理"),
        QUALITY_ASSESSMENT("质量评估"),
        LIGHT_ENHANCE("轻量增强"),
        FULL_ENHANCE("完整增强"),
        DETECTION("标签检测"),
        PERSPECTIVE_TRANSFORM("透视变换"),
        OCR_RECOGNITION("OCR识别"),
        BARCODE_DECODE("条码解码");

        private final String displayName;

        MetricType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 性能统计数据
     */
    public static class PerformanceStats {
        private long totalTime = 0;
        private long count = 0;
        private long minTime = Long.MAX_VALUE;
        private long maxTime = 0;

        public synchronized void addSample(long timeMs) {
            totalTime += timeMs;
            count++;
            minTime = Math.min(minTime, timeMs);
            maxTime = Math.max(maxTime, timeMs);
        }

        public synchronized double getAverageTime() {
            return count > 0 ? (double) totalTime / count : 0;
        }

        public synchronized long getMinTime() {
            return minTime == Long.MAX_VALUE ? 0 : minTime;
        }

        public synchronized long getMaxTime() {
            return maxTime;
        }

        public synchronized long getCount() {
            return count;
        }

        public synchronized void reset() {
            totalTime = 0;
            count = 0;
            minTime = Long.MAX_VALUE;
            maxTime = 0;
        }
    }

    private static final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    private static final Map<MetricType, PerformanceStats> stats = new ConcurrentHashMap<>();

    static {
        // 初始化统计对象
        for (MetricType type : MetricType.values()) {
            stats.put(type, new PerformanceStats());
        }
    }

    /**
     * 开始计时
     */
    public static void startTiming(MetricType type) {
        startTiming(type, null);
    }

    /**
     * 开始计时（带标识符，用于并发场景）
     */
    public static void startTiming(MetricType type, String identifier) {
        String key = createKey(type, identifier);
        startTimes.put(key, System.currentTimeMillis());
    }

    /**
     * 结束计时并记录
     */
    public static long endTiming(MetricType type) {
        return endTiming(type, null);
    }

    /**
     * 结束计时并记录（带标识符）
     */
    public static long endTiming(MetricType type, String identifier) {
        String key = createKey(type, identifier);
        Long startTime = startTimes.remove(key);
        
        if (startTime == null) {
            Log.w(TAG, "No start time found for " + type + " (identifier: " + identifier + ")");
            return 0;
        }

        long duration = System.currentTimeMillis() - startTime;
        recordTiming(type, duration);
        return duration;
    }

    /**
     * 直接记录耗时
     */
    public static void recordTiming(MetricType type, long durationMs) {
        PerformanceStats stat = stats.get(type);
        if (stat != null) {
            stat.addSample(durationMs);
            
            if (ENABLE_LOGGING) {
                Log.d(TAG, String.format("%s: %dms (avg: %.1fms, count: %d)", 
                    type.getDisplayName(), durationMs, stat.getAverageTime(), stat.getCount()));
            }
        }
    }

    /**
     * 获取性能统计
     */
    public static PerformanceStats getStats(MetricType type) {
        return stats.get(type);
    }

    /**
     * 获取所有统计数据
     */
    public static Map<MetricType, PerformanceStats> getAllStats() {
        return new HashMap<>(stats);
    }

    /**
     * 重置统计数据
     */
    public static void resetStats() {
        for (PerformanceStats stat : stats.values()) {
            stat.reset();
        }
        Log.d(TAG, "Performance stats reset");
    }

    /**
     * 重置特定类型的统计数据
     */
    public static void resetStats(MetricType type) {
        PerformanceStats stat = stats.get(type);
        if (stat != null) {
            stat.reset();
            Log.d(TAG, "Performance stats reset for " + type.getDisplayName());
        }
    }

    /**
     * 打印性能报告
     */
    public static void printReport() {
        if (!ENABLE_LOGGING) {
            return;
        }

        Log.d(TAG, "=== Performance Report ===");
        for (Map.Entry<MetricType, PerformanceStats> entry : stats.entrySet()) {
            MetricType type = entry.getKey();
            PerformanceStats stat = entry.getValue();
            
            if (stat.getCount() > 0) {
                Log.d(TAG, String.format("%s: avg=%.1fms, min=%dms, max=%dms, count=%d",
                    type.getDisplayName(), stat.getAverageTime(), 
                    stat.getMinTime(), stat.getMaxTime(), stat.getCount()));
            }
        }
        Log.d(TAG, "=========================");
    }

    /**
     * 创建键值
     */
    private static String createKey(MetricType type, String identifier) {
        return identifier != null ? type.name() + "_" + identifier : type.name();
    }

    /**
     * 便捷的计时器类
     */
    public static class Timer implements AutoCloseable {
        private final MetricType type;
        private final String identifier;
        private final long startTime;

        public Timer(MetricType type) {
            this(type, null);
        }

        public Timer(MetricType type, String identifier) {
            this.type = type;
            this.identifier = identifier;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void close() {
            long duration = System.currentTimeMillis() - startTime;
            recordTiming(type, duration);
        }
    }
}