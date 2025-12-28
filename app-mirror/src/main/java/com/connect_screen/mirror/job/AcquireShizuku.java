package com.connect_screen.mirror.job;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.connect_screen.mirror.BuildConfig;
import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.shizuku.UserService;
import com.topjohnwu.superuser.Shell;

import rikka.shizuku.Shizuku;

public class AcquireShizuku implements Job {
    public static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;
    private boolean hasRequestedPermission;
    public boolean acquired = false;

    @Override
    public void start() throws YieldException {
        if (!ShizukuUtils.hasShizukuStarted()) {
            return;
        }
        if (ShizukuUtils.hasPermission()) {
            State.log("已经获得 Shizuku 权限");
            acquired = true;
            if (hasRequestedPermission) {
                fixRootShizuku();
                State.bindUserService();
            }
        } else {
            if (hasRequestedPermission) {
                State.log("获取 Shizuku 权限失败");
                return;
            }
            hasRequestedPermission = true;
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
            throw new YieldException("等待 Shizuku 权限");
        }
    }

    public static void fixRootShizuku() {
        if (ShizukuUtils.hasPermission() && Shizuku.getUid() == 0) {
            State.log("检测到 shizuku 是 root 启动的，尝试拿 root 权限，把 shizuku 重启为 adb 身份");
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    boolean success = Shell.getShell().newJob()
                            .add("/data/adb/magisk/busybox killall shizuku_server")
                            .add("su 2000")
                            .add("/data/local/tmp/shizuku_starter")
                            .exec()
                            .isSuccess();
                    Log.e("State", "kill shizuku " + success);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (success) {
                            State.log("Shizuku 已重启为 adb 身份，请退出重新进入屏易连");
                        } else {
                            State.log("Shizuku 重启失败");
                        }
                    });
                } catch (Throwable e) {
                    // ignore
                }
            }).start();
        }
    }
}
