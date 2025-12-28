package com.connect_screen.mirror.job;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Surface;


import com.connect_screen.mirror.FloatingButtonService;
import com.connect_screen.mirror.MirrorMainActivity;
import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;


public class ProjectViaMoonlight implements Job {
    private final int width;
    private final int height;
    private final int frameRate;
    private final int packetDuration;
    private final Surface surface;
    private final boolean shouldSendAudio;
    private boolean mediaProjectionRequested;

    public ProjectViaMoonlight(int width, int height, int frameRate, int packetDuration, Surface surface, boolean shouldSendAudio) {
        this.width = width;
        this.height = height;
        this.frameRate = frameRate;
        this.packetDuration = packetDuration;
        this.surface = surface;
        this.shouldSendAudio = shouldSendAudio;
    }

    @Override
    public void start() throws YieldException {
        if (!requestMediaProjectionPermission()) {
            return;
        }
        Context context = State.getContext();
        if (context == null) {
            return;
        }
        // 创建AudioRecord
        if (shouldSendAudio) {
            if (SunshineAudio.sendAudio(context, packetDuration)) {
                return;
            }
        } else {
            State.log("客户端请求不录音，而是直接使用手机喇叭播放音频");
        }
        boolean autoRotate = Pref.getAutoRotate();
        boolean autoScale = Pref.getAutoScale();
        boolean singleAppMode = Pref.getSingleAppMode();
        if (singleAppMode) {
            if (ShizukuUtils.hasPermission()) {
                int singleAppDpi = Pref.getSingleAppDpi();
                State.mirrorVirtualDisplay = CreateVirtualDisplay.createVirtualDisplay(new VirtualDisplayArgs("Moonlight",
                        width, height, frameRate, singleAppDpi, autoRotate), surface);
                String selectedAppPackage = Pref.getSelectedAppPackage();
                ServiceUtils.launchPackage(context, selectedAppPackage, State.mirrorVirtualDisplay.getDisplay().getDisplayId());
                InputRouting.bindAllExternalInputToDisplay(State.mirrorVirtualDisplay.getDisplay().getDisplayId());
                InputRouting.moveImeToExternal(State.mirrorVirtualDisplay.getDisplay().getDisplayId());
            } else {
                State.showErrorStatus("moonlight 单应用投屏需要 shizuku 权限");
            }
        } else if (autoRotate || autoScale) {
            SunshineMouse.autoRotateAndScaleForMoonlight = new AutoRotateAndScaleForMoonlight(new VirtualDisplayArgs("Moonlight",
                    width, height, frameRate, 160, false));
            SunshineMouse.autoRotateAndScaleForMoonlight.start(surface);
            CreateVirtualDisplay.powerOffScreen();
        } else {
            State.mirrorVirtualDisplay = State.getMediaProjection().createVirtualDisplay("Moonlight",
                    width,
                    height,
                    160,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    surface, null, null);
            State.setMediaProjection(null);
            FloatingButtonService.startForMirror();
            CreateVirtualDisplay.powerOffScreen();
        }
    }


    private boolean requestMediaProjectionPermission() throws YieldException {
        if (State.mirrorVirtualDisplay != null) {
            return true;
        }
        if (State.getMediaProjection() != null) {
            State.log("MediaProjection 已经存在，跳过重复请求");
            return true;
        }
        if (mediaProjectionRequested) {
            State.log("因为未授予投屏权限，跳过任务");
            return false;
        }
        mediaProjectionRequested = true;
        MirrorMainActivity mirrorMainActivity = State.getCurrentActivity();
        if (mirrorMainActivity == null) {
            return false;
        }
        mirrorMainActivity.startMediaProjectionService();
        throw new YieldException("等待用户投屏授权");
    }
}
