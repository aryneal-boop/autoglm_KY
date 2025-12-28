package com.example.pingmutest.system

import android.content.Context
import android.provider.Settings
import android.util.Log

object AdbSecurityUtils {

    private const val TAG = "AdbSecurityUtils"

    fun isAdbEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, "adb_enabled", 0) == 1
        } catch (t: Throwable) {
            Log.e(TAG, "isAdbEnabled failed", t)
            false
        }
    }

    fun isUsbDebuggingSecurityEnabledOnMiui(context: Context): Boolean {
        if (!MiuiPermissionUtils.isMiui()) return true

        val resolver = context.contentResolver

        val candidates = arrayOf(
            "adb_secure",
            "adb_security",
            "adb_install_enabled",
        )

        var logged = false

        for (key in candidates) {
            try {
                val v = Settings.Global.getInt(resolver, key)
                return v == 1
            } catch (_: Throwable) {
                if (!logged) {
                    Log.w(TAG, "isUsbDebuggingSecurityEnabledOnMiui read failed: key=$key")
                    logged = true
                }
            }
        }

        return true
    }
}
