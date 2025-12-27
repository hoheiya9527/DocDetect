package com.example.docdetect;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 渲染锚点标记（十字准星样式）
 */
public class PointRenderer {
    private static final String TAG = "PointRenderer";

    // 顶点着色器
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "uniform float uPointSize;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    gl_PointSize = uPointSize;\n" +
                    "}";

    // 片段着色器 - 绘制圆形点
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "uniform vec4 uColor;\n" +
                    "void main() {\n" +
                    "    vec2 coord = gl_PointCoord - vec2(0.5);\n" +
                    "    float dist = length(coord);\n" +
                    "    if (dist > 0.5) discard;\n" +
                    // 边缘抗锯齿
                    "    float alpha = 1.0 - smoothstep(0.4, 0.5, dist);\n" +
                    "    gl_FragColor = vec4(uColor.rgb, uColor.a * alpha);\n" +
                    "}";

    private int program;
    private int positionHandle;
    private int mvpMatrixHandle;
    private int colorHandle;
    private int pointSizeHandle;

    private final float[] modelMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];

    public void createOnGlThread(Context context) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        colorHandle = GLES20.glGetUniformLocation(program, "uColor");
        pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize");
    }

    /**
     * 绘制锚点
     *
     * @param pose             锚点位姿（包含位置和旋转）
     * @param viewMatrix       视图矩阵
     * @param projectionMatrix 投影矩阵
     * @param color            RGBA颜色
     * @param pointSize        点大小（像素）
     */
    public void draw(float[] pose, float[] viewMatrix, float[] projectionMatrix,
                     float[] color, float pointSize) {
        GLES20.glUseProgram(program);

        // 设置点位置
        FloatBuffer vertexBuffer = ByteBuffer.allocateDirect(3 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(new float[]{pose[0], pose[1], pose[2]});
        vertexBuffer.position(0);

        // 计算MVP矩阵
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvpMatrix, 0);

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniform4fv(colorHandle, 1, color, 0);
        GLES20.glUniform1f(pointSizeHandle, pointSize);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
