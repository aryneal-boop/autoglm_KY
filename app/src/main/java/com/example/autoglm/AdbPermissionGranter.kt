package com.example.autoglm

import android.content.Context


/**
 * 通过 ADB/Shizuku“尽力自动授权”的工具。
 *
 * **用途**
 * - 在用户完成无线调试连接或授予 Shizuku 权限后，自动执行部分命令减少手动配置成本：
 *   - `appops set <pkg> SYSTEM_ALERT_WINDOW allow`：允许悬浮窗
 *   - `dumpsys deviceidle whitelist +<pkg>`：加入电池白名单
 *   - `settings put secure enabled_notification_listeners <component>`：启用通知监听（需要系统允许写 secure setting）
 *
 * **典型用法**
 * - ADB 模式：`AdbPermissionGranter.applyAdbPowerUserPermissions(context)`
 * - Shizuku 模式：`AdbPermissionGranter.applyShizukuPowerUserPermissions(context)`
 *
 * **引用路径（常见）**
 * - `AdbAutoConnectManager`：连接成功后触发 power user permissions（best-effort）。
 *
 * **使用注意事项**
 * - 不同 ROM/Android 版本对 `settings put secure` 限制不同，可能失败；失败时应提示用户手动授权。
 * - 该类会记录命令输出到 console 字符串，便于 UI 展示与排障。
 */
internal object AdbPermissionGranter {

    data class GrantResult(
        val ok: Boolean,
        val output: String,
    )

    private fun shellArgsToCmd(shellArgs: Array<out String>): String {
        return shellArgs.joinToString(" ") { s ->
            if (s.any { it.isWhitespace() || it == '"' || it == '\'' }) {
                "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else {
                s
            }
        }
    }

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

    fun applyShizukuPowerUserPermissions(context: Context): GrantResult {
        val ctx = context.applicationContext
        val pkg = ctx.packageName

        val console = StringBuilder()

        fun runShell(vararg shellArgs: String): ShizukuBridge.ExecResult {
            val cmd = shellArgsToCmd(shellArgs)
            val r = ShizukuBridge.execResult(cmd)
            console.append(">>> shizuku sh -c ")
                .append(cmd)
                .append("\n")
                .append(r.stdoutText())
                .append("\n")
                .append(r.stderrText())
                .append("\n\n")
            return r
        }

        val r1 = runShell("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        if (r1.exitCode != 0) return GrantResult(false, console.toString().trim())

        val r2 = runShell("dumpsys", "deviceidle", "whitelist", "+$pkg")
        if (r2.exitCode != 0) return GrantResult(false, console.toString().trim())

        return GrantResult(true, console.toString().trim())
    }

    fun autoGrantPermissionsViaShizuku(context: Context): GrantResult {
        val ctx = context.applicationContext
        val pkg = ctx.packageName
        val nls = PermissionUtils.notificationListenerComponent(ctx).flattenToString()

        val console = StringBuilder()

        fun runShell(vararg shellArgs: String): ShizukuBridge.ExecResult {
            val cmd = shellArgsToCmd(shellArgs)
            val r = ShizukuBridge.execResult(cmd)
            console.append(">>> shizuku sh -c ")
                .append(cmd)
                .append("\n")
                .append(r.stdoutText())
                .append("\n")
                .append(r.stderrText())
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
}
