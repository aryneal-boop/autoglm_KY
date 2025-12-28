package com.connect_screen.mirror;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.connect_screen.mirror.job.Job;
import com.connect_screen.mirror.job.YieldException;
import com.connect_screen.mirror.shizuku.IUserService;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.shizuku.SurfaceControl;
import com.connect_screen.mirror.shizuku.UserService;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class State {
    // 弱引用保存当前的 MainActivity 实例
    private static WeakReference<MirrorMainActivity> currentActivity = new WeakReference<>(null);
    public static final MutableLiveData<MirrorUiState> uiState = new MutableLiveData<>(new MirrorUiState());
    public static FloatingButtonService floatingButtonService;
    public static String serverUuid;
    private static Job currentJob;
    public static List<String> logs = new ArrayList<>();
    public static DisplaylinkState displaylinkState = new DisplaylinkState();
    private static MediaProjection mediaProjection;
    public static MediaProjection mediaProjectionInUse;
    public static int lastSingleAppDisplay;
    public static String displaylinkDeviceName;
    public static VirtualDisplay mirrorVirtualDisplay;
    public static Activity isInPureBlackActivity = null;
    public static volatile IUserService userService;
    public static Set<String> discoveredConnectScreenClients = new HashSet<>();

    public static MirrorMainActivity getCurrentActivity() {
        if (currentActivity == null) {
            return null;
        }
        return currentActivity.get();
    }

    public static void setCurrentActivity(MirrorMainActivity activity) {
        currentActivity = new WeakReference<>(activity);
    }

    public static final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            State.log("user service connected");
            State.userService = IUserService.Stub.asInterface(binder);
            if (State.currentActivity != null && State.currentActivity.get() != null) {
                MirrorMainActivity context = State.currentActivity.get();
                context.runOnUiThread(() -> {
                    State.resumeJob();
                });
            }
            SharedPreferences preferences = Pref.getPreferences();
            if (preferences != null && preferences.getInt("AUTO_GRANT_PERMISSION", 0) != BuildConfig.VERSION_CODE) {
                preferences.edit().putInt("AUTO_GRANT_PERMISSION", BuildConfig.VERSION_CODE).apply();
                State.log("授予媒体投影权限和悬浮窗权限");
                try {
                    State.userService.executeCommand("appops set com.connect_screen.mirror PROJECT_MEDIA allow");
                    State.userService.executeCommand("appops set com.connect_screen.mirror SYSTEM_ALERT_WINDOW allow");
                } catch (Throwable e) {
                    // ignorepp
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            State.log("user service disconnected");
        }
    };

    public static Shizuku.UserServiceArgs userServiceArgs = new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, UserService.class.getName()))
            .daemon(true)
            .tag("temp7")
            .processNameSuffix("connect-screen")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE);

    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public static boolean isJobRunning() {
        return currentJob != null;
    }   

    public static void startNewJob(Job job) {
        if (currentJob != null) {
            if (currentActivity != null && currentActivity.get() != null) {
                State.log("当前任务 " + currentJob.getClass().getSimpleName() + " 正在进行中");
            }
            return;
        }
        currentJob = job;
        try {
            State.log("开始任务 " + job.getClass().getSimpleName());
            currentJob.start();
            State.log("任务 " + job.getClass().getSimpleName() + " 完成");
            currentJob = null;
        } catch (YieldException e) {
            State.log("任务 " + job.getClass().getSimpleName() + " 暂停, " + e.getMessage());
        } catch (RuntimeException e) {
            State.log("任务 " + job.getClass().getSimpleName() + " 启动失败");
            String stackTrace = android.util.Log.getStackTraceString(e);
            State.log("堆栈跟踪: " + stackTrace);
            currentJob = null;
        }
    }

    public static void resumeJob() {
        if (currentJob == null) {
            return;
        }
        try {
            State.log("恢复任务 " + currentJob.getClass().getSimpleName());
            currentJob.start();
            State.log("任务 " + currentJob.getClass().getSimpleName() + " 完成");
            currentJob = null;
        } catch (YieldException e) {
            State.log("任务 " + currentJob.getClass().getSimpleName() + " 暂停, " + e.getMessage());
        } catch (RuntimeException e) {
            State.log("任务 " + currentJob.getClass().getSimpleName() + " 恢复失败");
            String stackTrace = android.util.Log.getStackTraceString(e);
            State.log("堆栈跟踪: " + stackTrace);
            currentJob = null;
        }
    }

    public static void resumeJobLater(long delayMillis) {
        if (currentActivity.get() != null) {
            mainHandler.postDelayed(() -> {
                resumeJob();
            }, delayMillis);
        }
    }

    public static void log(String message) {
        logs.add(message);
        Log.i("ConnectScreen", message);
        if (currentActivity != null && currentActivity.get() != null) {
            IMainActivity  mainActivity = (IMainActivity) currentActivity.get();
            mainActivity.updateLogs();
        }
    }

    public static MediaProjection getMediaProjection() {
        return mediaProjection;
    }

    public static void setMediaProjection(MediaProjection newMediaProjection) {
        if (newMediaProjection == null) {
            Log.d("State", "MediaProjection used");
            mediaProjection = null;
        } else {
            Log.d("State", "MediaProjection acquired");
            mediaProjection = newMediaProjection;
            mediaProjectionInUse = newMediaProjection;
        }
    }

    public static int getDisplaylinkVirtualDisplayId() {
        if (displaylinkState.getVirtualDisplay() == null) {
            return -1;
        }
        return displaylinkState.getVirtualDisplay().getDisplay().getDisplayId();
    }

    public static int getMirrorVirtualDisplayId() {
        if (mirrorVirtualDisplay == null) {
            return -1;
        }
        return mirrorVirtualDisplay.getDisplay().getDisplayId();
    }

    public static void unbindUserService() {
        try {
            Shizuku.unbindUserService(State.userServiceArgs, userServiceConnection, false); // 解绑用户服务
            State.userService = null;
        } catch (Exception e) {
            // ignore
        }
    }

    public static void refreshMainActivity() {
        MirrorMainActivity mirrorMainActivity = currentActivity.get();
        if (mirrorMainActivity != null) {
            mirrorMainActivity.runOnUiThread(mirrorMainActivity::refresh);
        }
    }

    public static void showErrorStatus(String msg) {
        State.log(msg);
        MirrorUiState newUiState = new MirrorUiState();
        newUiState.errorStatusText = msg;
        State.uiState.setValue(newUiState);
    }

    public static Context getContext() {
        if (currentActivity != null && currentActivity.get() != null) {
            return currentActivity.get();
        }
        if (SunshineService.instance != null) {
            return SunshineService.instance;
        }
        return null;
    }

    public static void bindUserService() {
        Shizuku.peekUserService(State.userServiceArgs, State.userServiceConnection);
        Shizuku.bindUserService(State.userServiceArgs, State.userServiceConnection);
    }
}
