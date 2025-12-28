package com.example.pingmutest.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Binder
import android.util.Log

object MiuiPermissionUtils {

    private const val TAG = "MiuiPermissionUtils"

    private fun getSystemProperty(key: String): String? {
        return try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java)
            get.invoke(null, key) as String
        } catch (_: Throwable) {
            Log.e(TAG, "getSystemProperty failed: key=$key")
            null
        }
    }

    fun isMiui(): Boolean {
        val name = getSystemProperty("ro.miui.ui.version.name")
        return !name.isNullOrBlank()
    }

    fun canBackgroundStartActivity(context: Context): Boolean {
        if (!isMiui()) return true

        val appOps = context.getSystemService(AppOpsManager::class.java)
        val uid = context.applicationInfo.uid
        val pkg = context.packageName

        return checkMiuiOpAllowed(appOps, 10021, uid, pkg) && checkMiuiOpAllowed(appOps, 10020, uid, pkg)
    }

    private fun checkMiuiOpAllowed(appOps: AppOpsManager, op: Int, uid: Int, pkg: String): Boolean {
        return try {
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = m.invoke(appOps, op, uid, pkg) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            Log.e(TAG, "checkMiuiOpAllowed failed: op=$op uid=$uid pkg=$pkg")
            true
        }
    }

    fun canBackgroundStartActivityByCallingUid(context: Context): Boolean {
        if (!isMiui()) return true

        val appOps = context.getSystemService(AppOpsManager::class.java)
        val callingUid = Binder.getCallingUid()
        val pkg = context.packageName

        return checkMiuiOpAllowed(appOps, 10021, callingUid, pkg) && checkMiuiOpAllowed(appOps, 10020, callingUid, pkg)
    }
}
