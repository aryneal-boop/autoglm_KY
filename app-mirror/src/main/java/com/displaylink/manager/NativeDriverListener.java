package com.displaylink.manager;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.ProjectionMode;
import com.displaylink.manager.display.MonitorInfo;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.DisplaylinkState;
import com.connect_screen.mirror.job.ProjectViaDisplaylink;
import com.connect_screen.mirror.job.VirtualDisplayArgs;

public class NativeDriverListener {
    private final String usbDeviceName;

    public NativeDriverListener(String usbDeviceName) {
        this.usbDeviceName = usbDeviceName;
    }

    public void onDisplayConnected(long encoderId) {
        Log.i("displaylink", "onDisplayConnected");
        new Handler(Looper.getMainLooper()).post(() -> {
            State.log("Display已连接, Encoder ID: " + encoderId);
        });
    }

    public void onDisplayDisconnected(long encoderId) {
        Log.i("displaylink", "onDisplayDisconnected");
        new Handler(Looper.getMainLooper()).post(() -> {
            DisplaylinkState displaylinkState = State.displaylinkState;
            if (displaylinkState == null) {
                State.log("Display已断开, 但找不到 USB 设备");
            } else {
                State.log("Display已断开, 关闭 usb 对应的状态");
                displaylinkState.encoderId = 0;
                displaylinkState.monitorInfo = null;
            }
        });
    }

    public void onError(int i) {
        Log.i("displaylink", "onError: " + i);
        new Handler(Looper.getMainLooper()).post(() -> {
            State.log("Displaylink 报告故障码：" + i);
        });
    }

    public void onFirmwareUpdateInfo(boolean z) {
        Log.i("displaylink", "onFirmwareUpdateInfo");
    }

    public void onUpdateMonitorInfo(long encoderId, MonitorInfo monitorInfo) {
        Log.i("displaylink", "onUpdateMonitorInfo");
        new Handler(Looper.getMainLooper()).post(() -> {
            State.log("onUpdateMonitorInfo: " + monitorInfo.toString());
            DisplaylinkState displaylinkState = State.displaylinkState;
            if (displaylinkState == null) {
                State.log("displaylinkState is null");
            } else {
                boolean wasNoMonitor = displaylinkState.monitorInfo == null;
                displaylinkState.encoderId = encoderId;
                displaylinkState.monitorInfo = monitorInfo;
                Context context = State.getContext();
                if (!State.isJobRunning() && wasNoMonitor && context != null) {
                    State.displaylinkState.virtualDisplayArgs = new VirtualDisplayArgs("DisplayLink", Pref.getDisplaylinkWidth(), Pref.getDisplaylinkHeight(), Pref.getDisplaylinkRefreshRate(), Pref.getSingleAppDpi(), Pref.getAutoRotate());
                    State.startNewJob(new ProjectViaDisplaylink(displaylinkState.device, displaylinkState.virtualDisplayArgs));
                }
            }
        });
    }
}
