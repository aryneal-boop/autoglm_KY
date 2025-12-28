package com.connect_screen.mirror;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEventHidden;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.Surface;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.graphics.Rect;
import android.util.Log;
import android.graphics.Matrix;
import android.view.View;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;

import com.connect_screen.mirror.shizuku.ServiceUtils;

import dev.rikka.tools.refine.Refine;

public class TouchscreenActivity extends AppCompatActivity {
    private Surface surface;
    private int displayId;
    private DirectDrawView drawView;
    private Bitmap bufferBitmap;
    private final Handler handler = new Handler(Looper.getMainLooper());
    
    // 添加计数器和时间记录变量
    private int updateCounter = 0;
    private long startTime = 0;
    private boolean finished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 锁定屏幕方向为竖屏
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        
        // 使用 FrameLayout 替代 LinearLayout
        android.widget.FrameLayout rootLayout = new android.widget.FrameLayout(this);
        rootLayout.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        
        // 创建DirectDrawView
        drawView = new DirectDrawView(this);
        android.widget.FrameLayout.LayoutParams drawViewParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        drawView.setId(View.generateViewId());
        
        // 将绘图视图添加到根布局
        rootLayout.addView(drawView, drawViewParams);

        // 创建退出文本
        TextView exitText = new TextView(this);
        exitText.setId(View.generateViewId());
        exitText.setPadding(32, 32, 32, 32);
        exitText.setText("退\n出");
        exitText.setBackgroundColor(0x80888888);  // 半透明灰色背景
        exitText.setTextColor(0xFFFFFFFF);  // 白色文字
        
        // 创建返回文本
        TextView backText = new TextView(this);
        backText.setId(View.generateViewId());
        backText.setPadding(32, 32, 32, 32);
        backText.setText("返\n回");
        backText.setBackgroundColor(0x80888888);  // 半透明灰色背景
        backText.setTextColor(0xFFFFFFFF);  // 白色文字
        
        // 设置返回按钮的布局参数，添加底部边距
        android.widget.LinearLayout.LayoutParams backParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        backParams.setMargins(0, 0, 0, 16); // 添加16dp的底部边距
        
        // 创建一个垂直线性布局来容纳两个按钮
        android.widget.LinearLayout buttonLayout = new android.widget.LinearLayout(this);
        buttonLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.FrameLayout.LayoutParams buttonLayoutParams = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        buttonLayoutParams.gravity = Gravity.CENTER | android.view.Gravity.END;
        buttonLayoutParams.setMargins(0, 16, 16, 0);
        
        // 将两个按钮添加到线性布局中，为返回按钮应用边距
        buttonLayout.addView(backText, backParams);
        buttonLayout.addView(exitText);
        
        // 将按钮布局添加到根布局
        rootLayout.addView(buttonLayout, buttonLayoutParams);

        // 设置内容视图为代码创建的布局
        setContentView(rootLayout);

        // 移除默认的 ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        
        // 隐藏底部导航栏手势提示和顶部状态栏
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11 及以上使用 WindowInsetsController
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                // 同时隐藏导航栏和状态栏
                controller.hide(android.view.WindowInsets.Type.navigationBars() | 
                                android.view.WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Android 10 及以下使用 SystemUiVisibility
            // 注意：现有代码已经包含了隐藏状态栏的标志 (SYSTEM_UI_FLAG_FULLSCREEN)
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        if (getIntent().hasExtra("display")) {
            displayId = getIntent().getIntExtra("display", -1);
            // 获取Display对象
            drawView.setDisplayId(displayId);
        }

        if (getIntent().hasExtra("surface")) {
            // the surface of the display
            surface = getIntent().getParcelableExtra("surface");
        }

        // 添加长按退出功能
        exitText.setOnClickListener(v -> {
            finished = true;
        });
        
        // 添加返回按钮功能
        backText.setOnClickListener(v -> {
            // 发送返回键事件
            long now = SystemClock.uptimeMillis();
            injectKeyEvent(KeyEvent.KEYCODE_BACK, now, now, KeyEvent.ACTION_DOWN);
            injectKeyEvent(KeyEvent.KEYCODE_BACK, now, now + 10, KeyEvent.ACTION_UP);
        });
        
        // 添加双击检测
        backText.setOnTouchListener(new View.OnTouchListener() {
            private static final int DOUBLE_CLICK_TIME = 300; // 双击时间间隔（毫秒）
            private long lastClickTime = 0;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    long clickTime = System.currentTimeMillis();
                    if (clickTime - lastClickTime < DOUBLE_CLICK_TIME) {
                        TouchpadActivity.launchSingleApp(TouchscreenActivity.this, displayId);
                        return true;
                    }
                    lastClickTime = clickTime;
                }
                return false; // 返回false以允许onClick事件继续传递
            }
        });

        captureFromSurface();

        // 添加对系统返回手势的处理
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        // 发送返回键事件到被镜像的显示器
                        long now = SystemClock.uptimeMillis();
                        injectKeyEvent(KeyEvent.KEYCODE_BACK, now, now, KeyEvent.ACTION_DOWN);
                        injectKeyEvent(KeyEvent.KEYCODE_BACK, now, now + 10, KeyEvent.ACTION_UP);
                    }
            );
        }
    }

    public void updateImage(Bitmap bitmap) {
        if (bitmap != null) {
            drawView.setBitmap(bitmap);
            drawView.invalidate();

            // 更新计数器并记录时间
            if (updateCounter == 0) {
                startTime = System.currentTimeMillis();
            }

            updateCounter++;

            if (updateCounter >= 60) {
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;
                Log.d("TouchscreenActivity", "60次图像更新耗时: " + duration + "毫秒，平均每次: " + (duration / 60.0) + "毫秒");
                updateCounter = 0;
            }
        }
    }

    public void captureFromSurface() {
        if (surface == null || !surface.isValid()) {
            Log.e("TouchscreenActivity", "Surface 无效或为空");
            return;
        }

        if (finished) {
            finish();
            return;
        }

        // 初始化或复用 bufferBitmap
        if (bufferBitmap == null || bufferBitmap.isRecycled()) {
            DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(displayId);
            bufferBitmap = Bitmap.createBitmap(
                    Math.max(display.getWidth(), display.getHeight()),
                    Math.min(display.getWidth(), display.getHeight()),
                    Bitmap.Config.ARGB_8888
            );
        }

        // 使用 PixelCopy API 从 Surface 复制到位图
        PixelCopy.request(
                surface,
                new Rect(0, 0, bufferBitmap.getWidth(), bufferBitmap.getHeight()),
                bufferBitmap,
                copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) {
                        // 交换bitmap
                        Bitmap temp = drawView.bitmap;
                        updateImage(bufferBitmap);
                        bufferBitmap = temp;

                        // 安排下一次捕获
                        handler.postDelayed(this::captureFromSurface, 30);
                    } else {
                        if (copyResult == 3) {
                            handler.postDelayed(this::captureFromSurface, 1000);
                        } else {
                            Log.e("TouchscreenActivity", "PixelCopy 失败: " + copyResult);
                        }
                    }
                },
                handler
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理两个 bitmap
        if (drawView.bitmap != null && !drawView.bitmap.isRecycled()) {
            drawView.bitmap.recycle();
            drawView.bitmap = null;
        }
        if (bufferBitmap != null && !bufferBitmap.isRecycled()) {
            bufferBitmap.recycle();
            bufferBitmap = null;
        }
    }

    // 添加注入按键事件的方法
    private void injectKeyEvent(int keyCode, long downTime, long eventTime, int action) {
        IInputManager inputManager = ServiceUtils.getInputManager();
        TouchpadActivity.setFocus(inputManager, displayId);
        KeyEvent event = new KeyEvent(downTime, eventTime, action, keyCode, 0);
        KeyEventHidden eventHidden = Refine.unsafeCast(event);
        eventHidden.setDisplayId(displayId);
        inputManager.injectInputEvent(event, 0);
    }

    @Override
    public void onBackPressed() {
        // 覆盖返回按钮行为，发送返回键事件到被镜像的显示器
        long now = SystemClock.uptimeMillis();
        injectKeyEvent(KeyEvent.KEYCODE_BACK, now, now, KeyEvent.ACTION_DOWN);
        injectKeyEvent(KeyEvent.KEYCODE_BACK, now, now + 10, KeyEvent.ACTION_UP);
    }

    // 添加直接绘制 Bitmap 的自定义 View
    public static class DirectDrawView extends View {
        private final IInputManager inputManager;
        private Bitmap bitmap;
        private int displayId;
        private static final String TAG = "DirectDrawView";
        // 保存上次使用的矩阵
        private Matrix transformMatrix = new Matrix();
        // 用于逆变换的矩阵
        private Matrix inverseMatrix = new Matrix();

        public DirectDrawView(android.content.Context context) {
            super(context);
            setBackgroundColor(android.graphics.Color.BLACK);
            inputManager = ServiceUtils.getInputManager();
        }

        public void setDisplayId(int displayId) {
            this.displayId = displayId;
        }

        public void setBitmap(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        private void injectMotionEvent(MotionEvent motionEvent) {
            TouchpadActivity.setFocus(inputManager, displayId);
            MotionEventHidden motionEventHidden = Refine.unsafeCast(motionEvent);
            motionEventHidden.setDisplayId(displayId);
            inputManager.injectInputEvent(motionEvent, 0);
        }

        private int getDisplayRotation() {
            DisplayManager displayManager = (DisplayManager) getContext().getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(displayId);
            return display.getRotation();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null || bitmap.isRecycled()) {
                return false;
            }

            // 创建一个新的事件用于注入
            MotionEvent transformedEvent = MotionEvent.obtain(event);
            
            // 获取触摸点数量
            int pointerCount = event.getPointerCount();
            
            // 创建用于转换坐标的数组
            float[] points = new float[pointerCount * 2];
            
            // 提取所有触摸点的坐标
            for (int i = 0; i < pointerCount; i++) {
                points[i * 2] = event.getX(i);
                points[i * 2 + 1] = event.getY(i);
            }
            
            Log.d("TouchscreenActivity", "映射前的点: [" + points[0] + ", " + points[1] + "]");
            
            // 应用逆矩阵转换坐标
            inverseMatrix.mapPoints(points);
            
            // 获取目标显示屏的旋转角度
            int rotation = getDisplayRotation();
            
            // 获取bitmap的宽高
            int bitmapWidth = bitmap.getWidth();
            int bitmapHeight = bitmap.getHeight();
            
            // 根据旋转角度调整坐标
            for (int i = 0; i < pointerCount; i++) {
                float x = points[i * 2];
                float y = points[i * 2 + 1];
                
                switch (rotation) {
                    case Surface.ROTATION_90:
                        // 顺时针旋转90度
                        points[i * 2] = y;
                        points[i * 2 + 1] = bitmapWidth - x;
                        break;
                    case Surface.ROTATION_180:
                        // 顺时针旋转180度
                        points[i * 2] = bitmapWidth - x;
                        points[i * 2 + 1] = bitmapHeight - y;
                        break;
                    case Surface.ROTATION_270:
                        // 顺时针旋转270度
                        points[i * 2] = bitmapHeight - y;
                        points[i * 2 + 1] = x;
                        break;
                    case Surface.ROTATION_0:
                    default:
                        // 不需要额外旋转
                        break;
                }
                
                // 限制坐标在有效范围内
                // 根据旋转情况确定宽度和高度的限制
                int maxWidth, maxHeight;
                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    maxWidth = bitmapHeight;
                    maxHeight = bitmapWidth;
                } else {
                    maxWidth = bitmapWidth;
                    maxHeight = bitmapHeight;
                }
                
                // 检查是否超出边界太多（超出50%），如果是则忽略此次触摸事件
                if (points[i * 2] < -maxWidth * 0.5 || points[i * 2] > maxWidth * 1.5 ||
                    points[i * 2 + 1] < -maxHeight * 0.5 || points[i * 2 + 1] > maxHeight * 1.5) {
                    Log.d("TouchscreenActivity", "触摸点超出边界太多，忽略此次事件");
                    return true;
                }
                
                // 限制X坐标在0到最大宽度之间
                points[i * 2] = Math.max(0, Math.min(points[i * 2], maxWidth - 1));
                // 限制Y坐标在0到最大高度之间
                points[i * 2 + 1] = Math.max(0, Math.min(points[i * 2 + 1], maxHeight - 1));
            }
            
            Log.d("TouchscreenActivity", "映射后的点: [" + points[0] + ", " + points[1] + "], 旋转角度: " + rotation);
            
            // 使用转换后的坐标创建新的MotionEvent
            MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[pointerCount];
            MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[pointerCount];
            
            for (int i = 0; i < pointerCount; i++) {
                pointerCoords[i] = new MotionEvent.PointerCoords();
                pointerCoords[i].x = points[i * 2];
                pointerCoords[i].y = points[i * 2 + 1];
                pointerCoords[i].pressure = event.getPressure(i);
                pointerCoords[i].size = event.getSize(i);
                
                pointerProperties[i] = new MotionEvent.PointerProperties();
                pointerProperties[i].id = event.getPointerId(i);
                pointerProperties[i].toolType = event.getToolType(i);
            }
            
            // 创建新的MotionEvent
            MotionEvent newEvent = MotionEvent.obtain(
                    event.getDownTime(),
                    event.getEventTime(),
                    event.getAction(),
                    pointerCount,
                    pointerProperties,
                    pointerCoords,
                    event.getMetaState(),
                    event.getButtonState(),
                    event.getXPrecision(),
                    event.getYPrecision(),
                    event.getDeviceId(),
                    event.getEdgeFlags(),
                    event.getSource(),
                    event.getFlags()
            );
            
            // 注入转换后的事件
            injectMotionEvent(newEvent);
            
            // 回收事件对象
            newEvent.recycle();
            transformedEvent.recycle();
            
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (bitmap != null && !bitmap.isRecycled()) {
                // 获取View和bitmap的尺寸
                int viewWidth = getWidth();
                int viewHeight = getHeight();
                int bitmapWidth = bitmap.getWidth();
                int bitmapHeight = bitmap.getHeight();

                // 计算缩放比例，以适应View的大小
                float scale;
                float dx = 0, dy = 0;

                if (bitmapWidth * viewHeight > viewWidth * bitmapHeight) {
                    // 如果bitmap比View更宽，按宽度缩放
                    scale = (float) viewWidth / bitmapWidth;
                    dy = (viewHeight - bitmapHeight * scale) * 0.5f;
                } else {
                    // 如果bitmap比View更高，按高度缩放
                    scale = (float) viewHeight / bitmapHeight;
                    dx = (viewWidth - bitmapWidth * scale) * 0.5f;
                }

                // 设置矩阵来缩放和居中bitmap
                transformMatrix.reset();
                transformMatrix.setScale(scale, scale);
                transformMatrix.postTranslate(dx, dy);
                
                // 计算逆矩阵用于坐标转换
                transformMatrix.invert(inverseMatrix);

                // 使用矩阵绘制bitmap
                canvas.drawBitmap(bitmap, transformMatrix, null);
            }
        }
    }
} 