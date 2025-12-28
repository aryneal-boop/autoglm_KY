package com.connect_screen.mirror.job;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.SunshineService;

public class MirrorDisplaylinkMonitor {

    private static boolean registered = false;
    private static final BroadcastReceiver usbDetachedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.d("MainActivity", "received action: " + intent.getAction());
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                MirrorDisplaylinkMonitor.onUsbDeviceDetached(device);
            }
        }
    };

    // 添加一个新的广播接收器来处理 USB 设备连接
    private static final BroadcastReceiver usbAttachedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.d("MainActivity", "received action: " + intent.getAction());
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                MirrorDisplaylinkMonitor.onUsbDeviceAttached(context, device);
            }
        }
    };

    public static void init(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        for (UsbDevice usbDevice : usbManager.getDeviceList().values()) {
            handleDisplaylink(context, usbDevice);
        }
        if (registered) {
            return;
        }
        registered = true;
        // 注册 USB 设备断开广播接收器
        IntentFilter detachedFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        context.registerReceiver(usbDetachedReceiver, detachedFilter, null, null, Context.RECEIVER_EXPORTED);

        // 注册 USB 设备连接广播接收器
        IntentFilter attachedFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        context.registerReceiver(usbAttachedReceiver, attachedFilter, null, null, Context.RECEIVER_EXPORTED);
    }
    public static void handleDisplaylink(Context context, UsbDevice device) {
        if (device == null) {
            return;
        }
        if (State.displaylinkDeviceName != null) {
            if (context != null) {
                UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                if (usbManager.getDeviceList().get(State.displaylinkDeviceName) == null) {
                    State.displaylinkState.destroy();
                    State.displaylinkDeviceName = null;
                    State.displaylinkState.device = null;
                }
            }
        }
        if (device.getVendorId() == 6121 && State.displaylinkDeviceName == null) {
            State.displaylinkDeviceName = device.getDeviceName();
            State.displaylinkState.device = device;
            State.log("发现 Displaylink 设备：" + device.getProductName());
        }
        // 处理 USB 设备连接的逻辑
        if (device.getDeviceName().equals(State.displaylinkDeviceName)) {
            State.displaylinkState.virtualDisplayArgs = new VirtualDisplayArgs("DisplayLink", Pref.getDisplaylinkWidth(), Pref.getDisplaylinkHeight(), Pref.getDisplaylinkRefreshRate(), Pref.getSingleAppDpi(), Pref.getAutoRotate());
            State.startNewJob(new ProjectViaDisplaylink(device, State.displaylinkState.virtualDisplayArgs));
        }
    }
    public static void onUsbDeviceAttached(Context context, UsbDevice device) {
        if (device == null) {
            return;
        }
        handleDisplaylink(context, device);
    }

    public static void onUsbDeviceDetached(UsbDevice device) {
        if (device != null && device.getDeviceName().equals(State.displaylinkDeviceName)) {
            State.log("Displaylink设备断开：" + device.getProductName());
            State.displaylinkState.destroy();
            State.displaylinkDeviceName = null;
            State.displaylinkState.device = null;
            CreateVirtualDisplay.powerOnScreen();
            InputRouting.moveImeToDefault();
        }
    }

}
