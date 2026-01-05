package com.example.autoglm

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 权限/系统开关检查工具（面向引导与自检）。
 *
 * **用途**
 * - 汇总检查当前 App 在“助手常驻/悬浮窗交互”场景下的关键系统开关：
 *   - 悬浮窗权限（SYSTEM_ALERT_WINDOW）
 *   - 通知监听权限（Notification Listener Service）
 *   - 电池优化白名单（device idle whitelist，避免后台被杀）
 *
 * **典型用法**
 * - `val st = PermissionUtils.checkAll(context); if (!st.allGranted) ...`
 *
 * **引用路径（常见）**
 * - `MainActivity`：引导/校准流程中展示权限状态与跳转入口。
 * - `AdbPermissionGranter`：在 ADB/Shizuku 通道可用时尝试自动授予部分权限。
 *
 * **使用注意事项**
 * - 电池白名单检测通过 `adb shell dumpsys deviceidle whitelist`：依赖 ADB 可用，失败会返回 false。
 */
internal object PermissionUtils {

    data class Status(
        val systemAlertWindow: Boolean,
        val notificationListenerEnabled: Boolean,
        val deviceIdleWhitelisted: Boolean,
    ) {
        val allGranted: Boolean get() = systemAlertWindow && notificationListenerEnabled && deviceIdleWhitelisted
    }

    fun checkAll(context: Context): Status {
        val appCtx = context.applicationContext

        val overlayOk = try {
            Settings.canDrawOverlays(appCtx)
        } catch (_: Exception) {
            false
        }

        val nlsOk = try {
            val cn = notificationListenerComponent(appCtx)
            isNotificationListenerEnabled(appCtx, cn)
        } catch (_: Exception) {
            false
        }

        val idleOk = try {
            val adbExecPath = LocalAdb.resolveAdbExecPath(appCtx)
            val out = LocalAdb.runCommand(
                appCtx,
                LocalAdb.buildAdbCommand(appCtx, adbExecPath, listOf("shell", "dumpsys", "deviceidle", "whitelist")),
                timeoutMs = 8_000L
            ).output
            out.lines().any { it.trim() == appCtx.packageName || it.trim().endsWith(":" + appCtx.packageName) }
        } catch (_: Exception) {
            false
        }

        return Status(
            systemAlertWindow = overlayOk,
            notificationListenerEnabled = nlsOk,
            deviceIdleWhitelisted = idleOk,
        )
    }

    fun notificationListenerComponent(context: Context): ComponentName {
        return ComponentName(context, AutoglmNotificationListenerService::class.java)
    }

    private fun isNotificationListenerEnabled(context: Context, component: ComponentName): Boolean {
        val flat = component.flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { it.equals(flat, ignoreCase = false) }
    }
}
