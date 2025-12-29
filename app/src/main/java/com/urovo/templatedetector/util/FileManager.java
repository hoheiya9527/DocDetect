package com.urovo.templatedetector.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件管理工具类
 * 用于统计和删除Download/Scan目录下的YUV和JPEG文件
 */
public class FileManager {

    private static final String TAG = "FileManager";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + File.separator + "Scan";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    /**
     * 保存YUV数据和JPEG图像到Download/Scan目录
     *
     * @param context 上下文
     * @param yuvData YUV数据（NV21格式）
     * @param width   图像宽度
     * @param height  图像高度
     * @param prefix  文件名前缀（可选）
     * @return 是否保存成功
     */
    public static boolean saveYuvAndJpeg(@NonNull Context context, @NonNull byte[] yuvData,
                                         int width, int height, String prefix) {
        String timestamp = DATE_FORMAT.format(new Date());
        String baseName = (prefix != null ? prefix + "_" : "") + timestamp + "_" + width + "x" + height;

        boolean yuvSuccess = saveYuvData(context, yuvData, width, height, baseName + ".yuv");
//        boolean jpegSuccess = saveJpegFromYuv(context, yuvData, width, height, baseName + ".jpg");

        if (yuvSuccess //&& jpegSuccess
        ) {
            Log.d(TAG, "Successfully saved YUV and JPEG: " + baseName);
            return true;
        } else {
            Log.w(TAG, "Partial save failure - YUV: " + yuvSuccess //+ ", JPEG: " + jpegSuccess
            );
            return false;
        }
    }

    /**
     * 保存增强后的JPEG图像到Download/Scan目录
     * 用于保存经过OpenCV增强处理的高质量JPEG
     *
     * @param context  上下文
     * @param jpegData JPEG数据
     * @param prefix   文件名前缀（可选）
     * @return 是否保存成功
     */
    public static boolean saveEnhancedJpeg(@NonNull Context context, @NonNull byte[] jpegData, String prefix) {
        String timestamp = DATE_FORMAT.format(new Date());
        String fileName = (prefix != null ? prefix + "_" : "") + timestamp + ".jpg";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveJpegViaMediaStore(context, jpegData, fileName);
            } else {
                return saveJpegViaLegacyStorage(jpegData, fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save enhanced JPEG: " + fileName, e);
            return false;
        }
    }

    /**
     * Android 10+ 保存JPEG到Download/Scan目录
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static boolean saveJpegViaMediaStore(@NonNull Context context,
                                                 @NonNull byte[] jpegData,
                                                 @NonNull String fileName) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.Downloads.IS_PENDING, 0);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.e(TAG, "Failed to create MediaStore entry for enhanced JPEG");
                return false;
            }

            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    Log.e(TAG, "Failed to open output stream for enhanced JPEG");
                    return false;
                }
                os.write(jpegData);
                os.flush();
                Log.d(TAG, "Successfully saved enhanced JPEG: " + fileName +
                        ", size: " + jpegData.length + " bytes");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save JPEG via MediaStore", e);
            return false;
        }
    }

    /**
     * Android 9及以下保存JPEG到Download/Scan目录
     */
    private static boolean saveJpegViaLegacyStorage(@NonNull byte[] jpegData, @NonNull String fileName) {
        try {
            File file = getLegacyFile(fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(jpegData);
                fos.flush();
                Log.d(TAG, "Successfully saved enhanced JPEG (legacy): " + fileName +
                        ", size: " + jpegData.length + " bytes");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save JPEG (legacy)", e);
            return false;
        }
    }

    /**
     * 保存YUV原始数据
     */
    private static boolean saveYuvData(@NonNull Context context, @NonNull byte[] yuvData,
                                       int width, int height, @NonNull String fileName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return saveViaMediaStore(context, yuvData, fileName, "application/octet-stream");
            } else {
                return saveViaLegacyStorage(yuvData, fileName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save YUV data: " + fileName, e);
            return false;
        }
    }

    /**
     * 将YUV转换为JPEG并保存
     */
    private static boolean saveJpegFromYuv(@NonNull Context context, @NonNull byte[] yuvData,
                                           int width, int height, @NonNull String fileName) {
        try {
            // 使用YuvImage进行高效转换
            YuvImage yuvImage = new YuvImage(yuvData, ImageFormat.NV21, width, height, null);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = createContentValues(fileName, "image/jpeg");
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

                if (uri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry for JPEG");
                    return false;
                }

                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) {
                        Log.e(TAG, "Failed to open output stream for JPEG");
                        return false;
                    }
                    return yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, os);
                }
            } else {
                File file = getLegacyFile(fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    return yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, fos);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save JPEG: " + fileName, e);
            return false;
        }
    }

    /**
     * Android 10+ 使用MediaStore保存
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static boolean saveViaMediaStore(@NonNull Context context, @NonNull byte[] data,
                                             @NonNull String fileName, @NonNull String mimeType) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = createContentValues(fileName, mimeType);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);

            if (uri == null) {
                Log.e(TAG, "Failed to create MediaStore entry");
                return false;
            }

            try (OutputStream os = resolver.openOutputStream(uri)) {
                if (os == null) {
                    Log.e(TAG, "Failed to open output stream");
                    return false;
                }
                os.write(data);
                os.flush();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore save failed", e);
            return false;
        }
    }

    /**
     * Android 9及以下使用传统文件系统保存
     */
    private static boolean saveViaLegacyStorage(@NonNull byte[] data, @NonNull String fileName) {
        try {
            File file = getLegacyFile(fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
                fos.flush();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Legacy storage save failed", e);
            return false;
        }
    }

    /**
     * 创建ContentValues
     */
    private static ContentValues createContentValues(@NonNull String fileName, @NonNull String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.Downloads.IS_PENDING, 0);
        }

        return values;
    }

    /**
     * 获取传统存储路径的文件对象
     */
    private static File getLegacyFile(@NonNull String fileName) {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File scanDir = new File(downloadDir, "Scan");

        if (!scanDir.exists()) {
            scanDir.mkdirs();
        }

        return new File(scanDir, fileName);
    }

    /**
     * 文件统计结果
     */
    public static class FileStats {
        public int yuvCount = 0;
        public int jpegCount = 0;

        public int getTotalCount() {
            return yuvCount + jpegCount;
        }
    }

    /**
     * 统计Download/Scan目录下的文件数量
     */
    public static FileStats getFileStats(@NonNull Context context) {
        FileStats stats = new FileStats();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用MediaStore查询
            stats = getFileStatsViaMediaStore(context);
        } else {
            // Android 9及以下使用文件系统查询
            stats = getFileStatsViaLegacyStorage();
        }

        Log.d(TAG, "File stats - YUV: " + stats.yuvCount + ", JPEG: " + stats.jpegCount);
        return stats;
    }

    /**
     * 删除Download/Scan目录下的所有文件
     */
    public static boolean deleteAllFiles(@NonNull Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return deleteFilesViaMediaStore(context);
            } else {
                return deleteFilesViaLegacyStorage();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete files", e);
            return false;
        }
    }

    /**
     * Android 10+ 使用MediaStore统计文件
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static FileStats getFileStatsViaMediaStore(@NonNull Context context) {
        FileStats stats = new FileStats();
        ContentResolver resolver = context.getContentResolver();

        // 查询条件：Download/Scan目录下的文件
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = new String[]{RELATIVE_PATH + "%"};

        try (Cursor cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE},
                selection,
                selectionArgs,
                null)) {

            if (cursor != null) {
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);

                while (cursor.moveToNext()) {
                    String fileName = cursor.getString(nameColumn);
                    String mimeType = cursor.getString(mimeColumn);

                    if (fileName != null) {
                        if (fileName.endsWith(".yuv")) {
                            stats.yuvCount++;
                        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                                "image/jpeg".equals(mimeType)) {
                            stats.jpegCount++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query files via MediaStore", e);
        }

        return stats;
    }

    /**
     * Android 9及以下使用文件系统统计
     */
    private static FileStats getFileStatsViaLegacyStorage() {
        FileStats stats = new FileStats();

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File scanDir = new File(downloadDir, "Scan");

        if (!scanDir.exists() || !scanDir.isDirectory()) {
            return stats;
        }

        File[] files = scanDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".yuv")) {
                        stats.yuvCount++;
                    } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                        stats.jpegCount++;
                    }
                }
            }
        }

        return stats;
    }

    /**
     * Android 10+ 使用MediaStore删除文件
     */
    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static boolean deleteFilesViaMediaStore(@NonNull Context context) {
        ContentResolver resolver = context.getContentResolver();

        // 查询条件：Download/Scan目录下的文件
        String selection = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
        String[] selectionArgs = new String[]{RELATIVE_PATH + "%"};

        try (Cursor cursor = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Downloads._ID},
                selection,
                selectionArgs,
                null)) {

            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                int deletedCount = 0;

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    Uri uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(id));

                    try {
                        int deleted = resolver.delete(uri, null, null);
                        if (deleted > 0) {
                            deletedCount++;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to delete file with ID: " + id, e);
                    }
                }

                Log.d(TAG, "Deleted " + deletedCount + " files via MediaStore");
                return deletedCount > 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete files via MediaStore", e);
            return false;
        }

        return false;
    }

    /**
     * Android 9及以下使用文件系统删除
     */
    private static boolean deleteFilesViaLegacyStorage() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File scanDir = new File(downloadDir, "Scan");

        if (!scanDir.exists() || !scanDir.isDirectory()) {
            return false;
        }

        File[] files = scanDir.listFiles();
        if (files == null || files.length == 0) {
            return false;
        }

        int deletedCount = 0;
        for (File file : files) {
            if (file.isFile() && file.delete()) {
                deletedCount++;
            }
        }

        Log.d(TAG, "Deleted " + deletedCount + " files via legacy storage");
        return deletedCount > 0;
    }
}
