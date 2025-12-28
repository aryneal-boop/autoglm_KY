package com.connect_screen.mirror.shizuku;

import android.content.pm.IPackageManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserHandleHidden;
import android.permission.IPermissionManager;
import android.util.Log;


import com.connect_screen.mirror.BuildConfig;
import com.connect_screen.mirror.State;

import dev.rikka.tools.refine.Refine;

public class PermissionManager {
    public static boolean grant(String permissionName) {
        try {
            return _grant(permissionName);
        } catch(Throwable e) {
            State.log("授权失败: " + e);
            return false;
        }
    }
    private static boolean _grant(String permissionName) {
        UserHandle userHandle = Process.myUserHandle();
        UserHandleHidden userHandleHidden = Refine.unsafeCast(userHandle);
        String packageName = BuildConfig.APPLICATION_ID;
        IPermissionManager permissionManager = ServiceUtils.getPermissionManager();
        if (permissionManager == null) {
            IPackageManager packageManager = ServiceUtils.getPackageManager();
            packageManager.grantRuntimePermission(packageName, permissionName, userHandleHidden.getIdentifier());
            Log.i("PermissionManager", "成功授予 " + permissionName + " 权限");
            return true;
        } else {
            try {
                permissionManager.grantRuntimePermission(
                        packageName,
                        permissionName,
                        "0", userHandleHidden.getIdentifier());
                Log.i("PermissionManager", "成功授予 " + permissionName + " 权限");
                return true;
            } catch (Throwable e) {
                try {
                    permissionManager.grantRuntimePermission(
                            packageName,
                            permissionName,
                            userHandleHidden.getIdentifier());
                    Log.i("PermissionManager", "成功授予 " + permissionName + " 权限");
                    return true;
                } catch (Throwable e2) {
                    State.log("授予权限失败: " + e2.getMessage());
                }
            }
        }
        return false;
    }
}
