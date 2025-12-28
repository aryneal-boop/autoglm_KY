package com.connect_screen.mirror;

import android.accessibilityservice.GestureDescription;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyEventHidden;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;


import com.connect_screen.mirror.job.StartTouchPad;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import dev.rikka.tools.refine.Refine;

public class TouchpadActivity extends AppCompatActivity {
    
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;
    private TextView touchpadArea;
    private ImageView cursorView;
    private int displayId;
    private static final String TAG = "TouchpadActivity";
    private float cursorX = 0;
    private float cursorY = 0;
    private WindowManager.LayoutParams cursorParams;
    private float halfWidth;
    private float halfHeight;
    private IInputManager inputManager;
    private GestureState gestureState = new GestureState();
    private boolean isCursorLocked = false;
    private Spinner modeSpinner;
    private static final int MODE_NORMAL = 0;
    private static final int MODE_CURSOR_LOCKED = 1;

    private static class GestureState {
        List<MotionEvent> allMotionEvents = new ArrayList<>();
        int lastReplayed = 0;
        boolean isSingleFinger;
        float initialTouchX = 0;
        float initialTouchY = 0;
    }

    private static class StrokePoint {
        float x;
        float y;
        long time;

        StrokePoint(float x, float y, long time) {
            this.x = x;
            this.y = y;
            this.time = time;
        }
    }

    public static boolean startTouchpad(Context context,int displayId, boolean dryRun) {
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q && !ShizukuUtils.hasPermission()) {
            return false;
        }
        if (displayId == Display.DEFAULT_DISPLAY) {
            return false;
        }
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(context)) {
            if (!dryRun) {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
                );
                context.startActivity(intent);
            }
            return false;
        }
        
        if (ShizukuUtils.hasShizukuStarted()) {
            if (!dryRun) {
                State.startNewJob(new StartTouchPad(displayId, context));
            }
            return true;
        }
        
        // 检查无障碍服务权限并尝试启动服务
        if (!TouchpadAccessibilityService.isAccessibilityServiceEnabled(context)) {
            if (!dryRun) {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                context.startActivity(intent);
            }
            return false;
        }
        
        if (!dryRun) {
            if(TouchpadAccessibilityService.getInstance() != null) {
                Intent touchpadIntent = new Intent(context, TouchpadActivity.class);
                touchpadIntent.putExtra("display_id", displayId);
                context.startActivity(touchpadIntent);
                return true;
            }
            // 启动无障碍服务
            Intent serviceIntent = new Intent(context, TouchpadAccessibilityService.class);
            context.startService(serviceIntent);

            // 延迟1秒后启动触控板
            new Handler().postDelayed(() -> {
                if (TouchpadAccessibilityService.getInstance() == null) {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    context.startActivity(intent);
                } else {
                    // 权限都具备且服务启动后启动触控板
                    Intent touchpadIntent = new Intent(context, TouchpadActivity.class);
                    touchpadIntent.putExtra("display_id", displayId);
                    context.startActivity(touchpadIntent);
                }
            }, 1000);
            
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 4. 最后设内容视图
        setContentView(R.layout.activity_touchpad);

        modeSpinner = findViewById(R.id.modeSpinner);
        // 设置触控板区域的帮助文案
        touchpadArea = findViewById(R.id.touchpad_area);
        updateHelp();

        // 获取目标显示器ID
        displayId = getIntent().getIntExtra("display_id", Display.DEFAULT_DISPLAY);
        
        if (ShizukuUtils.hasPermission()) {
            inputManager = ServiceUtils.getInputManager();
        }
        // 计算屏幕尺寸
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display targetDisplay = displayManager.getDisplay(displayId);
        if (targetDisplay == null) {
            finish();
            return;
        }

        // 计算屏幕边界（以屏幕中心为原点）
        halfWidth = targetDisplay.getWidth() / 2.0f;
        halfHeight = targetDisplay.getHeight() / 2.0f;

        // 显示光标
        showMouseCursor(targetDisplay);

        touchpadArea = findViewById(R.id.touchpad_area);
        
        // 替换触控板的触摸事件监听
        if (inputManager == null) {
            setupTouchListenerForAccessibility();
        } else {
            setupTouchListenerForInputManager();
        }
        
        // 修改暗色模式按钮点击事件
        ImageButton goDarkButton = findViewById(R.id.goDarkButton);
        goDarkButton.setOnClickListener(v -> toggleDarkMode());
        
        // 添加返回按钮的点击监听器
        ImageButton backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> {
            performBackGesture(inputManager, displayId);
        });

        // 添加Home按钮的点击监听器
        ImageButton homeButton = findViewById(R.id.homeButton);
        homeButton.setOnClickListener(v -> {
            launchSingleApp(this, displayId);
        });

        setupModeSpinner();

        // 添加退出按钮的点击监听器
        Button exitButton = findViewById(R.id.exitButton);
        exitButton.setOnClickListener(v -> finish());

        if (ShizukuUtils.hasPermission()) {
            setFocus(inputManager, displayId);
        }

        // 获取切换模式按钮并设置点击监听器
        Button switchModeButton = findViewById(R.id.switchModeButton);
        switchModeButton.setOnClickListener(v -> switchMode());
    }

    private void setupTouchListenerForAccessibility() {
        touchpadArea.setOnTouchListener((v, event) -> {
            // 计算偏移量并存储修改后的事件
            if (gestureState.allMotionEvents.isEmpty()) {
                gestureState.initialTouchX = event.getX();
                gestureState.initialTouchY = event.getY();
            }

            float relativeX = event.getX() - gestureState.initialTouchX;
            float relativeY = event.getY() - gestureState.initialTouchY;

            float absoluteX = cursorX + halfWidth + relativeX * 2;
            float absoluteY = cursorY + halfHeight + relativeY * 2;
            float offsetX = absoluteX - event.getX();
            float offsetY = absoluteY - event.getY();

            MotionEvent copiedEventWithOffset = obtainMotionEventWithOffset(event, offsetX, offsetY);
            gestureState.allMotionEvents.add(copiedEventWithOffset);

            // 处理手势结束
            if (event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL) {
                Log.d(TAG, "触摸事件结束, isSingleFinger: " + gestureState.isSingleFinger);
                if (!gestureState.isSingleFinger) {
                    boolean alwaysSingleFinger = true;
                    for (MotionEvent e : gestureState.allMotionEvents) {
                        if (e.getPointerCount() > 1) {
                            alwaysSingleFinger = false;
                        }
                    }
                    if (!isCursorLocked && alwaysSingleFinger && (Math.abs(relativeX) > 10 || Math.abs(relativeY) > 10)) {
                        // ignore
                    } else {
                        // 构建手势路径并通过无障碍服务执行
                        replayGestureViaAccessibility(gestureState.allMotionEvents, displayId);
                    }
                }
                gestureState.lastReplayed = 0;
                gestureState.isSingleFinger = false;
                gestureState.allMotionEvents.clear();
                return true;
            }

            if (!isCursorLocked) {
                // 在非锁定模式下，单指移动光标
                if (gestureState.isSingleFinger || (event.getPointerCount() == 1 && (gestureState.allMotionEvents.size() == 5 || Math.abs(relativeX) > 10 || Math.abs(relativeY) > 10))) {
                    if (gestureState.allMotionEvents.size() == 5 && Math.abs(relativeX) < 1 && Math.abs(relativeY) < 1) {
                        Log.d(TAG, "未检测到移动");
                        return true;
                    }
                    gestureState.isSingleFinger = true;
                    if (event.getPointerCount() == 1) {
                        updateCursorPosition(relativeX * 0.5f, relativeY * 0.5f);
                        gestureState.initialTouchX = event.getX();
                        gestureState.initialTouchY = event.getY();
                    }
                    return true;
                }
            }
            return true;
        });
    }

    private void setupTouchListenerForInputManager() {
        touchpadArea.setOnTouchListener((v, event) -> {
            // 计算偏移量并存储修改后的事件
            if (gestureState.allMotionEvents.isEmpty()) {
                gestureState.initialTouchX = event.getX();
                gestureState.initialTouchY = event.getY();
            }

            float relativeX = event.getX() - gestureState.initialTouchX;
            float relativeY = event.getY() - gestureState.initialTouchY;

            float absoluteX = cursorX + halfWidth + relativeX * 2;
            float absoluteY = cursorY + halfHeight + relativeY * 2;
            float offsetX = absoluteX - event.getX();
            float offsetY = absoluteY - event.getY();

            MotionEvent copiedEventWithOffset = obtainMotionEventWithOffset(event, offsetX, offsetY);
            gestureState.allMotionEvents.add(copiedEventWithOffset);

            // 处理手势结束
            if (event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL) {
                Log.d(TAG, "触摸事件结束, isSingleFinger: " + gestureState.isSingleFinger);
                if (!gestureState.isSingleFinger) {
                    if (!isCursorLocked && gestureState.lastReplayed == 0 && (Math.abs(relativeX) > 10 || Math.abs(relativeY) > 10)) {
                        // ignore
                    } else {
                        replayBufferedEvents();
                    }
                }
                gestureState.lastReplayed = 0;
                gestureState.isSingleFinger = false;
                gestureState.allMotionEvents.clear();
                return true;
            }

            if (!isCursorLocked && gestureState.lastReplayed == 0) {
                // 在非锁定模式下，单指移动光标
                if (gestureState.isSingleFinger || (event.getPointerCount() == 1 && (gestureState.allMotionEvents.size() == 5 || Math.abs(relativeX) > 10 || Math.abs(relativeY) > 10))) {
                    if (gestureState.allMotionEvents.size() == 5 && Math.abs(relativeX) < 1 && Math.abs(relativeY) < 1) {
                        Log.d(TAG, "未检测到移动");
                        return true;
                    }
                    gestureState.isSingleFinger = true;
                    if (event.getPointerCount() == 1) {
                        updateCursorPosition(relativeX * 0.5f, relativeY * 0.5f);
                        gestureState.initialTouchX = event.getX();
                        gestureState.initialTouchY = event.getY();
                    }
                    return true;
                }

                if (event.getPointerCount() == 1) {
                    // buffer it
                    return true;
                }
            }

            replayBufferedEvents();
            return true;
        });
    }

    public static void launchSingleApp(Context context, int displayId) {
        String lastPackageName = Pref.getSelectedAppPackage();
        if (lastPackageName == null || lastPackageName.isEmpty()) {
            return;
        }
        ServiceUtils.launchPackage(context, lastPackageName, displayId);
    }

    private void updateHelp() {
        String singleFingerAction;
        int selectedMode = modeSpinner.getSelectedItemPosition();
        
        switch (selectedMode) {
            case MODE_CURSOR_LOCKED:
                singleFingerAction = "光标不被移动，执行单指手势";
                break;
            default:
                singleFingerAction = "移动光标";
                break;
        }
        
        touchpadArea.setText(
            "请在此区域触控\n" +
            "• 单指滑动 - " + singleFingerAction + "\n" +
            "• 单指轻触 - 点击\n" +
            "• 多指手势 - 执行多指手势\n" +
            "• 返回按钮 - 在目标屏幕上按返回\n" +
            "• 主页按钮 - 再次打开列表中选择的应用\n" +
            "• 月亮按钮 - 纯黑色屏幕省电\n" +
            "• 如果蓝牙手柄等控制不了显示器，请尝试用月亮按钮让主屏熄屏，从而把输入焦点转移到显示器"
        );
    }

    private void replayBufferedEvents() {
        if (inputManager == null || gestureState.allMotionEvents.isEmpty()) {
            return;
        }

        setFocus(inputManager, displayId);

        // 直重放已经计算好偏移量的事件
        for (int i = gestureState.lastReplayed; i < gestureState.allMotionEvents.size(); i++) {
            MotionEvent event = gestureState.allMotionEvents.get(i);
            
            Log.d(TAG, String.format(
                "重放事件 #%d [显示ID=%d]: 坐标=(%.2f, %.2f), 动作=%d", 
                i, displayId, event.getX(), event.getY(), event.getAction()));
            
            MotionEventHidden eventHidden = Refine.unsafeCast(event);
            eventHidden.setDisplayId(displayId);
            inputManager.injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC);
        }
        
        gestureState.lastReplayed = gestureState.allMotionEvents.size();
    }

    private static void injectKeyEvent(IInputManager inputManager, int displayId, int action, int keyCode, int repeat, int metaState, int injectMode) {
        setFocus(inputManager, displayId);
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        KeyEventHidden eventHidden = Refine.unsafeCast(event);
        eventHidden.setDisplayId(displayId);
        inputManager.injectInputEvent(event, injectMode);
    }

    private void showMouseCursor(Display targetDisplay) {
        cursorParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        
        cursorParams.x = 0;
        cursorParams.y = 0;
        
        cursorView = new ImageView(this);
        cursorView.setImageResource(R.drawable.mouse_cursor);
        
        Context displayContext = createDisplayContext(targetDisplay);
        WindowManager windowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        
        try {
            windowManager.addView(cursorView, cursorParams);
        } catch (Exception e) {
            Toast.makeText(this, "显示鼠标光标失败", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "显示鼠标光标失败: " + e.getMessage());
        }
    }

    // 添加更新光标位的方法
    private void updateCursorPosition(float deltaX, float deltaY) {
        cursorX += deltaX * 1.5f;
        cursorY += deltaY * 1.5f;
        
        // 检查并记录是否超出边界
        if (cursorX < -halfWidth || cursorX > halfWidth || 
            cursorY < -halfHeight || cursorY > halfHeight) {
            Log.w(TAG, "光标位置超出边界 - 原始位置: (" + cursorX + ", " + cursorY + ")");
        }
        
        // 确保光标不会超出屏幕边界
        cursorX = Math.max(-halfWidth, Math.min(cursorX, halfWidth));
        cursorY = Math.max(-halfHeight, Math.min(cursorY, halfHeight));
        
        if (cursorView != null && cursorParams != null) {
            cursorParams.x = (int) cursorX;
            cursorParams.y = (int) cursorY;
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            try {
                windowManager.updateViewLayout(cursorView, cursorParams);
            } catch (Exception e) {
                Log.e(TAG, "更新光标位置失败: " + e.getMessage());
            }
        }
    }

    // 添加执行返回手势的方法
    public static void performBackGesture(IInputManager inputManager, int displayId) {
        if (inputManager != null) {
            injectKeyEvent(inputManager, displayId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0, 0, INJECT_INPUT_EVENT_MODE_ASYNC);
            injectKeyEvent(inputManager, displayId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0, 0, INJECT_INPUT_EVENT_MODE_ASYNC);
            return;
        }
        TouchpadAccessibilityService accessibilityService = TouchpadAccessibilityService.getInstance();
        if (accessibilityService != null) {
            accessibilityService.performBackGesture(displayId);
        }
    }

    private void toggleDarkMode() {
        Intent intent = new Intent(this, PureBlackActivity.class);
        ActivityOptions options = ActivityOptions.makeBasic();
        startActivity(intent, options.toBundle());
    }

    public static void setFocus(IInputManager inputManager, int displayId) {
        try {
            if (inputManager != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceUtils.getActivityTaskManager().focusTopTask(displayId);
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    List<ActivityTaskManager.RootTaskInfo> taskInfos = ServiceUtils.getActivityTaskManager().getAllRootTaskInfosOnDisplay(displayId);
                    for (ActivityTaskManager.RootTaskInfo taskInfo : taskInfos) {
                        ServiceUtils.getActivityTaskManager().setFocusedRootTask(taskInfo.taskId);
                        break;
                    }
                } else {
                    List<Object> stackInfos = ServiceUtils.getActivityTaskManager().getAllStackInfosOnDisplay(displayId);
                    if (!stackInfos.isEmpty()) {
                        Object stackInfo = stackInfos.get(0);
                        Field stackIdField = stackInfo.getClass().getDeclaredField("stackId");
                        stackIdField.setAccessible(true);
                        int stackId = stackIdField.getInt(stackInfo);
                        ServiceUtils.getActivityTaskManager().setFocusedStack(stackId);
                    }
                }
            } else {
                TouchpadAccessibilityService accessibilityService = TouchpadAccessibilityService.getInstance();
                if (accessibilityService != null) {
                    accessibilityService.setFocus(displayId);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "设置焦点失败", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 移除光标视图
        if (cursorView != null && cursorView.getWindowToken() != null) {
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            windowManager.removeView(cursorView);
        }
    }

    // 添加新的辅助方法
    private MotionEvent obtainMotionEventWithOffset(MotionEvent source, float offsetX, float offsetY) {
        int pointerCount = source.getPointerCount();
        
        // 准备所有触点的属性和坐标数组
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[pointerCount];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        
        // 复制每个触点的信
        for (int i = 0; i < pointerCount; i++) {
            // 复制触点属性
            properties[i] = new MotionEvent.PointerProperties();
            source.getPointerProperties(i, properties[i]);
            
            // 复制并修改触点坐标
            coords[i] = new MotionEvent.PointerCoords();
            source.getPointerCoords(i, coords[i]);
            coords[i].x += offsetX;
            coords[i].y += offsetY;
        }
        
        // 创建新的MotionEvent
        int DEFAULT_DEVICE_ID = 0;
        return MotionEvent.obtain(
            source.getDownTime(),
            source.getEventTime(),
            source.getAction(),
            pointerCount,
            properties,
            coords,
            source.getMetaState(),
            source.getButtonState(),
            source.getXPrecision(),
            source.getYPrecision(),
            DEFAULT_DEVICE_ID,
            source.getEdgeFlags(),
            source.getSource(),
            source.getFlags()
        );
    }

    private void setupModeSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[]{"普通模式", "锁定光标"}
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);
        
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case MODE_NORMAL:
                        isCursorLocked = false;
                        // 在普通模式下显示光标
                        if (cursorView != null) {
                            cursorView.setVisibility(View.VISIBLE);
                        }
                        break;
                    case MODE_CURSOR_LOCKED:
                        isCursorLocked = true;
                        // 在锁定光标模式下隐藏光标
                        if (cursorView != null) {
                            cursorView.setVisibility(View.GONE);
                        }
                        break;
                }
                updateHelp();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何处理
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 隐藏光标
        if (cursorView != null) {
            cursorView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 显示光标
        if (cursorView != null) {
            cursorView.setVisibility(View.VISIBLE);
        }
    }

    // 添加切换模式的方法
    private void switchMode() {
        int currentMode = modeSpinner.getSelectedItemPosition();
        int nextMode = (currentMode + 1) % modeSpinner.getCount();
        modeSpinner.setSelection(nextMode);
    }

    // 添加新方法：通过无障碍服务回放手势
    public static void replayGestureViaAccessibility(List<MotionEvent> allMotionEvents, int displayId) {
        TouchpadAccessibilityService service = TouchpadAccessibilityService.getInstance();
        if (service == null || allMotionEvents.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        // 为每个触点记录起点和终点
        SparseArray<StrokePoint> startPoints = new SparseArray<>();
        SparseArray<StrokePoint> endPoints = new SparseArray<>();
        long baseTime = allMotionEvents.get(0).getDownTime();

        // 遍历所有事件来记录每个触点的起点和终点
        for (MotionEvent event : allMotionEvents) {
            int action = event.getActionMasked();
            int pointerIndex = event.getActionIndex();
            
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    int pointerId = event.getPointerId(pointerIndex);
                    startPoints.put(pointerId, new StrokePoint(
                        Math.max(0, event.getX(pointerIndex)),
                        Math.max(0, event.getY(pointerIndex)),
                        event.getEventTime() - baseTime
                    ));
                    break;
                    
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    pointerId = event.getPointerId(pointerIndex);
                    endPoints.put(pointerId, new StrokePoint(
                        Math.max(0, event.getX(pointerIndex)),
                        Math.max(0, event.getY(pointerIndex)),
                        event.getEventTime() - baseTime
                    ));
                    break;
                    
                case MotionEvent.ACTION_MOVE:
                    // 更新所有活动触点的当前位置
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        pointerId = event.getPointerId(i);
                        endPoints.put(pointerId, new StrokePoint(
                            Math.max(0, event.getX(i)),
                            Math.max(0, event.getY(i)),
                            event.getEventTime() - baseTime
                        ));
                    }
                    break;
            }
        }

        // 创建手势构建器
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.setDisplayId(displayId);

        // 为每个触点添加一个笔画
        for (int i = 0; i < startPoints.size(); i++) {
            int pointerId = startPoints.keyAt(i);
            StrokePoint start = startPoints.get(pointerId);
            StrokePoint end = endPoints.get(pointerId);

            if (end == null) {
                // 如果没有结束点，使用最后一个已知位置
                end = start;
            }

            Path strokePath = new Path();
            strokePath.moveTo(start.x, start.y);
            strokePath.lineTo(end.x, end.y);

            long duration = end.time - start.time;
            if (duration <= 0) duration = 100; // 确保持续时间至少为100ms

            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(
                strokePath,
                start.time,  // 开始时间
                duration,    // 持续时间
                false       // 不继续
            ));
        }

        // 检查是否有至少一个笔画
        if (startPoints.size() > 0) {
            // 执行手势
            GestureDescription gestureDescription = gestureBuilder.build();
            service.setFocus(displayId);
            service.dispatchGesture(gestureDescription, null, null);
        }
    }
}