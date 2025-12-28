package com.connect_screen.mirror;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.input.IInputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.MotionEventHidden;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.KeyEvent;
import android.content.BroadcastReceiver;

import androidx.appcompat.app.AppCompatActivity;

import com.connect_screen.mirror.job.CreateVirtualDisplay;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.shizuku.SurfaceControl;

import dev.rikka.tools.refine.Refine;

import java.util.HashSet;
import java.util.Set;

public class PureBlackActivity extends AppCompatActivity {
    // 添加 Set 来存储外部设备 ID
    private final Set<Integer> externalDeviceIds = new HashSet<>();
    private final boolean hasShizukuPermission = ShizukuUtils.hasPermission();
    private IInputManager inputManager;

    // 将 ExitReceiver 修改为静态内部类
    public static class ExitReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            State.log("从熄屏中唤醒");
            CreateVirtualDisplay.powerOnScreen();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        State.isInPureBlackActivity = this;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 隐藏标题栏
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 设置全屏
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }

        // 支持刘海屏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams layoutParams = window.getAttributes();
            layoutParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            window.setAttributes(layoutParams);
        }

        // 设置状态栏和导航栏透明
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // 设置纯黑背景
        View view = new View(this);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setBackgroundColor(Color.BLACK);
        setContentView(view);

        // 添加鼠标捕获
        view.setOnGenericMotionListener((v, event) -> {
            TouchpadActivity.setFocus(inputManager, Display.DEFAULT_DISPLAY);
            view.requestFocus();
            view.requestFocusFromTouch();
            view.requestPointerCapture();
            return false;
        });
        
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);

        // 修改触摸监听器
        view.setOnTouchListener((v, event) -> {
            if (isExternalDevice(event)) {
                Display targetDisplay = displayManager.getDisplay(State.lastSingleAppDisplay);
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
                
                // 根据旋转角度调整坐标映射
                float mappedX, mappedY;
                switch (rotation) {
                    case Surface.ROTATION_270:
                        mappedX = (1 - relativeY) * targetWidth;
                        mappedY = relativeX * targetHeight;
                        break;
                    case Surface.ROTATION_180:
                        mappedX = (1 - relativeX) * targetWidth;
                        mappedY = (1 - relativeY) * targetHeight;
                        break;
                    case Surface.ROTATION_90:
                        mappedX = relativeY * targetWidth;
                        mappedY = (1 - relativeX) * targetHeight;
                        break;
                    default: // Surface.ROTATION_0
                        mappedX = relativeX * targetWidth;
                        mappedY = relativeY * targetHeight;
                        break;
                }
                // 设置整后的坐标
                event.setLocation(mappedX, mappedY);

                MotionEventHidden motionEventHidden = Refine.unsafeCast(event);
                motionEventHidden.setDisplayId(State.lastSingleAppDisplay);
                ServiceUtils.getInputManager().injectInputEvent(event, 0);
                return true;
            }
            finish();
            return true;
        });
       if (ShizukuUtils.hasPermission()) {
           inputManager = ServiceUtils.getInputManager();
           TouchpadActivity.setFocus(inputManager, State.lastSingleAppDisplay);
           TouchpadAccessibilityService.startServiceByShizuku(this);
           new Handler().postDelayed(() -> {
               TouchpadActivity.setFocus(inputManager, State.lastSingleAppDisplay);
           }, 500);
       } else if(TouchpadAccessibilityService.getInstance() != null) {
           TouchpadActivity.setFocus(null, State.lastSingleAppDisplay);
           new Handler().postDelayed(() -> {
               TouchpadActivity.setFocus(null, State.lastSingleAppDisplay);
           }, 500);
       } else if (TouchpadAccessibilityService.isAccessibilityServiceEnabled(this)) {
           Intent serviceIntent = new Intent(this, TouchpadAccessibilityService.class);
           this.startService(serviceIntent);
           new Handler().postDelayed(() -> {
               TouchpadActivity.setFocus(null, State.lastSingleAppDisplay);
           }, 500);
       }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        State.isInPureBlackActivity = null;
    }

    private boolean isExternalDevice(MotionEvent event) {
        if (!hasShizukuPermission) {
            return false;
        }
        int deviceId = event.getDeviceId();
        if (externalDeviceIds.contains(deviceId)) {
            return true;
        }
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null) {
            if (device.isExternal()) {
                externalDeviceIds.add(deviceId);
                return true;
            }
        }
        return false;
    }
}