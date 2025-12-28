package com.connect_screen.mirror.job;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class ExternalTextureRenderer {

    // 更新顶点坐标为全屏四边形
    private final float[] vertexCoords = {
            -1.0f, -1.0f, 0.0f,  // 左下
            1.0f, -1.0f, 0.0f,  // 右下
            -1.0f,  1.0f, 0.0f,  // 左上
            1.0f,  1.0f, 0.0f   // 右上
    };

    // 添加纹理坐标
    private float[] textureCoords = {
            0.0f, 1.0f,  // 左下
            1.0f, 1.0f,  // 右下
            0.0f, 0.0f,  // 左上
            1.0f, 0.0f   // 右上
    };


    private final float[] textureCoordsFlipped = {
            0.0f, 0.0f,  // 左下
            1.0f, 0.0f,  // 右下
            0.0f, 1.0f,  // 左上
            1.0f, 1.0f   // 右上
    };

    // 简化的顶点着色器代码
    private final String vertexShaderCode =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec4 vPosition;\n" +
                    "attribute vec2 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVPMatrix * vPosition;\n" +
                    "  vTextureCoord = aTextureCoord;\n" +
                    "}";

    // 简化的片段着色器代码
    private final String fragmentShaderCode =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(uTexture, vTextureCoord);\n" +
                    "}";

    protected FloatBuffer textureBuffer;
    protected int textureCoordHandle;
    protected int positionHandle;
    protected int textureHandle;

    protected int mProgram;
    protected FloatBuffer vertexBuffer;

    protected int mvpMatrixHandle;
    protected final int inputTextureId;
    public ExternalTextureRenderer(int inputTextureId) {
        this(inputTextureId, false);
    }
    public ExternalTextureRenderer(int inputTextureId, boolean flip) {
        this.inputTextureId = inputTextureId;
        if (flip) {
            textureCoords = textureCoordsFlipped;
        }

        // 初始化顶点缓冲
        ByteBuffer bb = ByteBuffer.allocateDirect(vertexCoords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertexCoords);
        vertexBuffer.position(0);

        // 初始化纹理坐标缓冲
        ByteBuffer textureBB = ByteBuffer.allocateDirect(textureCoords.length * 4);
        textureBB.order(ByteOrder.nativeOrder());
        textureBuffer = textureBB.asFloatBuffer();
        textureBuffer.put(textureCoords);
        textureBuffer.position(0);

        // 创建着色器程序
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        // 获取着色器中的变量句柄
        positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition");
        textureCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        textureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture");
        mvpMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
    }

    public void renderFrame(float[] mvpMatrix) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);

        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        // 绑定顶点坐标
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        // 绑定纹理坐标
        GLES20.glEnableVertexAttribArray(textureCoordHandle);
        GLES20.glVertexAttribPointer(textureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer);

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, inputTextureId);
        GLES20.glUniform1i(textureHandle, 0);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 解绑
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(textureCoordHandle);
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    public void release() {
        // 清理OpenGL资源
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }
}
