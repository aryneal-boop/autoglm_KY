package com.example.autoglm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class AdbAutoConnectManager(
    private val context: Context,
) {

    enum class State {
        INITIALIZING,
        CONNECTING,
        SCANNING,
        CONNECTED,
        FAILED,
    }

    data class Result(
        val state: State,
        val message: String = "",
        val endpoint: String? = null,
    )

    @Volatile
    private var stopped: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: AdbPortScanner? = null

    private companion object {
        private const val TAG = "AutoglmAdb"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val DEVICES_TIMEOUT_MS = 6_000L
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val SERVER_WAIT_READY_MS = 2_000L
        private const val SERVER_WAIT_POLL_MS = 50L
        private const val RETRY_DELAY_SHORT_MS = 300L
        private const val RETRY_DELAY_MS = 600L
        private const val MAX_CONNECT_ATTEMPTS = 3
    }

    fun stop() {
        stopped = true
        try {
            scanner?.stop()
        } catch (_: Exception) {
        } finally {
            scanner = null
        }
    }

    fun start(onResult: (Result) -> Unit) {
        stopped = false

        fun emit(r: Result) {
            mainHandler.post {
                if (!stopped) onResult(r)
            }
        }

        Thread {
            emit(Result(State.INITIALIZING, "正在初始化 ADB 环境..."))

            try {
                LocalAdb.prepareAdbEnvironment(context)
            } catch (t: Throwable) {
                Log.w(TAG, "prepareAdbEnvironment 失败（已忽略）: ${t.message}")
            }

            val adbExecPath = LocalAdb.resolveAdbExecPath(context)

            tryEnsureServerRunning(adbExecPath)

            if (stopped) return@Thread

            val lastEndpoint = try {
                ConfigManager(context).getLastAdbEndpoint().trim()
            } catch (_: Exception) {
                ""
            }

            if (lastEndpoint.isNotEmpty()) {
                emit(Result(State.CONNECTING, "正在快速重连：$lastEndpoint", lastEndpoint))

                val ok = tryConnectAndVerify(adbExecPath, lastEndpoint, ::emit)
                if (ok) {
                    try {
                        ConfigManager(context).setLastAdbEndpoint(lastEndpoint)
                    } catch (_: Exception) {
                    }
                    emit(Result(State.CONNECTED, "已连接：$lastEndpoint", lastEndpoint))
                    return@Thread
                }
            }

            if (stopped) return@Thread

            emit(Result(State.SCANNING, "正在尝试自动扫描连接端口..."))

            val scanOk = scanAndConnect(adbExecPath, timeoutMs = SCAN_TIMEOUT_MS, emit = { emit(it) })
            if (scanOk) return@Thread

            if (stopped) return@Thread

            val msg = if (lastEndpoint.isEmpty()) {
                "未检测到历史连接记录，等待你触发配对。"
            } else {
                "自动扫描超时，等待你触发配对。"
            }
            emit(Result(State.FAILED, msg))
        }.start()
    }

    private fun tryConnectAndVerify(
        adbExecPath: String,
        endpoint: String,
        emit: ((Result) -> Unit)? = null,
    ): Boolean {
        var lastConnectOutput = ""
        var lastDevicesOutput = ""

        repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
            if (stopped) return false

            tryEnsureServerRunning(adbExecPath)

            val connectResult = try {
                LocalAdb.runCommand(
                    context,
                    LocalAdb.buildAdbCommand(context, adbExecPath, listOf("connect", endpoint)),
                    timeoutMs = CONNECT_TIMEOUT_MS
                )
            } catch (_: Exception) {
                null
            }

            if (connectResult == null) {
                SystemClock.sleep(RETRY_DELAY_SHORT_MS)
                return@repeat
            }

            lastConnectOutput = connectResult.output

            val outLower = connectResult.output.lowercase()
            val connectOk = connectResult.exitCode == 0 && (
                outLower.contains("connected") ||
                    outLower.contains("already connected") ||
                    outLower.contains("already connected to")
                )
            if (!connectOk) {
                emit?.invoke(
                    Result(
                        State.CONNECTING,
                        "连接失败（尝试 ${attempt + 1}/3）：$endpoint\n[adb connect]\n${connectResult.output}",
                        endpoint
                    )
                )
                SystemClock.sleep(RETRY_DELAY_MS)
                return@repeat
            }

            val devicesResult = try {
                LocalAdb.runCommand(
                    context,
                    LocalAdb.buildAdbCommand(context, adbExecPath, listOf("devices")),
                    timeoutMs = DEVICES_TIMEOUT_MS
                )
            } catch (_: Exception) {
                null
            }

            if (devicesResult == null) {
                SystemClock.sleep(RETRY_DELAY_SHORT_MS)
                return@repeat
            }

            lastDevicesOutput = devicesResult.output
            val ok = devicesResult.output.lines().any { it.contains(endpoint) && it.contains("device") }
            if (ok) {
                try {
                    emit?.invoke(Result(State.CONNECTING, "连接确认成功，正在自动授予权限...", endpoint))
                } catch (_: Exception) {
                }

                try {
                    val grant = AdbPermissionGranter.applyAdbPowerUserPermissions(context)
                    if (!grant.ok) {
                        emit?.invoke(Result(State.CONNECTING, "自动授予权限失败（已忽略，后续可手动处理）。\n${grant.output}", endpoint))
                    } else {
                        emit?.invoke(Result(State.CONNECTING, "自动授予权限完成。", endpoint))
                    }
                } catch (t: Throwable) {
                    emit?.invoke(Result(State.CONNECTING, "自动授予权限异常（已忽略）：${t.message}", endpoint))
                }

                return true
            }

            emit?.invoke(
                Result(
                    State.CONNECTING,
                    "连接未确认（尝试 ${attempt + 1}/3）：$endpoint\n[adb devices]\n${devicesResult.output}",
                    endpoint
                )
            )
            SystemClock.sleep(RETRY_DELAY_MS)
        }

        if (!lastConnectOutput.isBlank()) {
            emit?.invoke(Result(State.CONNECTING, "最终连接失败：$endpoint\n[adb connect]\n$lastConnectOutput", endpoint))
        } else if (!lastDevicesOutput.isBlank()) {
            emit?.invoke(Result(State.CONNECTING, "最终连接未确认：$endpoint\n[adb devices]\n$lastDevicesOutput", endpoint))
        }

        return false
    }

    private fun tryEnsureServerRunning(adbExecPath: String) {
        if (LocalAdb.isAdbServerRunning()) return

        try {
            LocalAdb.startAdbServer(context, adbExecPath)
        } catch (_: Exception) {
        }

        // 等待端口就绪（最多 2 秒），避免立即 connect 时 daemon 还没起来
        val startAt = SystemClock.elapsedRealtime()
        while (!stopped && (SystemClock.elapsedRealtime() - startAt) < SERVER_WAIT_READY_MS) {
            if (LocalAdb.isAdbServerRunning()) return
            SystemClock.sleep(SERVER_WAIT_POLL_MS)
        }
    }

    private fun scanAndConnect(
        adbExecPath: String,
        timeoutMs: Long,
        emit: (Result) -> Unit,
    ): Boolean {
        val lock = Object()
        var foundEndpoint: AdbPortScanner.Endpoint? = null
        var scanError: String? = null

        val s = AdbPortScanner(
            context = context,
            serviceType = AdbPortScanner.SERVICE_TYPE_CONNECT,
            onEndpoint = { ep ->
                synchronized(lock) {
                    if (foundEndpoint == null) {
                        foundEndpoint = ep
                        lock.notifyAll()
                    }
                }
            },
            onError = { msg, _ ->
                synchronized(lock) {
                    if (scanError == null) {
                        scanError = msg
                        lock.notifyAll()
                    }
                }
            }
        )

        scanner = s

        try {
            s.start()
            val startAt = SystemClock.elapsedRealtime()
            synchronized(lock) {
                while (!stopped && foundEndpoint == null && scanError == null) {
                    val elapsed = SystemClock.elapsedRealtime() - startAt
                    val remaining = timeoutMs - elapsed
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                s.stop()
            } catch (_: Exception) {
            }
            if (scanner === s) scanner = null
        }

        if (stopped) return false

        val ep = foundEndpoint
        if (ep == null) {
            val msg = scanError ?: "扫描超时"
            emit(Result(State.SCANNING, "扫描未得到端口：$msg"))
            return false
        }

        val endpoint = "${ep.hostAddress}:${ep.port}"
        emit(Result(State.CONNECTING, "扫描到端口，正在连接：$endpoint", endpoint))

        val ok = tryConnectAndVerify(adbExecPath, endpoint)
        if (!ok) {
            emit(Result(State.SCANNING, "连接未确认，将继续等待...", endpoint))
            return false
        }

        try {
            ConfigManager(context).setLastAdbEndpoint(endpoint)
        } catch (_: Exception) {
        }

        emit(Result(State.CONNECTED, "已连接：$endpoint", endpoint))
        return true
    }

}
