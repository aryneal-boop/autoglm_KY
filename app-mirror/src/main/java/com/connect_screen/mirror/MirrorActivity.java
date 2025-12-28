package com.connect_screen.mirror;

import static android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.opengl.EGLConfig;
import android.opengl.EGLSurface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.MotionEventHidden;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.appcompat.app.AppCompatActivity;

import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import android.view.Window;
import android.view.WindowManager;

import com.connect_screen.mirror.job.CreateVirtualDisplay;
import com.connect_screen.mirror.job.ExitAll;
import com.connect_screen.mirror.job.ExternalTextureRenderer;
import com.connect_screen.mirror.job.InputRouting;
import com.connect_screen.mirror.job.LandscapeAutoScaler;
import com.connect_screen.mirror.job.VirtualDisplayArgs;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import dev.rikka.tools.refine.Refine;

public class MirrorActivity extends AppCompatActivity {

    private static MirrorActivity instance;
    private SurfaceView surfaceView;
    private int portraitInputTextureId = -1;
    private SurfaceTexture portraitInputSurfaceTexture = null;
    private Surface portraitInputSurface = null;
    private Handler renderHandler;
    private HandlerThread renderThread;

    private EGLDisplay eglDisplay;
    private android.opengl.EGLSurface eglOutputSurface;
    private android.opengl.EGLContext eglContext;
    private android.opengl.EGLConfig eglConfig;
    private PortraitRenderer portraitRenderer;

    private int landscapeInputTextureId = -1;
    private SurfaceTexture landscapeInputSurfaceTexture = null;
    private Surface landscapeInputSurface = null;
    private LandscapeRenderer landscapeRenderer;

    private boolean autoRotate;
    private boolean autoScale;
    private OrientationChangeCallback orientationChangeCallback;
    private boolean singleAppMode;
    private int singleAppDpi;

    public static void stopVirtualDisplay() {
        if (State.mirrorVirtualDisplay == null) {
            return;
        }
        State.mirrorVirtualDisplay.release();
        State.mirrorVirtualDisplay = null;
    }

    public static MirrorActivity getInstance() {
        return instance;
    }

    private class OrientationChangeCallback implements DisplayManager.DisplayListener {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                boolean isLandscape = SunshineService.instance.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                Surface targetSurface = isLandscape ? landscapeInputSurface : portraitInputSurface;

                if (State.mirrorVirtualDisplay != null) {
                    if (isLandscape) {
                        State.mirrorVirtualDisplay.resize(surfaceView.getWidth(), surfaceView.getHeight(), 160);
                    } else {
                        State.mirrorVirtualDisplay.resize(surfaceView.getHeight(), surfaceView.getWidth(), 160);
                    }
                    State.mirrorVirtualDisplay.setSurface(targetSurface);
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;

        // 读取设置
        autoRotate = Pref.getAutoRotate();
        autoScale = Pref.getAutoScale();

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(layoutParams);
        }

        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        surfaceView = new SurfaceView(this);
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int targetDisplayId = this.getDisplay().getDisplayId();
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int i) {

                }

                @Override
                public void onDisplayRemoved(int i) {
                    if (i == targetDisplayId) {
                        MirrorActivity.this.finish();
                    }
                }

                @Override
                public void onDisplayChanged(int i) {

                }
            }, null);
        }
        // 只在autoRotate为true时注册屏幕方向变化监听
        if (autoRotate) {
            orientationChangeCallback = new OrientationChangeCallback();
            displayManager.registerDisplayListener(orientationChangeCallback, renderHandler);
        }

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // 获取手机主屏的完整显示信息
                DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                DisplayMetrics displayMetrics = new DisplayMetrics();
                Display display = displayManager.getDisplay(0);
                display.getRealMetrics(displayMetrics); // 使用getRealMetrics获取包含系统装饰(如状态栏、导航栏)的真实尺寸
                int defaultDisplayWidth = displayMetrics.widthPixels; // 获取实际屏幕宽度
                int defaultDisplayHeight = displayMetrics.heightPixels; // 获取实际屏幕高度
                if (defaultDisplayHeight < defaultDisplayWidth) {
                    // 如果主屏幕是横屏,交换宽高
                    int temp = defaultDisplayWidth;
                    defaultDisplayWidth = defaultDisplayHeight;
                    defaultDisplayHeight = temp;
                }

                // 记录屏幕尺寸信息到日志
                Log.d("MirrorActivity", "主屏幕实际尺寸: " + defaultDisplayWidth + " x " + defaultDisplayHeight);
                Log.d("MirrorActivity", "外接显示器尺寸: " + surfaceView.getWidth() + " x " + surfaceView.getHeight());

                // 创建专用的渲染线程
                renderThread = new HandlerThread("MirrorActivityRenderThread");
                renderThread.start();
                renderHandler = new Handler(renderThread.getLooper());

                // 在渲染线程中初始化OpenGL
                renderHandler.post(() -> {
                    // 初始化 EGL
                    eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                    if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                        throw new RuntimeException("无法获取 EGL 显示连接");
                    }

                    int[] version = new int[2];
                    if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                        throw new RuntimeException("无法初始化 EGL");
                    }

                    // 配置 EGL
                    int[] configAttribs = {
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_RED_SIZE, 8,
                            EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_ALPHA_SIZE, 8,
                            EGL14.EGL_NONE
                    };

                    EGLConfig[] configs = new EGLConfig[1];
                    int[] numConfigs = new int[1];
                    EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);
                    eglConfig = configs[0];

                    // 创建 EGL 上下文
                    int[] contextAttribs = {
                            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                            EGL14.EGL_NONE
                    };
                    eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

                    // 创建 EGL Surface
                    eglOutputSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig,
                            surfaceView.getHolder().getSurface(), null, 0);

                    // 设置当前 EGL 环境
                    EGL14.eglMakeCurrent(eglDisplay, eglOutputSurface, eglOutputSurface, eglContext);
                    GLES20.glViewport(0, 0, surfaceView.getWidth(), surfaceView.getHeight());

                    // 一次性创建两个输入纹理
                    int[] textures = new int[2];
                    GLES20.glGenTextures(2, textures, 0);
                    portraitInputTextureId = textures[0];
                    landscapeInputTextureId = textures[1];

                    portraitRenderer = new PortraitRenderer(portraitInputTextureId, eglDisplay, eglOutputSurface);
                    landscapeRenderer = new LandscapeRenderer(landscapeInputTextureId, eglDisplay, eglOutputSurface,
                            surfaceView.getWidth(), surfaceView.getHeight(), autoScale);

                    GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, portraitInputTextureId);

                    // 设置纹理参数
                    GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                    GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                    GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, landscapeInputTextureId);
                    GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                    GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                    // 创建SurfaceTexture和Surface
                    portraitInputSurfaceTexture = new SurfaceTexture(portraitInputTextureId);
                    portraitInputSurfaceTexture.setDefaultBufferSize(surfaceView.getHeight(), surfaceView.getWidth());
                    portraitInputSurfaceTexture.setOnFrameAvailableListener(portraitRenderer);
                    portraitInputSurface = new Surface(portraitInputSurfaceTexture);

                    landscapeInputSurfaceTexture = new SurfaceTexture(landscapeInputTextureId);
                    landscapeInputSurfaceTexture.setDefaultBufferSize(surfaceView.getWidth(), surfaceView.getHeight());
                    landscapeInputSurfaceTexture.setOnFrameAvailableListener(landscapeRenderer);
                    landscapeInputSurface = new Surface(landscapeInputSurfaceTexture);

                    // 使用inputSurface创建虚拟显示器
                    if (State.mirrorVirtualDisplay == null && State.getMediaProjection() != null) {
                        stopVirtualDisplay();
                        boolean isLandscape = SunshineService.instance.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                        if (!autoRotate) {
                            isLandscape = true;
                        }
                        Surface targetSurface = isLandscape ? landscapeInputSurface : portraitInputSurface;
                        State.mirrorVirtualDisplay = State.getMediaProjection().createVirtualDisplay("Mirror",
                                isLandscape ? surfaceView.getWidth() : surfaceView.getHeight(),
                                isLandscape ? surfaceView.getHeight() : surfaceView.getWidth(), 160,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                                targetSurface, null, renderHandler);
                        State.setMediaProjection(null);
                        FloatingButtonService.startForMirror();
                        CreateVirtualDisplay.changeAspectRatio(surfaceView.getWidth(), surfaceView.getHeight());

                        IInputManager inputManager = ServiceUtils.getInputManager();
                        // 修改触摸监听器
                        surfaceView.setOnTouchListener((v, event) -> {
                            Display targetDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                            if (targetDisplay == null)
                                return true;

                            // 获取原始坐标
                            float x = event.getX();
                            float y = event.getY();

                            // 计算相对坐标
                            float relativeX = x / v.getWidth();
                            float relativeY = y / v.getHeight();

                            // 获取目标显示器的旋转角度
                            int rotation = targetDisplay.getRotation();
                            float targetWidth = targetDisplay.getWidth();
                            float targetHeight = targetDisplay.getHeight();
                            Log.d("MirrorActivity", "rotation: " + rotation);

                            // 根据旋转角度调整坐标映射
                            float mappedX, mappedY;
                            switch (rotation) {
                                case Surface.ROTATION_270:
                                    mappedX = (1 - relativeX) * targetWidth;
                                    mappedY = (1 - relativeY) * targetHeight;
                                    break;
                                case Surface.ROTATION_180:
                                    mappedX = relativeY * targetWidth;
                                    mappedY = (1 - relativeX) * targetHeight;
                                    break;
                                case Surface.ROTATION_90:
                                    mappedX = relativeX * targetWidth;
                                    mappedY = relativeY * targetHeight;
                                    break;
                                default: // Surface.ROTATION_0
                                    mappedX = (1 - relativeY) * targetWidth;
                                    mappedY = relativeX * targetHeight;
                                    break;
                            }
                            // 设置整后的坐标
                            event.setLocation(mappedX, mappedY);

                            MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
                            motionEventHidden.setDisplayId(Display.DEFAULT_DISPLAY);
                            TouchpadActivity.setFocus(inputManager, 0);
                            inputManager.injectInputEvent(event, 0);
                            return true;
                        });
                        CreateVirtualDisplay.powerOffScreen();
                    } else if (State.mirrorVirtualDisplay != null) {
                        boolean isLandscape = SunshineService.instance.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                        Surface targetSurface = isLandscape ? landscapeInputSurface : portraitInputSurface;

                        State.mirrorVirtualDisplay.setSurface(targetSurface);
                    }

                });
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // 可以在这里处理尺寸变化
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                renderHandler.post(() -> {
                    // 清理OpenGL资源
                    if (portraitRenderer != null) {
                        portraitRenderer.release();
                    }
                    if (portraitInputTextureId != -1) {
                        int[] textures = new int[] { portraitInputTextureId };
                        GLES20.glDeleteTextures(1, textures, 0);
                        portraitInputTextureId = -1;
                    }

                    if (landscapeRenderer != null) {
                        landscapeRenderer.release();
                    }
                    if (landscapeInputTextureId != -1) {
                        int[] textures = new int[] { landscapeInputTextureId };
                        GLES20.glDeleteTextures(1, textures, 0);
                        landscapeInputTextureId = -1;
                    }

                    // 原有的清理代码
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                                EGL14.EGL_NO_CONTEXT);
                        if (eglOutputSurface != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglDestroySurface(eglDisplay, eglOutputSurface);
                        }
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            EGL14.eglDestroyContext(eglDisplay, eglContext);
                        }
                        EGL14.eglTerminate(eglDisplay);
                    }
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                    eglContext = EGL14.EGL_NO_CONTEXT;
                    eglOutputSurface = EGL14.EGL_NO_SURFACE;
                });

                // 清理线程
                if (renderThread != null) {
                    renderThread.quitSafely();
                    renderThread = null;
                }
                if (portraitInputSurface != null) {
                    portraitInputSurface.release();
                    portraitInputSurface = null;
                }
                if (portraitInputSurfaceTexture != null) {
                    portraitInputSurfaceTexture.release();
                    portraitInputSurfaceTexture = null;
                }
                if (landscapeInputSurface != null) {
                    landscapeInputSurface.release();
                    landscapeInputSurface = null;
                }
                if (landscapeInputSurfaceTexture != null) {
                    landscapeInputSurfaceTexture.release();
                    landscapeInputSurfaceTexture = null;
                }
            }
        });

        setContentView(surfaceView);
        State.log("MirrorActivity 启动，autoRotate=" + autoRotate + ", autoScale=" + autoScale);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CreateVirtualDisplay.restoreAspectRatio();
        CreateVirtualDisplay.powerOnScreen();
        instance = null;
        if (orientationChangeCallback != null) {
            DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            displayManager.unregisterDisplayListener(orientationChangeCallback);
        }
        State.log("MirrorActivity destroyed");
    }

    private static class PortraitRenderer implements SurfaceTexture.OnFrameAvailableListener {

        protected float[] portraitMvpMatrix;
        protected final ExternalTextureRenderer externalTextureRenderer;
        protected final EGLDisplay eglDisplay;
        protected final EGLSurface eglOutputSurface;

        public PortraitRenderer(int inputTextureId, EGLDisplay eglDisplay, EGLSurface eglOutputSurface) {
            this.externalTextureRenderer = new ExternalTextureRenderer(inputTextureId);
            this.eglDisplay = eglDisplay;
            this.eglOutputSurface = eglOutputSurface;
            portraitMvpMatrix = new float[16];
            android.opengl.Matrix.setIdentityM(portraitMvpMatrix, 0);
            android.opengl.Matrix.scaleM(portraitMvpMatrix, 0, 1, 1, 1.0f);
            android.opengl.Matrix.setRotateM(portraitMvpMatrix, 0, 90, 0, 0, 1.0f);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            surfaceTexture.updateTexImage();
            externalTextureRenderer.renderFrame(portraitMvpMatrix);
            EGL14.eglSwapBuffers(eglDisplay, eglOutputSurface);
        }

        // 添加清理方法
        public void release() {
            externalTextureRenderer.release();
        }
    }

    private static class LandscapeRenderer implements SurfaceTexture.OnFrameAvailableListener {
        private final EGLDisplay eglDisplay;
        private final EGLSurface eglOutputSurface;
        private final boolean autoScale;
        private final ExternalTextureRenderer externalTextureRenderer;
        private final LandscapeAutoScaler landscapeAutoScaler;
        private int[] fbo = new int[1];
        private int[] tempTexture = new int[1];

        public LandscapeRenderer(int inputTextureId, EGLDisplay eglDisplay, EGLSurface eglOutputSurface, int width,
                int height, boolean autoScale) {
            this.externalTextureRenderer = new ExternalTextureRenderer(inputTextureId);
            this.eglDisplay = eglDisplay;
            this.eglOutputSurface = eglOutputSurface;
            this.autoScale = autoScale;

            // 创建临时纹理
            GLES20.glGenTextures(1, tempTexture, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tempTexture[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, // 修改高度为完整高度
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // 创建并设置FBO
            GLES20.glGenFramebuffers(1, fbo, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, tempTexture[0], 0);

            // 检查FBO状态
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.e("MirrorActivity", "FBO创建失败，状态: " + status);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            this.landscapeAutoScaler = new LandscapeAutoScaler(externalTextureRenderer, width, height, fbo[0]);
        }

        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            surfaceTexture.updateTexImage();
            externalTextureRenderer.renderFrame(landscapeAutoScaler.landscapeMvpMatrix);
            EGL14.eglSwapBuffers(eglDisplay, eglOutputSurface);
            if (autoScale) {
                landscapeAutoScaler.onFrame();
            }
        }

        public void release() {
            this.externalTextureRenderer.release();
            // 清理额外的资源
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            GLES20.glDeleteTextures(1, tempTexture, 0);
        }
    }
}