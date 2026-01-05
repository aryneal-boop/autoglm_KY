package com.example.autoglm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlin.concurrent.thread

/**
 * 跳转到系统“开发者选项/无线调试”相关设置页。
 *
 * **用途**
 * - 在无线调试配对失败或需要用户手动操作时，提供便捷跳转入口。
 *
 * **引用路径（常见）**
 * - `MainActivity`：引导用户开启无线调试。
 * - `PairingService`：通知栏提示用户打开设置页刷新端口/重新生成配对码。
 *
 * **使用注意事项**
 * - 不同 ROM 的开发者选项 Intent 可能不一致：这里采用 best-effort，失败则提示用户手动前往。
 */
fun startWirelessDebuggingSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            Toast.makeText(context, "跳转失败，请手动前往开发者选项", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }
}

/**
 * 通知栏配对/连接前台服务（无线调试）。
 *
 * **用途**
 * - 在后台执行无线调试配对闭环：
 *   - 扫描 `_adb-tls-pairing._tcp.` 获取配对端口（或使用用户/调用方传入端口）。
 *   - 在通知栏通过 `RemoteInput` 收集 6 位配对码。
 *   - 执行 `adb pair`，成功后继续扫描 `_adb-tls-connect._tcp.` 并执行 `adb connect`。
 *   - 最终用 `adb devices` 校验连接，并写入 [ConfigManager.setLastAdbEndpoint]。
 * - 使用前台服务保证流程不被系统后台限制中断。
 *
 * **引用路径（常见）**
 * - `MainActivity`：用户点击“开始配对/连接”时启动服务。
 * - `PairingInputReceiver`：接收通知栏输入并回传到本服务继续执行。
 *
 * **使用注意事项**
 * - 无线调试端口可能变化：本服务实现了扫描/重试/屏蔽失败端口等策略。
 * - ADB 命令为耗时操作：服务内部通过后台线程执行，UI 侧只应监听通知结果。
 */
class PairingService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(TAG, "PairingService.onStartCommand action=$action startId=$startId")
        when (action) {
            ACTION_START -> {
                val pairPort = intent.getStringExtra(EXTRA_PAIR_PORT)?.trim().orEmpty()
                if (pairPort.isEmpty()) {
                    Log.w(TAG, "收到 ACTION_START 但 pairPort 为空：仍将显示通知以便先完成通知链路验证")
                }
                try {
                    startForeground(NOTIFICATION_ID, buildIdleNotification(pairPort))
                    Log.i(TAG, "startForeground(Idle) 已调用，pairPort=$pairPort")
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground(Idle) 失败：${t.message}", t)
                }
            }

            ACTION_PAIR -> {
                val pairPort = intent.getStringExtra(EXTRA_PAIR_PORT)?.trim().orEmpty()
                val pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE)?.trim().orEmpty()

                try {
                    startForeground(NOTIFICATION_ID, buildWorkingNotification("收到配对码，准备执行配对...", pairPort))
                    Log.i(TAG, "startForeground(Working) 已调用，pairPort=$pairPort")
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground(Working) 失败：${t.message}", t)
                }

                thread(start = true) {
                    doPairAndConnect(pairPort = pairPort, pairingCode = pairingCode)
                }
            }

            else -> {
                // 未知 action：保持通知存在，避免前台服务被系统杀。
                try {
                    startForeground(NOTIFICATION_ID, buildIdleNotification(""))
                    Log.i(TAG, "startForeground(Default) 已调用")
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground(Default) 失败：${t.message}", t)
                }
            }
        }

        return START_STICKY
    }

    private fun doPairAndConnect(pairPort: String, pairingCode: String) {
        if (pairingCode.isEmpty()) {
            updateNotification("未输入配对码：请在通知栏输入配对码后提交。", pairPort)
            return
        }

        val digitsOnly = pairingCode.all { it.isDigit() }
        if (pairingCode.length != 6 || !digitsOnly) {
            updateNotification("配对码格式不正确：请输入 6 位数字配对码。", pairPort)
            return
        }

        // 说明：无线调试相关端口可能在短时间内变化或解析到错误值。
        // 按需求：如果配对/连接失败，需要实时刷新“配对端口(_adb-tls-pairing)”和“连接端口(_adb-tls-connect)”并重试，直到成功或超时。
        val overallTimeoutMs = 60_000L
        val retryDelayMs = 1_000L
        val scanTimeoutMs = 8_000L
        val startAt = System.currentTimeMillis()

        // 说明：为了减少用户输入，我们优先通过 mDNS 自动获取“配对端口”。
        // 无线调试在局域网广播 _adb-tls-pairing._tcp 服务，该服务的端口即“配对端口”。
        val adbExecPath = LocalAdb.resolveAdbExecPath(this)
        Log.i(TAG, "通知栏配对：adbExecPath=$adbExecPath")

        if (!LocalAdb.isAdbServerRunning()) {
            updateNotification("正在启动 ADB Server...", pairPort)
            LocalAdb.startAdbServer(this, adbExecPath)
        }

        val blockedPairPorts = mutableSetOf<String>()
        var noNewPairPortCount = 0
        var blockedPairHitCount = 0
        var pairHinted = false
        var lastResolvedPairPort = pairPort
        var lastResolvedPairHost = "127.0.0.1"
        var pairOk = false
        var lastPairOutput = ""

        while (!pairOk) {
            val elapsed = System.currentTimeMillis() - startAt
            if (elapsed > overallTimeoutMs) break

            val resolvedPairEp = if (pairPort.isBlank()) {
                updateNotification("未提供配对端口，开始自动扫描 _adb-tls-pairing._tcp ...", pairPort)
                scanEndpoint(
                    serviceType = AdbPortScanner.SERVICE_TYPE_PAIRING,
                    timeoutMs = scanTimeoutMs,
                    blockedPorts = blockedPairPorts
                )
            } else {
                AdbPortScanner.Endpoint(
                    host = java.net.InetAddress.getByName("127.0.0.1"),
                    port = pairPort.toIntOrNull() ?: 0,
                    serviceName = "manual"
                )
            }

            if (resolvedPairEp == null || resolvedPairEp.port <= 0) {
                lastPairOutput = "未能自动扫描到配对端口。"

                if (pairPort.isBlank() && blockedPairPorts.isNotEmpty()) {
                    blockedPairHitCount++
                    if (blockedPairHitCount >= 3) {
                        blockedPairHitCount = 0
                        updateNotification(
                            "检测到配对端口可能一直不变，且当前扫描到的端口都已尝试失败并被屏蔽。\n" +
                                "将继续等待新的配对端口广播（你可以在系统无线调试界面重新生成配对码以刷新端口）。",
                            pairPort
                        )
                    }
                }

                if (pairPort.isBlank() && blockedPairPorts.isNotEmpty()) {
                    noNewPairPortCount++
                }

                if (!pairHinted && noNewPairPortCount >= 3) {
                    pairHinted = true
                    updateNotification(
                        "多次扫描未获取到新的配对端口（可能系统只广播同一个端口，或无线调试端口未刷新）。\n" +
                            "建议：\n" +
                            "1) 打开系统【无线调试】界面，确认“使用配对码配对设备”的配对端口是否变化；\n" +
                            "2) 尝试关闭再开启【无线调试】；\n" +
                            "3) 重新生成配对码；\n" +
                            "4) 或使用应用里的“备用方案2：手动输入端口”。",
                        pairPort
                    )
                }

                updateNotification(
                    "未能自动扫描到配对端口，将在 ${retryDelayMs}ms 后重试。\n" +
                        "你也可以在系统“无线调试 -> 使用配对码配对设备”界面查看配对端口，\n" +
                        "然后回到应用手动填写端口再启动通知栏配对。",
                    pairPort
                )
                // 如果 blocked 列表不为空，说明“扫到的端口都尝试过了”，适当退避避免无意义刷屏
                SystemClock.sleep(if (pairPort.isBlank() && blockedPairPorts.isNotEmpty()) 2_000L else retryDelayMs)
                continue
            }

            lastResolvedPairPort = resolvedPairEp.port.toString()
            lastResolvedPairHost = resolvedPairEp.hostAddress
            val pairEndpoint = "${resolvedPairEp.hostAddress}:${resolvedPairEp.port}"
            updateNotification("正在执行 adb pair $pairEndpoint ...", lastResolvedPairPort)
            val pairResult = LocalAdb.runCommand(
                this,
                LocalAdb.buildAdbCommand(this, adbExecPath, listOf("pair", pairEndpoint, pairingCode))
            )
            lastPairOutput = pairResult.output

            if (pairResult.exitCode == 0) {
                pairOk = true
                break
            }

            if (pairPort.isBlank()) {
                blockedPairPorts.add(lastResolvedPairPort)
                Log.w(TAG, "配对失败，已屏蔽配对端口=$lastResolvedPairPort blocked=$blockedPairPorts")
            }

            blockedPairHitCount = 0

            noNewPairPortCount = 0

            updateNotification(
                "配对失败（exitCode=${pairResult.exitCode}），将刷新配对端口并重试...\n\n" +
                    "[adb pair 输出]\n${pairResult.output}",
                lastResolvedPairPort
            )
            SystemClock.sleep(retryDelayMs)
        }

        if (!pairOk) {
            updateNotification(
                "配对超时/失败：已多次刷新配对端口仍未成功。\n\n" +
                    "最后一次配对端口=$lastResolvedPairPort\n" +
                    "[adb pair 输出]\n$lastPairOutput",
                lastResolvedPairPort
            )
            return
        }

        updateNotification("配对成功，开始自动扫描连接端口(_adb-tls-connect._tcp)...", lastResolvedPairPort)

        // mDNS(NSD) 扫描逻辑说明：
        // 1) 无线调试“连接端口”是随机的，系统会通过局域网 mDNS 广播服务 _adb-tls-connect._tcp
        // 2) discoverServices 只能发现“服务存在”，拿不到端口
        // 3) 必须 resolveService，才能得到最终 host/port
        // 4) 我们只需要其中的 port，然后用本地 adb connect 127.0.0.1:port 完成连接

        val blockedConnectPorts = mutableSetOf<String>()
        var noNewConnectPortCount = 0
        var blockedConnectHitCount = 0
        var connectHinted = false
        var lastConnectPort: String? = null
        var lastConnectHost: String? = null
        var lastConnectOutput = ""
        var lastDevicesOutput = ""
        var connectOk = false

        while (!connectOk) {
            val elapsed = System.currentTimeMillis() - startAt
            if (elapsed > overallTimeoutMs) break

            val connectEp = scanEndpoint(
                serviceType = AdbPortScanner.SERVICE_TYPE_CONNECT,
                timeoutMs = scanTimeoutMs,
                blockedPorts = blockedConnectPorts
            )
            lastConnectPort = connectEp?.port?.toString()
            lastConnectHost = connectEp?.hostAddress

            if (connectEp == null || connectEp.port <= 0) {
                if (blockedConnectPorts.isNotEmpty()) {
                    blockedConnectHitCount++
                    if (blockedConnectHitCount >= 3) {
                        blockedConnectHitCount = 0
                        updateNotification(
                            "检测到连接端口可能一直不变，且当前扫描到的端口都已尝试失败并被屏蔽。\n" +
                                "将继续等待新的连接端口广播（你也可以关闭再开启无线调试以刷新端口）。",
                            lastResolvedPairPort
                        )
                    }
                }

                if (blockedConnectPorts.isNotEmpty()) {
                    noNewConnectPortCount++
                }

                if (!connectHinted && noNewConnectPortCount >= 3) {
                    connectHinted = true
                    updateNotification(
                        "多次扫描未获取到新的连接端口（可能系统只广播同一个端口，或无线调试连接端口未刷新）。\n" +
                            "建议：\n" +
                            "1) 打开系统【无线调试】界面，查看当前“IP:端口”；\n" +
                            "2) 尝试关闭再开启【无线调试】；\n" +
                            "3) 或使用应用里的“备用方案2：手动输入端口”进行连接。",
                        lastResolvedPairPort
                    )
                }

                updateNotification(
                    "已配对成功，但未扫描到连接端口，将在 ${retryDelayMs}ms 后重试扫描...\n" +
                        "你也可以在系统“无线调试”界面查看 IP:端口，并回到应用手动连接。",
                    lastResolvedPairPort
                )
                // 如果 blocked 列表不为空，说明“扫到的端口都尝试过了”，适当退避避免无意义刷屏
                SystemClock.sleep(if (blockedConnectPorts.isNotEmpty()) 2_000L else retryDelayMs)
                continue
            }

            val connectEndpoint = "${connectEp.hostAddress}:${connectEp.port}"
            updateNotification("扫描到连接端口：$connectEndpoint，正在执行 adb connect...", lastResolvedPairPort)

            val connectResult = LocalAdb.runCommand(
                this,
                LocalAdb.buildAdbCommand(this, adbExecPath, listOf("connect", connectEndpoint))
            )
            lastConnectOutput = connectResult.output

            val devicesResult = LocalAdb.runCommand(
                this,
                LocalAdb.buildAdbCommand(this, adbExecPath, listOf("devices"))
            )
            lastDevicesOutput = devicesResult.output

            val ok = devicesResult.output.lines().any { it.contains(connectEndpoint) && it.contains("device") }

            if (ok) {
                connectOk = true
                try {
                    ConfigManager(this).setLastAdbEndpoint(connectEndpoint)
                } catch (_: Exception) {
                }

                try {
                    updateNotification("连接确认成功，正在自动授予权限...", lastResolvedPairPort)
                    val grant = AdbPermissionGranter.applyAdbPowerUserPermissions(this)
                    if (!grant.ok) {
                        updateNotification("自动授予权限失败（已忽略，可手动处理）。\n${grant.output}", lastResolvedPairPort)
                    } else {
                        updateNotification("自动授予权限完成。", lastResolvedPairPort)
                    }
                } catch (t: Throwable) {
                    updateNotification("自动授予权限异常（已忽略）：${t.message}", lastResolvedPairPort)
                }

                val finalMsg = buildString {
                    append("配对成功，连接端口=$connectEndpoint\n")
                    append("[adb connect]\n${connectResult.output}\n\n")
                    append("[adb devices]\n${devicesResult.output}\n")
                    append("连接确认：成功（已保存记录）。")
                }
                updateNotification(finalMsg, lastResolvedPairPort)

                tryOpenChatActivity()
                return
            }

            blockedConnectPorts.add(connectEp.port.toString())
            Log.w(TAG, "连接未确认，已屏蔽连接端口=${connectEp.port} blocked=$blockedConnectPorts")

            blockedConnectHitCount = 0

            noNewConnectPortCount = 0

            updateNotification(
                "连接未确认，将刷新连接端口并重试...\n" +
                    "当前端口=$connectEndpoint\n\n" +
                    "[adb connect]\n${connectResult.output}\n\n" +
                    "[adb devices]\n${devicesResult.output}",
                lastResolvedPairPort
            )
            SystemClock.sleep(retryDelayMs)
        }

        val timeoutMsg = buildString {
            append("配对成功，但连接超时/失败：已多次刷新连接端口仍未确认设备。\n")
            append("最后一次连接端口=${(if (!lastConnectHost.isNullOrBlank() && !lastConnectPort.isNullOrBlank()) "$lastConnectHost:$lastConnectPort" else lastConnectPort) ?: "(空)"}\n\n")
            append("[adb connect]\n$lastConnectOutput\n\n")
            append("[adb devices]\n$lastDevicesOutput\n")
        }
        updateNotification(timeoutMsg, lastResolvedPairPort)
    }

    private fun tryOpenChatActivity() {
        try {
            mainHandler.post {
                try {
                    startActivity(
                        Intent(this@PairingService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("extra_boot_stage", "loading")
                        }
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "配对成功后自动跳转 MainActivity(LOADING) 失败：${t.message}", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "配对成功后投递跳转任务失败：${t.message}", t)
        }
    }

    private fun scanEndpoint(
        serviceType: String,
        timeoutMs: Long,
        blockedPorts: Set<String> = emptySet(),
    ): AdbPortScanner.Endpoint? {
        val lock = Object()
        var result: AdbPortScanner.Endpoint? = null

        val scanner = AdbPortScanner(
            context = this,
            serviceType = serviceType,
            onEndpoint = { ep ->
                synchronized(lock) {
                    val p = ep.port.toString()
                    if (blockedPorts.contains(p)) {
                        Log.i(TAG, "扫描到端口但已屏蔽：${ep.hostAddress}:$p type=$serviceType")
                        return@synchronized
                    }
                    result = ep
                    lock.notifyAll()
                }
            },
            onError = { msg, _ ->
                Log.w(TAG, "NSD 扫描错误：$msg")
            }
        )

        try {
            scanner.start()
            val start = System.currentTimeMillis()
            synchronized(lock) {
                while (result == null) {
                    val remaining = timeoutMs - (System.currentTimeMillis() - start)
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描连接端口异常", e)
        } finally {
            try {
                scanner.stop()
            } catch (_: Exception) {
            }
        }

        return result
    }

    private fun updateNotification(message: String, pairPort: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildWorkingNotification(message, pairPort))
    }

    private fun buildIdleNotification(pairPort: String): Notification {
        val input = RemoteInput.Builder(EXTRA_PAIRING_CODE)
            .setLabel("请输入6位配对码")
            .build()

        val intent = Intent(this, PairingInputReceiver::class.java).apply {
            action = ACTION_REMOTE_INPUT
            putExtra(EXTRA_PAIR_PORT, pairPort)
        }

        // RemoteInput 要求 PendingIntent 可变：否则在 Android 12+ 可能无法展示输入框或无法回传输入。
        // 参考：RemoteInput 需要系统往 Intent 里塞入结果 bundle。
        val remoteInputFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(this, 1, intent, remoteInputFlags)

        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_input_add,
            "提交",
            pending
        )
            .addRemoteInput(input)
            .build()

        val openAppIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val contentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val openPending = PendingIntent.getActivity(this, 2, openAppIntent, contentFlags)

        val contentText = "在此处输入“无线调试配对码”。"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("AutoGLM 无线调试配对")
            .setContentText(contentText)
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(openPending, true)
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun buildWorkingNotification(message: String, pairPort: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AutoGLM 无线调试")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentText(message.take(40))
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "ADB配对助手",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "用于输入ADB配对码"
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "AutoglmAdb"

        const val ACTION_START = "com.example.autoglm.action.PAIRING_START"
        const val ACTION_REMOTE_INPUT = "com.example.autoglm.action.PAIRING_REMOTE_INPUT"
        const val ACTION_PAIR = "com.example.autoglm.action.PAIRING_PAIR"

        const val EXTRA_PAIRING_CODE = "extra_pairing_code"
        const val EXTRA_PAIR_PORT = "extra_pair_port"

        private const val CHANNEL_ID = "autoglm_pairing"
        private const val NOTIFICATION_ID = 1001

        fun startForegroundCompat(context: Context, intent: Intent) {
            try {
                Log.i(TAG, "startForegroundCompat: action=${intent.action}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "startForegroundCompat 失败：${t.message}", t)
            }
        }

        fun startPairingNotification(context: Context, pairPort: String) {
            val intent = Intent(context, PairingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PAIR_PORT, pairPort)
            }
            startForegroundCompat(context, intent)
        }
    }
}
