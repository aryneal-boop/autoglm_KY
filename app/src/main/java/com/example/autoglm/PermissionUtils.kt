package com.example.autoglm

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

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
