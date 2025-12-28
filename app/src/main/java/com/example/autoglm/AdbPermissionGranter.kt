package com.example.autoglm

import android.content.Context

internal object AdbPermissionGranter {

    data class GrantResult(
        val ok: Boolean,
        val output: String,
    )

    fun autoGrantPermissionsViaAdb(context: Context): GrantResult {
        val ctx = context.applicationContext
        val adbExecPath = LocalAdb.resolveAdbExecPath(ctx)
        val pkg = ctx.packageName
        val nls = PermissionUtils.notificationListenerComponent(ctx).flattenToString()

        val console = StringBuilder()

        fun runShell(vararg shellArgs: String): LocalAdb.CommandResult {
            val cmd = LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("shell") + shellArgs.toList())
            val r = LocalAdb.runCommand(ctx, cmd, timeoutMs = 12_000L)
            console.append(">>> adb shell ")
                .append(shellArgs.joinToString(" "))
                .append("\n")
                .append(r.output)
                .append("\n\n")
            return r
        }

        val r1 = runShell("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        if (r1.exitCode != 0) return GrantResult(false, console.toString().trim())

        val r2 = runShell("dumpsys", "deviceidle", "whitelist", "+$pkg")
        if (r2.exitCode != 0) return GrantResult(false, console.toString().trim())

        val r3 = runShell("settings", "put", "secure", "enabled_notification_listeners", nls)
        if (r3.exitCode != 0) return GrantResult(false, console.toString().trim())

        return GrantResult(true, console.toString().trim())
    }

    fun applyAdbPowerUserPermissions(context: Context): GrantResult {
        val ctx = context.applicationContext
        val adbExecPath = LocalAdb.resolveAdbExecPath(ctx)
        val pkg = ctx.packageName

        val console = StringBuilder()

        fun runShell(vararg shellArgs: String): LocalAdb.CommandResult {
            val cmd = LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("shell") + shellArgs.toList())
            val r = LocalAdb.runCommand(ctx, cmd, timeoutMs = 12_000L)
            console.append(">>> adb shell ")
                .append(shellArgs.joinToString(" "))
                .append("\n")
                .append(r.output)
                .append("\n\n")
            return r
        }

        val r1 = runShell("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        if (r1.exitCode != 0) return GrantResult(false, console.toString().trim())

        val r2 = runShell("dumpsys", "deviceidle", "whitelist", "+$pkg")
        if (r2.exitCode != 0) return GrantResult(false, console.toString().trim())

        return GrantResult(true, console.toString().trim())
    }
}
