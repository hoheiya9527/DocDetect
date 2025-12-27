package com.example.codedetect.renderer;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * AR渲染器
 * 负责在ARCore追踪的位置上渲染3D内容
 */
public class ARRenderer {
    
    private static final String TAG = "ARRenderer";
    
    // 顶点着色器
    private static final String VERTEX_SHADER =
            "uniform mat4 u_ModelViewProjection;\n" +
            "attribute vec4 a_Position;\n" +
            "attribute vec4 a_Color;\n" +
            "varying vec4 v_Color;\n" +
            "void main() {\n" +
            "   v_Color = a_Color;\n" +
            "   gl_Position = u_ModelViewProjection * a_Position;\n" +
            "}";
    
    // 片段着色器
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec4 v_Color;\n" +
            "void main() {\n" +
            "   gl_FragColor = v_Color;\n" +
            "}";
    
    private int shaderProgram;
    private int positionAttribute;
    private int colorAttribute;
    private int modelViewProjectionUniform;
    
    // 立方体顶点数据
    private FloatBuffer cubeVertices;
    private FloatBuffer cubeColors;
    
    private boolean isInitialized = false;
    
    /**
     * 初始化渲染器
     */
    public void initialize() {
        if (isInitialized) {
            return;
        }
        
        try {
            // 编译着色器
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            
            // 创建程序
            shaderProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(shaderProgram, vertexShader);
            GLES20.glAttachShader(shaderProgram, fragmentShader);
            GLES20.glLinkProgram(shaderProgram);
            
            // 获取属性位置
            positionAttribute = GLES20.glGetAttribLocation(shaderProgram, "a_Position");
            colorAttribute = GLES20.glGetAttribLocation(shaderProgram, "a_Color");
            modelViewProjectionUniform = GLES20.glGetUniformLocation(
                    shaderProgram, "u_ModelViewProjection");
            
            // 初始化立方体数据
            initializeCube();
            
            isInitialized = true;
            Log.i(TAG, "Renderer initialized");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize renderer", e);
        }
    }
    
    /**
     * 初始化立方体顶点数据
     */
    private void initializeCube() {
        // 立方体顶点（0.1m边长）
        float[] vertices = {
                // 前面
                -0.05f, -0.05f, 0.05f,
                0.05f, -0.05f, 0.05f,
                0.05f, 0.05f, 0.05f,
                -0.05f, 0.05f, 0.05f,
                // 后面
                -0.05f, -0.05f, -0.05f,
                0.05f, -0.05f, -0.05f,
                0.05f, 0.05f, -0.05f,
                -0.05f, 0.05f, -0.05f
        };
        
        // 颜色（半透明红色）
        float[] colors = new float[8 * 4];
        for (int i = 0; i < 8; i++) {
            colors[i * 4] = 1.0f;     // R
            colors[i * 4 + 1] = 0.0f; // G
            colors[i * 4 + 2] = 0.0f; // B
            colors[i * 4 + 3] = 0.7f; // A
        }
        
        // 创建缓冲区
        ByteBuffer vbb = ByteBuffer.allocateDirect(vertices.length * 4);
        vbb.order(ByteOrder.nativeOrder());
        cubeVertices = vbb.asFloatBuffer();
        cubeVertices.put(vertices);
        cubeVertices.position(0);
        
        ByteBuffer cbb = ByteBuffer.allocateDirect(colors.length * 4);
        cbb.order(ByteOrder.nativeOrder());
        cubeColors = cbb.asFloatBuffer();
        cubeColors.put(colors);
        cubeColors.position(0);
    }
    
    /**
     * 渲染AR内容
     */
    public void render(@NonNull Frame frame, @NonNull Anchor anchor) {
        if (!isInitialized) {
            Log.w(TAG, "Renderer not initialized");
            return;
        }
        
        Camera camera = frame.getCamera();
        
        // 获取投影矩阵
        float[] projectionMatrix = new float[16];
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
        
        // 获取视图矩阵
        float[] viewMatrix = new float[16];
        camera.getViewMatrix(viewMatrix, 0);
        
        // 获取Anchor的姿态
        Pose anchorPose = anchor.getPose();
        float[] modelMatrix = new float[16];
        anchorPose.toMatrix(modelMatrix, 0);
        
        // 计算MVP矩阵
        float[] viewProjectionMatrix = new float[16];
        Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
        
        float[] modelViewProjectionMatrix = new float[16];
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, 
                viewProjectionMatrix, 0, modelMatrix, 0);
        
        // 渲染立方体
        drawCube(modelViewProjectionMatrix);
    }
    
    /**
     * 绘制立方体
     */
    private void drawCube(float[] mvpMatrix) {
        GLES20.glUseProgram(shaderProgram);
        
        // 启用混合（半透明）
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        
        // 设置顶点属性
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glVertexAttribPointer(
                positionAttribute, 3, GLES20.GL_FLOAT, false, 0, cubeVertices);
        
        GLES20.glEnableVertexAttribArray(colorAttribute);
        GLES20.glVertexAttribPointer(
                colorAttribute, 4, GLES20.GL_FLOAT, false, 0, cubeColors);
        
        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(modelViewProjectionUniform, 1, false, mvpMatrix, 0);
        
        // 绘制（简化版，实际应使用索引缓冲）
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4); // 前面
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 4, 4); // 后面
        
        // 禁用属性
        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(colorAttribute);
        
        GLES20.glDisable(GLES20.GL_BLEND);
    }
    
    /**
     * 加载着色器
     */
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        
        // 检查编译状态
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compilation failed: " + error);
        }
        
        return shader;
    }
    
    /**
     * 释放资源
     */
    public void release() {
        if (shaderProgram != 0) {
            GLES20.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }
        isInitialized = false;
        Log.i(TAG, "Renderer released");
    }
}
