package com.urovo.templatedetector.matcher;

import android.util.Log;

import org.opencv.core.CvType;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 特征数据序列化工具
 * 用于将 Mat 描述符和 KeyPoint 列表保存到文件/从文件加载
 */
public class FeatureSerializer {

    private static final String TAG = "FeatureSerializer";
    
    /** 文件魔数，用于验证文件格式 */
    private static final int MAGIC_DESCRIPTORS = 0x44455343; // "DESC"
    private static final int MAGIC_KEYPOINTS = 0x4B455950;   // "KEYP"
    
    /** 文件版本 */
    private static final int VERSION = 1;

    private FeatureSerializer() {
        // 工具类，禁止实例化
    }

    /**
     * 保存描述符到文件
     * @param descriptors 描述符 Mat
     * @param filePath 文件路径
     * @return 是否成功
     */
    public static boolean saveDescriptors(Mat descriptors, String filePath) {
        if (descriptors == null || descriptors.empty()) {
            Log.e(TAG, "saveDescriptors: invalid descriptors");
            return false;
        }

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(filePath))) {
            // 写入魔数和版本
            dos.writeInt(MAGIC_DESCRIPTORS);
            dos.writeInt(VERSION);
            
            // 写入 Mat 信息
            int rows = descriptors.rows();
            int cols = descriptors.cols();
            int type = descriptors.type();
            
            dos.writeInt(rows);
            dos.writeInt(cols);
            dos.writeInt(type);
            
            // 写入数据
            int channels = descriptors.channels();
            int elemSize = (int) descriptors.elemSize1();
            int totalBytes = rows * cols * channels * elemSize;
            
            byte[] data = new byte[totalBytes];
            descriptors.get(0, 0, data);
            dos.write(data);
            
            Log.d(TAG, "saveDescriptors: saved " + rows + "x" + cols + " type=" + type + " to " + filePath);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "saveDescriptors failed", e);
            return false;
        }
    }

    /**
     * 从文件加载描述符
     * @param filePath 文件路径
     * @return 描述符 Mat，失败返回 null
     */
    public static Mat loadDescriptors(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            Log.e(TAG, "loadDescriptors: file not found: " + filePath);
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(filePath))) {
            // 验证魔数和版本
            int magic = dis.readInt();
            if (magic != MAGIC_DESCRIPTORS) {
                Log.e(TAG, "loadDescriptors: invalid magic number");
                return null;
            }
            
            int version = dis.readInt();
            if (version > VERSION) {
                Log.e(TAG, "loadDescriptors: unsupported version: " + version);
                return null;
            }
            
            // 读取 Mat 信息
            int rows = dis.readInt();
            int cols = dis.readInt();
            int type = dis.readInt();
            
            // 创建 Mat 并读取数据
            Mat descriptors = new Mat(rows, cols, type);
            
            int channels = descriptors.channels();
            int elemSize = (int) descriptors.elemSize1();
            int totalBytes = rows * cols * channels * elemSize;
            
            byte[] data = new byte[totalBytes];
            dis.readFully(data);
            descriptors.put(0, 0, data);
            
            Log.d(TAG, "loadDescriptors: loaded " + rows + "x" + cols + " type=" + type + " from " + filePath);
            return descriptors;
            
        } catch (IOException e) {
            Log.e(TAG, "loadDescriptors failed", e);
            return null;
        }
    }

    /**
     * 保存特征点到文件
     * @param keypoints 特征点
     * @param filePath 文件路径
     * @return 是否成功
     */
    public static boolean saveKeypoints(MatOfKeyPoint keypoints, String filePath) {
        if (keypoints == null || keypoints.empty()) {
            Log.e(TAG, "saveKeypoints: invalid keypoints");
            return false;
        }

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(filePath))) {
            // 写入魔数和版本
            dos.writeInt(MAGIC_KEYPOINTS);
            dos.writeInt(VERSION);
            
            // 获取特征点数组
            KeyPoint[] kpArray = keypoints.toArray();
            
            // 写入数量
            dos.writeInt(kpArray.length);
            
            // 写入每个特征点
            for (KeyPoint kp : kpArray) {
                dos.writeFloat((float) kp.pt.x);
                dos.writeFloat((float) kp.pt.y);
                dos.writeFloat(kp.size);
                dos.writeFloat(kp.angle);
                dos.writeFloat(kp.response);
                dos.writeInt(kp.octave);
                dos.writeInt(kp.class_id);
            }
            
            Log.d(TAG, "saveKeypoints: saved " + kpArray.length + " keypoints to " + filePath);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "saveKeypoints failed", e);
            return false;
        }
    }

    /**
     * 从文件加载特征点
     * @param filePath 文件路径
     * @return 特征点，失败返回 null
     */
    public static MatOfKeyPoint loadKeypoints(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            Log.e(TAG, "loadKeypoints: file not found: " + filePath);
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(filePath))) {
            // 验证魔数和版本
            int magic = dis.readInt();
            if (magic != MAGIC_KEYPOINTS) {
                Log.e(TAG, "loadKeypoints: invalid magic number");
                return null;
            }
            
            int version = dis.readInt();
            if (version > VERSION) {
                Log.e(TAG, "loadKeypoints: unsupported version: " + version);
                return null;
            }
            
            // 读取数量
            int count = dis.readInt();
            
            // 读取每个特征点
            List<KeyPoint> kpList = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                float x = dis.readFloat();
                float y = dis.readFloat();
                float size = dis.readFloat();
                float angle = dis.readFloat();
                float response = dis.readFloat();
                int octave = dis.readInt();
                int classId = dis.readInt();
                
                kpList.add(new KeyPoint(x, y, size, angle, response, octave, classId));
            }
            
            MatOfKeyPoint keypoints = new MatOfKeyPoint();
            keypoints.fromList(kpList);
            
            Log.d(TAG, "loadKeypoints: loaded " + count + " keypoints from " + filePath);
            return keypoints;
            
        } catch (IOException e) {
            Log.e(TAG, "loadKeypoints failed", e);
            return null;
        }
    }

    /**
     * 删除特征文件
     * @param descriptorsPath 描述符文件路径
     * @param keypointsPath 特征点文件路径
     */
    public static void deleteFeatureFiles(String descriptorsPath, String keypointsPath) {
        if (descriptorsPath != null) {
            File descFile = new File(descriptorsPath);
            if (descFile.exists()) {
                descFile.delete();
            }
        }
        if (keypointsPath != null) {
            File kpFile = new File(keypointsPath);
            if (kpFile.exists()) {
                kpFile.delete();
            }
        }
    }
}
