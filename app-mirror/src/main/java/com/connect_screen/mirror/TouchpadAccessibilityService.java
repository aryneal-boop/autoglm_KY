package com.connect_screen.mirror;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.connect_screen.mirror.shizuku.PermissionManager;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

public class TouchpadAccessibilityService extends AccessibilityService {
    private static TouchpadAccessibilityService instance;

    // 检查无障碍服务是否启用
    public static boolean isAccessibilityServiceEnabled(Context context) {
        String serviceName = context.getPackageName() + "/" + TouchpadAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabledServices != null) {
            return enabledServices.contains(serviceName);
        }
        return false;
    }

    public static void startServiceByShizuku(Context context) {
        if (TouchpadAccessibilityService.getInstance() != null) {
            return;
        }
        if (Pref.getDisableAccessibility()) {
            return;
        }
        if (PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
            String newService = BuildConfig.APPLICATION_ID + "/" + TouchpadAccessibilityService.class.getCanonicalName();
            String existingServices = getExistingServices(context, newService);

            String finalServices;
            // 如果已有配置，则追加新服务；否则直接使用新服务
            if (existingServices != null && !existingServices.isEmpty()) {
                finalServices = existingServices + ":" + newService;
            } else {
                finalServices = newService;
            }

            // 更新无障碍服务配置
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, finalServices);
            Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            Intent serviceIntent = new Intent(context, TouchpadAccessibilityService.class);
            context.startService(serviceIntent);
        }
    }

    public static void restartServiceByShizuku(Context context) {
        if (Pref.getDisableAccessibility()) {
            return;
        }
        if (PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
            String newService = BuildConfig.APPLICATION_ID + "/" + TouchpadAccessibilityService.class.getCanonicalName();
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, getExistingServices(context, newService));
            new Handler().postDelayed(() -> {
                startServiceByShizuku(context);
            }, 100);
        }
    }

    private static @NonNull String getExistingServices(Context context, String newService) {
        String existingServices = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(existingServices == null) {
            existingServices = "";
        }
        if (existingServices.contains(":" + newService)) {
            existingServices = existingServices.replace(":" + newService, "");
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existingServices);
        } else if (existingServices.contains(newService + ":")) {
            existingServices = existingServices.replace(newService + ":", "");
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existingServices);
        } else if (existingServices.contains(newService)) {
            existingServices = "";
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, existingServices);
        }
        return existingServices;
    }

    public static void disableAll(Context context) {
        if (PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "0");
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        State.log("TouchpadAccessibilityService 已连接");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        State.log("TouchpadAccessibilityService 已断开");
        return super.onUnbind(intent);
    }

    public static TouchpadAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && State.lastSingleAppDisplay > 0) {
            TouchpadActivity.launchSingleApp(getApplicationContext(), State.lastSingleAppDisplay);
            return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 处理辅助功能事件
    }

    @Override
    public void onInterrupt() {
        // 服务中断时的处理
    }

    // 修改 findFocusableNode 方法，返回所有可获取焦点的节点列表
    private List<AccessibilityNodeInfo> findFocusableNodes(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> results) {
        if (root == null) return results;

        if (root.isFocusable()) {
            results.add(root);
            if (results.size() >= 3) {
                return results;
            }
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                findFocusableNodes(child, results);
                if (results.size() >= 3) {
                    return results;
                }
            }
        }

        return results;
    }

    public boolean setFocus(int displayId) {
        List<AccessibilityWindowInfo> targetDisplayWindows = null;
        // 获取指定显示器上的窗口
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            targetDisplayWindows = getWindows();
        } else {
            SparseArray<List<AccessibilityWindowInfo>> windows = getWindowsOnAllDisplays();
            targetDisplayWindows = windows.get(displayId);
        }
        android.util.Log.d("AccessibilityService", "获取到窗口列表: " + (targetDisplayWindows != null ? targetDisplayWindows.size() : 0) + "个窗口");

        if (targetDisplayWindows != null && !targetDisplayWindows.isEmpty()) {
            // 找到目标显示器上最上层的窗口
            AccessibilityWindowInfo topWindow = null;
            int topLayer = -1;

            for (AccessibilityWindowInfo window : targetDisplayWindows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                if (window.getDisplayId() == displayId) {
                    if (window.getLayer() > topLayer) {
                        topLayer = window.getLayer();
                        topWindow = window;
                    }
                }
            }
            if (topWindow == null) {
                android.util.Log.d("AccessibilityService", "为你能找到最上层窗口");
                return false;
            }
            if (topWindow.isFocused()) {
                android.util.Log.d("AccessibilityService", "已经有焦点了，无需再设置焦点");
                return false;
            }
            android.util.Log.d("AccessibilityService", "找到最上层窗口，层级: " + topLayer);

            // 获取并聚焦窗口的根节点
            AccessibilityNodeInfo rootNode = topWindow.getRoot();
            android.util.Log.d("AccessibilityService", "获取根节点: " + (rootNode != null ? "成功" : "失败"));

            if (rootNode != null) {
                try {
                    // 获取所有可获取焦点的节点
                    List<AccessibilityNodeInfo> focusableNodes = findFocusableNodes(rootNode, new ArrayList<>());
                    android.util.Log.d("AccessibilityService", "找到 " + focusableNodes.size() + " 个可获取焦点的节点");

                    boolean focusSuccess = false;
                    // 遍历所有可获取焦点的节点，直到成功设置焦点
                    for (AccessibilityNodeInfo node : focusableNodes) {
                        try {
                            boolean focusResult = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                            android.util.Log.d("AccessibilityService", "尝试设置焦点: " + (focusResult ? "成功" : "失败"));
                            if (focusResult) {
                                focusSuccess = true;
                                break;
                            } else {
                                focusResult = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                                if (focusResult) {
                                    focusSuccess = true;
                                    break;
                                } else {
                                    // add to blacklist
                                }
                            }
                        } catch (Throwable e) {
                            try {
                                boolean focusResult = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                                if (focusResult) {
                                    focusSuccess = true;
                                    break;
                                }
                            } catch (Throwable e2) {
                                // ignore
                            }
                        }
                    }

                    try {
                        for (AccessibilityNodeInfo node : focusableNodes) {
                            node.recycle();
                        }
                    } catch (Throwable e) {
                        // ignore
                    }

                    if (focusSuccess) {
                        return true;
                    } else {
                        android.util.Log.d("AccessibilityService", "所有节点都无法获取焦点");
                    }
                } finally {
                    try {
                        rootNode.recycle();
                    } catch (Throwable e) {
                        // ignore
                    }
                    android.util.Log.d("AccessibilityService", "回收根节点");
                }
            }
        }
        return false;
    }

    // 修改 performBackGesture 方法中的相关部分
    public void performBackGesture(int displayId) {
        android.util.Log.d("AccessibilityService", "开始执行返回手势，显示器ID: " + displayId);
        if (setFocus(displayId)) {
            // 执行返回操作
            boolean backResult = performGlobalAction(GLOBAL_ACTION_BACK);
            android.util.Log.d("AccessibilityService", "执行返回操作: " + (backResult ? "成功" : "失败"));
        }
    }

    public static void grantPermissionByClick(Context context) {
        if (TouchpadAccessibilityService.isAccessibilityServiceEnabled(context)) {
            Intent serviceIntent = new Intent(context, TouchpadAccessibilityService.class);
            context.startService(serviceIntent);
        } else if (ShizukuUtils.hasPermission()) {
            TouchpadAccessibilityService.startServiceByShizuku(context);
        }
        new Thread(() -> {
            java.util.concurrent.atomic.AtomicBoolean granted = new java.util.concurrent.atomic.AtomicBoolean(false);
            for (int i = 0; i < 5 && !granted.get(); i++) {
                // 使用 Handler 在主线程执行
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (TouchpadAccessibilityService.instance != null) {
                        if (TouchpadAccessibilityService.instance.tryGrantPermissionByClick()) {
                            granted.set(true);
                        }
                    }
                });
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private boolean tryGrantPermissionByClick() {
        android.util.Log.d("AccessibilityService", "无障碍尝试确认");
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo window : windows) {
                android.util.Log.d("AccessibilityService", "正在检查窗口类型: " + window.getType());
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try {
                        // 查找包含特定文本的可点击节点
                        String[] targetTexts = {"立即开始", "允许", "确定", "开始", "确认", "共享屏幕","继续"};
                        for (String text : targetTexts) {
                            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                            for (AccessibilityNodeInfo node : nodes) {
                                if (node.isClickable()) {
                                    boolean clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    State.log("尝试点击按钮 '" + text + "': " + (clicked ? "成功" : "失败"));
                                    node.recycle();
                                    if (clicked) {
                                        return true;
                                    }
                                }
                                node.recycle();
                            }
                        }
                    } finally {
                        root.recycle();
                    }
                }
            }
        }
        return false;
    }
}