package com.urovo.templatedetector.model;

import android.graphics.PointF;

import org.opencv.core.Mat;

public class CorrectionData {
    public Mat correctedMat;
    public Mat inverseMatrix;
    public PointF[] templateCornersInImage;  // 模板在原图中的四角坐标（用于显示检测框）
    public MatchResult cachedMatchResult;    // 缓存的匹配结果，避免重复匹配

    public CorrectionData(Mat correctedMat, Mat inverseMatrix, PointF[] templateCornersInImage) {
        this.correctedMat = correctedMat;
        this.inverseMatrix = inverseMatrix;
        this.templateCornersInImage = templateCornersInImage;
        this.cachedMatchResult = null;
    }

    public CorrectionData(Mat correctedMat, Mat inverseMatrix, PointF[] templateCornersInImage, MatchResult cachedMatchResult) {
        this.correctedMat = correctedMat;
        this.inverseMatrix = inverseMatrix;
        this.templateCornersInImage = templateCornersInImage;
        this.cachedMatchResult = cachedMatchResult;
    }

    public void release() {
        if (correctedMat != null) correctedMat.release();
        if (inverseMatrix != null) inverseMatrix.release();
        if (cachedMatchResult != null) cachedMatchResult.release();
    }
}
