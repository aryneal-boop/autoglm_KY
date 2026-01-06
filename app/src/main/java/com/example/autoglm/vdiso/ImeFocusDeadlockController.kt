package com.example.autoglm.vdiso

import android.os.SystemClock
import android.util.Log
import com.example.autoglm.ShizukuBridge
import com.example.autoglm.VirtualDisplayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 输入法焦点死锁监测与自愈控制器（虚拟隔离模式）。
 *
 * **背景问题**
 * - 在部分 ROM/Android 版本中，VirtualDisplay + IME 弹出时可能出现“焦点来回抢占/输入路由异常”，
 *   导致虚拟屏持续黑屏、触摸/按键无效或输入法无法正常关闭。
 *
 * **用途**
 * - 轮询 `dumpsys window windows`，解析目标 `displayId` 上 IME 是否处于激活状态。
 * - 一旦检测到 IME 激活，进入 FORCE_LOCK：高频执行 `wm set-focused-display <displayId>`
 *   将焦点强制锁定在虚拟屏，直到 IME 消失。
 * - 通过 [Callback] 向上层（通常是 `FloatingStatusService`）反馈锁定状态变化，用于展示 UI 提示/遮罩。
 *
 * **典型用法**
 * - `ImeFocusDeadlockController(workerScope).apply { callback = ...; start() }`
 * - 在 Service/Activity 销毁时调用 [stop]。
 *
 * **引用路径（常见）**
 * - `FloatingStatusService`：创建并接收回调，必要时显示“焦点死锁防护”提示。
 * - `VirtualDisplayController.getDisplayId()`：提供当前虚拟屏 displayId。
 *
 * **使用注意事项**
 * - 依赖 Shizuku：需要能执行 `dumpsys` / `wm` 命令，否则会自动降频轮询。
 * - `dumpsys window` 属于重命令：本类默认轮询间隔较大，并在 Shizuku 不可用时进一步降频。
 */
class ImeFocusDeadlockController(
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 220L,
    private val forceIntervalMs: Long = 90L,
) {

    interface Callback {
        fun onLockStateChanged(locked: Boolean, displayId: Int, detail: String)
    }

    private val TAG = "ImeFocusDeadlock"

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var locked: Boolean = false

    private var monitorJob: Job? = null

    private var forceLockJob: Job? = null

    var callback: Callback? = null

    fun start() {
        if (running) return
        running = true
        monitorJob = scope.launch {
            loopMonitor()
        }
    }

    fun stop() {
        running = false
        monitorJob?.cancel()
        monitorJob = null
        stopForceLock("stop")
    }

    private suspend fun loopMonitor() {
        var lastLogAt = 0L
        while (scope.isActive && running) {
            val did = VirtualDisplayController.getDisplayId() ?: 0
            val t0 = SystemClock.uptimeMillis()
            val shizukuReady = try {
                ShizukuBridge.pingBinder() && ShizukuBridge.hasPermission()
            } catch (_: Throwable) {
                false
            }
            val ime = if (did > 0 && shizukuReady) {
                val r = ShizukuBridge.execResult("dumpsys window windows")
                val text = if (r.exitCode == 0) r.stdoutText() else ""
                parseImeActive(text, did)
            } else {
                ImeParseResult(false, "did=$did shizukuReady=$shizukuReady")
            }
            val cost = SystemClock.uptimeMillis() - t0

            if (ime.active && !locked) {
                locked = true
                Log.i(TAG, "IME detected -> enter FORCE_LOCK did=$did cost=${cost}ms detail=${ime.detail}")
                callback?.onLockStateChanged(true, did, ime.detail)
                startForceLock(did)
            } else if (!ime.active && locked) {
                locked = false
                Log.i(TAG, "IME gone -> exit FORCE_LOCK did=$did cost=${cost}ms detail=${ime.detail}")
                stopForceLock("ime_gone")
                callback?.onLockStateChanged(false, did, ime.detail)
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastLogAt >= 1500L) {
                lastLogAt = now
                Log.i(TAG, "tick: did=$did locked=$locked imeActive=${ime.active} cost=${cost}ms")
            }

            // 在 Shizuku 不可用或 displayId 无效时，降低轮询频率，减少无意义开销。
            delay(if (did <= 0 || !shizukuReady) pollIntervalMs * 2 else pollIntervalMs)
        }
    }

    private fun startForceLock(displayId: Int) {
        if (displayId <= 0) return
        if (forceLockJob?.isActive == true) return

        forceLockJob = scope.launch {
            var n = 0L
            var lastLogAt = 0L
            while (scope.isActive && running && locked) {
                val cmd = "wm set-focused-display $displayId"
                val r = ShizukuBridge.execResult(cmd)
                n++

                val now = SystemClock.uptimeMillis()
                if (now - lastLogAt >= 800L) {
                    lastLogAt = now
                    Log.i(TAG, "forceLock: did=$displayId n=$n exit=${r.exitCode}")
                }

                delay(forceIntervalMs)
            }
        }
    }

    private fun stopForceLock(reason: String) {
        forceLockJob?.cancel()
        forceLockJob = null
        Log.i(TAG, "forceLock stopped: reason=$reason")
    }

    private data class ImeParseResult(
        val active: Boolean,
        val detail: String,
    )

    private fun parseImeActive(text: String, displayId: Int): ImeParseResult {
        if (text.isEmpty()) return ImeParseResult(false, "empty dumpsys")

        var inImeBlock = false
        var imeTitle: String? = null
        var hasSurface = false
        var viewVisibility: Int? = null
        var displayMatched = false

        fun isWindowHeaderLine(line: String): Boolean {
            return line.contains("Window{") || line.trimStart().startsWith("Window #")
        }

        fun isImeHeaderLine(line: String): Boolean {
            val l = line.lowercase()
            if (!isWindowHeaderLine(line)) return false
            return l.contains("inputmethod") || l.contains("input method")
        }

        fun matchDisplay(line: String): Boolean {
            return line.contains("displayId=$displayId") ||
                line.contains("mDisplayId=$displayId") ||
                line.contains("displayId $displayId") ||
                line.contains("displayId=$displayId,") ||
                line.contains("displayId=$displayId ")
        }

        fun parseIntValue(line: String, key: String): Int? {
            val idx = line.indexOf(key)
            if (idx < 0) return null
            var i = idx + key.length
            while (i < line.length && line[i] == ' ') i++

            if (i + 1 < line.length && line[i] == '0' && (line[i + 1] == 'x' || line[i + 1] == 'X')) {
                i += 2
                val hex = StringBuilder()
                while (i < line.length) {
                    val c = line[i]
                    val isHex = c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F')
                    if (!isHex) break
                    hex.append(c)
                    i++
                }
                if (hex.isEmpty()) return null
                return hex.toString().toIntOrNull(16)
            }

            val sb = StringBuilder()
            while (i < line.length) {
                val c = line[i]
                if (c == '-' || c.isDigit()) {
                    sb.append(c)
                    i++
                    continue
                }
                break
            }
            if (sb.isEmpty()) return null
            return sb.toString().toIntOrNull()
        }

        fun isImeActiveRobust(): Pair<Boolean, String> {
            val surfaceOk = hasSurface
            val displayOk = displayMatched
            val vis = viewVisibility
            val notGone = vis != 8

            if (surfaceOk && displayOk && notGone) {
                return true to "surface=$surfaceOk display=$displayOk viewVis=$vis"
            }
            return false to "surface=$surfaceOk display=$displayOk viewVis=$vis"
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (isImeHeaderLine(line)) {
                inImeBlock = true
                imeTitle = line.take(160)
                hasSurface = false
                viewVisibility = null
                displayMatched = false
            } else if (inImeBlock && isWindowHeaderLine(line) && !isImeHeaderLine(line)) {
                inImeBlock = false
            }

            if (!inImeBlock) continue

            if (line.contains("mHasSurface=true") || line.contains("hasSurface=true")) {
                hasSurface = true
            }
            parseIntValue(line, "mViewVisibility=")?.let { viewVisibility = it }
            if (matchDisplay(line)) {
                displayMatched = true
            }

            val (ok, reason) = isImeActiveRobust()
            if (ok) {
                val detail = "title=${imeTitle ?: ""} $reason"
                return ImeParseResult(true, detail)
            }
        }

        val detail = "hasSurface=$hasSurface viewVis=$viewVisibility displayMatched=$displayMatched title=${imeTitle ?: ""}"
        return ImeParseResult(false, detail)
    }
}
