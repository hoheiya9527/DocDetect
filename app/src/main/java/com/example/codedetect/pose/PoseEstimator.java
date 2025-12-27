package com.example.codedetect.pose;

import android.graphics.Point;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.codedetect.model.BarcodeInfo;
import com.example.codedetect.model.LayoutPose;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point3;

import java.util.ArrayList;
import java.util.List;

/**
 * 姿态估算器
 * 使用PnP算法从多个条码计算版面的3D姿态
 */
public class PoseEstimator {
    
    private static final String TAG = "PoseEstimator";
    
    // 相机内参（需要根据实际设备校准）
    private Mat cameraMatrix;
    private MatOfDouble distCoeffs;
    
    // 版面物理尺寸（米）
    private float layoutWidth = 0.21f;  // A4纸宽度
    private float layoutHeight = 0.297f; // A4纸高度
    
    public PoseEstimator() {
        initializeCameraParameters();
    }
    
    /**
     * 初始化相机参数（简化版，实际应通过标定获得）
     */
    private void initializeCameraParameters() {
        // 假设的相机内参（1080p分辨率）
        cameraMatrix = new Mat(3, 3, CvType.CV_64FC1);
        cameraMatrix.put(0, 0,
                1000, 0, 540,
                0, 1000, 960,
                0, 0, 1
        );
        
        // 畸变系数（假设无畸变）
        distCoeffs = new MatOfDouble(0, 0, 0, 0, 0);
    }
    
    /**
     * 设置相机内参
     */
    public void setCameraIntrinsics(double fx, double fy, double cx, double cy) {
        cameraMatrix.put(0, 0,
                fx, 0, cx,
                0, fy, cy,
                0, 0, 1
        );
    }
    
    /**
     * 设置版面尺寸
     */
    public void setLayoutSize(float width, float height) {
        this.layoutWidth = width;
        this.layoutHeight = height;
    }
    
    /**
     * 从多个条码估算版面姿态
     * 
     * @param barcodes 检测到的条码列表
     * @return 版面姿态，如果估算失败返回null
     */
    @Nullable
    public LayoutPose estimatePose(@NonNull List<BarcodeInfo> barcodes) {
        // 至少需要4个角点（1个完整条码）
        if (barcodes.isEmpty()) {
            return null;
        }
        
        // 收集所有有效的2D-3D对应点
        List<org.opencv.core.Point> imagePoints = new ArrayList<>();
        List<Point3> objectPoints = new ArrayList<>();
        
        for (BarcodeInfo barcode : barcodes) {
            if (!barcode.hasCornerPoints() || !barcode.hasKnownPosition()) {
                continue;
            }
            
            Point[] corners = barcode.getCornerPoints();
            float knownX = barcode.getKnownX();
            float knownY = barcode.getKnownY();
            
            // 假设条码尺寸为3cm x 3cm
            float barcodeSize = 0.03f;
            
            // 添加4个角点
            for (int i = 0; i < 4; i++) {
                imagePoints.add(new org.opencv.core.Point(corners[i].x, corners[i].y));
                
                // 计算3D坐标（相对于版面左上角）
                float x3d = knownX + (i % 2) * barcodeSize;
                float y3d = knownY + (i / 2) * barcodeSize;
                objectPoints.add(new Point3(x3d, y3d, 0));
            }
        }
        
        // 至少需要4个点对
        if (imagePoints.size() < 4) {
            Log.w(TAG, "Not enough points for pose estimation: " + imagePoints.size());
            return null;
        }
        
        return solvePnP(imagePoints, objectPoints);
    }
    
    /**
     * 使用PnP算法求解姿态
     */
    @Nullable
    private LayoutPose solvePnP(List<org.opencv.core.Point> imagePoints, 
                                List<Point3> objectPoints) {
        try {
            MatOfPoint2f imagePointsMat = new MatOfPoint2f();
            imagePointsMat.fromList(imagePoints);
            
            MatOfPoint3f objectPointsMat = new MatOfPoint3f();
            objectPointsMat.fromList(objectPoints);
            
            Mat rvec = new Mat();
            Mat tvec = new Mat();
            
            // 求解PnP
            boolean success = Calib3d.solvePnP(
                    objectPointsMat,
                    imagePointsMat,
                    cameraMatrix,
                    distCoeffs,
                    rvec,
                    tvec
            );
            
            if (!success) {
                Log.w(TAG, "PnP solving failed");
                return null;
            }
            
            // 提取旋转和平移向量
            float[] rotation = new float[3];
            float[] translation = new float[3];
            
            rvec.get(0, 0, rotation);
            tvec.get(0, 0, translation);
            
            LayoutPose pose = new LayoutPose(translation, rotation, System.currentTimeMillis());
            
            // 计算置信度（基于重投影误差）
            float confidence = calculateConfidence(
                    objectPointsMat, imagePointsMat, rvec, tvec
            );
            pose.setConfidence(confidence);
            
            // 清理
            imagePointsMat.release();
            objectPointsMat.release();
            rvec.release();
            tvec.release();
            
            return pose;
            
        } catch (Exception e) {
            Log.e(TAG, "Error in PnP solving", e);
            return null;
        }
    }
    
    /**
     * 计算姿态估算的置信度（基于重投影误差）
     */
    private float calculateConfidence(MatOfPoint3f objectPoints, MatOfPoint2f imagePoints,
                                     Mat rvec, Mat tvec) {
        try {
            MatOfPoint2f projectedPoints = new MatOfPoint2f();
            
            Calib3d.projectPoints(
                    objectPoints,
                    rvec,
                    tvec,
                    cameraMatrix,
                    distCoeffs,
                    projectedPoints
            );
            
            // 计算平均重投影误差
            org.opencv.core.Point[] original = imagePoints.toArray();
            org.opencv.core.Point[] projected = projectedPoints.toArray();
            
            double totalError = 0;
            for (int i = 0; i < original.length; i++) {
                double dx = original[i].x - projected[i].x;
                double dy = original[i].y - projected[i].y;
                totalError += Math.sqrt(dx * dx + dy * dy);
            }
            
            double avgError = totalError / original.length;
            
            // 转换为置信度（误差越小，置信度越高）
            // 假设误差<5像素为高置信度
            float confidence = (float) Math.max(0, 1.0 - avgError / 20.0);
            
            projectedPoints.release();
            
            return confidence;
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating confidence", e);
            return 0.5f;
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (cameraMatrix != null) {
            cameraMatrix.release();
        }
        if (distCoeffs != null) {
            distCoeffs.release();
        }
    }
}
