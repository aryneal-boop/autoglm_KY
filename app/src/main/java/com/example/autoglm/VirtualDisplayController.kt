package com.example.autoglm

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.media.ImageReader
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine
import com.example.autoglm.ShizukuBridge
import com.example.autoglm.input.VirtualAsyncInputInjector

object VirtualDisplayController {
    @Volatile
    private var activeDisplayId: Int? = null

    @Volatile
    private var welcomeShownForCurrentVd: Boolean = false

    @Volatile
    private var lifecycleObserverInstalled: Boolean = false

    @Synchronized
    fun prepareForTask(context: Context, adbExecPath: String): Int? {
        val execEnv = try {
            ConfigManager(context).getExecutionEnvironment()
        } catch (_: Exception) {
            ConfigManager.EXEC_ENV_MAIN
        }

        val existing = activeDisplayId
        if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL && existing != null && ShizukuVirtualDisplayEngine.isStarted()) {
            return existing
        }

        if (execEnv != ConfigManager.EXEC_ENV_VIRTUAL) {
            try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}
            activeDisplayId = null
            welcomeShownForCurrentVd = false
            return null
        }

        // Shizuku 权限/可用性校验：避免后续反射/系统服务调用报错不直观。
        if (!ShizukuBridge.pingBinder()) {
            Log.w(TAG, "Shizuku binder not ready")
            activeDisplayId = null
            return null
        }
        if (!ShizukuBridge.hasPermission()) {
            Log.w(TAG, "Shizuku permission not granted")
            activeDisplayId = null
            return null
        }

        val (vdW, vdH) = try {
            ConfigManager(context).getVirtualDisplaySize(isLandscape = false)
        } catch (_: Exception) {
            480 to 848
        }
        val vdDpi = try {
            ConfigManager(context).getVirtualDisplayDpi().takeIf { it in 72..640 } ?: ConfigManager.DEFAULT_VIRTUAL_DISPLAY_DPI
        } catch (_: Exception) {
            ConfigManager.DEFAULT_VIRTUAL_DISPLAY_DPI
        }

        // Shizuku VirtualDisplay: strictly follow ReadVirtualDisplay.md principle.
        val r = ShizukuVirtualDisplayEngine.ensureStarted(
            ShizukuVirtualDisplayEngine.Args(
                name = "AutoGLM-Virtual",
                width = vdW,
                height = vdH,
                dpi = vdDpi,
                refreshRate = 0f,
                rotatesWithContent = false,
                ownerPackage = "com.android.shell",
            )
        )
        if (r.isSuccess) {
            val did = r.getOrNull()
            val isNewOrChanged = (did != null && did != existing)
            activeDisplayId = did

            if (isNewOrChanged) {
                welcomeShownForCurrentVd = false
            }
            if (did != null && !welcomeShownForCurrentVd) {
                welcomeShownForCurrentVd = true
                showWelcomePresentationOnDisplay(context.applicationContext, did)
            }
            return did
        }
        Log.w(TAG, "ShizukuVirtualDisplayEngine.ensureStarted failed", r.exceptionOrNull())
        activeDisplayId = null
        welcomeShownForCurrentVd = false
        return null
    }

    fun getDisplayId(): Int? = activeDisplayId

    @JvmStatic
    fun showWelcomeOnActiveDisplayBestEffort(context: Context) {
        val did = activeDisplayId ?: return
        runCatching {
            showWelcomePresentationOnDisplay(context.applicationContext, did)
            welcomeShownForCurrentVd = true
        }
    }

    // --- Compatibility layer (old monitor UI expects these APIs) ---
    @Synchronized
    fun ensureVirtualDisplayForMonitor(context: Context): Int? {
        // Keep existing entrypoints working. In virtual mode, this will create/reuse Shizuku VirtualDisplay.
        // In main-screen mode, it will return null.
        return prepareForTask(context, "")
    }

    @Synchronized
    fun startMonitor(context: Context): Boolean {
        // MonitorActivity currently uses screenrecord-based preview; we only need a valid displayId.
        return ensureVirtualDisplayForMonitor(context) != null
    }

    fun getMonitorImageReader(): ImageReader? = null

    @Synchronized
    fun stopMonitor() {
        // no-op (legacy API)
    }

    @JvmStatic
    fun screenshotPngBase64(): String {
        val did = activeDisplayId ?: return ""
        if (!ShizukuVirtualDisplayEngine.isStarted()) return ""
        // best effort: refresh focus before capture to reduce black frames on some ROMs
        runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(did) }

        val bmp = ShizukuVirtualDisplayEngine.captureLatestBitmap().getOrNull() ?: return ""
        return try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    @JvmStatic
    fun screenshotPngBase64NonBlack(
        maxWaitMs: Long = 1500L,
        pollIntervalMs: Long = 80L,
    ): String {
        val did = activeDisplayId ?: return ""
        if (!ShizukuVirtualDisplayEngine.isStarted()) return ""
        runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(did) }

        val deadline = android.os.SystemClock.uptimeMillis() + maxWaitMs
        var lastBmp: Bitmap? = null
        while (android.os.SystemClock.uptimeMillis() <= deadline) {
            val bmp = ShizukuVirtualDisplayEngine.captureLatestBitmap().getOrNull()
            if (bmp != null) {
                lastBmp?.recycle()
                lastBmp = bmp
                if (!isLikelyBlackBitmap(bmp)) {
                    return try {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        val bytes = baos.toByteArray()
                        if (bytes.isEmpty()) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } catch (_: Exception) {
                        ""
                    } finally {
                        runCatching { bmp.recycle() }
                    }
                }
            }
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
        runCatching { lastBmp?.recycle() }
        return ""
    }

    @JvmStatic
    fun ensureFocusedDisplayBestEffort(): Boolean {
        val did = activeDisplayId ?: return false
        if (!ShizukuVirtualDisplayEngine.isStarted()) return false
        return runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(did) }.isSuccess
    }

    private val asyncInputInjector by lazy { VirtualAsyncInputInjector() }

    @JvmStatic
    fun injectTapBestEffort(displayId: Int, x: Int, y: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        val downTime = android.os.SystemClock.uptimeMillis()
        runCatching {
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, x.toFloat(), y.toFloat(), android.view.MotionEvent.ACTION_DOWN)
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, x.toFloat(), y.toFloat(), android.view.MotionEvent.ACTION_UP)
        }
    }

    @JvmStatic
    fun injectSwipeBestEffort(displayId: Int, startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return

        val downTime = android.os.SystemClock.uptimeMillis()
        val dur = durationMs.coerceAtLeast(1)
        runCatching {
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, startX.toFloat(), startY.toFloat(), android.view.MotionEvent.ACTION_DOWN)
            val startTime = android.os.SystemClock.uptimeMillis()
            val endTime = startTime + dur
            while (android.os.SystemClock.uptimeMillis() < endTime) {
                val elapsed = android.os.SystemClock.uptimeMillis() - startTime
                val frac = (elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val ix = (startX + (endX - startX) * frac).toInt()
                val iy = (startY + (endY - startY) * frac).toInt()
                asyncInputInjector.injectSingleTouchAsync(displayId, downTime, ix.toFloat(), iy.toFloat(), android.view.MotionEvent.ACTION_MOVE)
                try {
                    Thread.sleep(16L)
                } catch (_: Exception) {
                }
            }
            asyncInputInjector.injectSingleTouchAsync(displayId, downTime, endX.toFloat(), endY.toFloat(), android.view.MotionEvent.ACTION_UP)
        }
    }

    @JvmStatic
    fun injectBackBestEffort(displayId: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        runCatching {
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
        }
    }

    @JvmStatic
    fun injectHomeBestEffort(displayId: Int) {
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        runCatching {
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN)
            asyncInputInjector.injectKeyEventAsync(displayId, KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_UP)
        }
    }

    fun hardResetOverlayAsync(context: Context) {
        thread(start = true, name = "VirtualDisplayHardReset") {
            hardResetOverlay(context)
        }
    }

    @Synchronized
    fun hardResetOverlay(context: Context) {
        val ctx = context.applicationContext
        try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}

        activeDisplayId = null
        welcomeShownForCurrentVd = false
    }

    fun cleanupOverlayOnlyAsync(context: Context) {
        thread(start = true, name = "VirtualDisplayOverlayOnlyCleanup") {
            cleanupOverlayOnly(context)
        }
    }

    @Synchronized
    fun cleanupOverlayOnly(context: Context) {
        val ctx = context.applicationContext
        try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}

        activeDisplayId = null
        welcomeShownForCurrentVd = false
    }

    fun ensureProcessLifecycleObserverInstalled(context: Context) {
        if (lifecycleObserverInstalled) return
        synchronized(this) {
            if (lifecycleObserverInstalled) return
            lifecycleObserverInstalled = true
        }
    }

    fun cleanupAsync(context: Context) {
        thread(start = true, name = "VirtualDisplayCleanup") {
            cleanup(context)
        }
    }

    @Synchronized
    fun cleanup(context: Context) {
        val ctx = context.applicationContext
        try { ShizukuVirtualDisplayEngine.stop() } catch (_: Exception) {}

        activeDisplayId = null
        welcomeShownForCurrentVd = false
    }

    private const val TAG = "VirtualDisplay"

    private fun showWelcomePresentationOnDisplay(appContext: Context, displayId: Int) {
        runCatching {
            val intent = Intent().apply {
                setClassName(appContext.packageName, "${appContext.packageName}.WelcomeActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            appContext.startActivity(intent, options.toBundle())
            runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(displayId) }
            return
        }

        if (ShizukuBridge.pingBinder() && ShizukuBridge.hasPermission()) {
            thread(start = true, name = "ShowWelcomeOnDisplay") {
                val component = "${appContext.packageName}/.WelcomeActivity"
                val flags = 0x10000000
                val candidates = listOf(
                    "cmd activity start-activity --user 0 --display $displayId --windowingMode 1 -n $component -f $flags",
                    "cmd activity start-activity --user 0 --display $displayId -n $component -f $flags",
                    "am start --user 0 --display $displayId -n $component -f $flags",
                    "am start --display $displayId -n $component -f $flags",
                )
                for (c in candidates) {
                    val r = runCatching { ShizukuBridge.execResult(c) }.getOrNull()
                    if (r != null) {
                        val err = r.stderrText().trim()
                        val out = r.stdoutText().trim()
                        Log.i(
                            TAG,
                            "showWelcome exec: exitCode=${r.exitCode} cmd=$c stderr=${err.take(200)} stdout=${out.take(200)}"
                        )
                        if (r.exitCode == 0) {
                            runCatching { ShizukuVirtualDisplayEngine.ensureFocusedDisplay(displayId) }
                            return@thread
                        }
                    }
                }
            }
            return
        }

        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val h = Handler(Looper.getMainLooper())
        val tryShow = object : Runnable {
            var attempts = 0
            override fun run() {
                val targetDisplay = dm.getDisplay(displayId)
                if (targetDisplay != null) {
                    try {
                        Log.i(TAG, "showWelcome: displayId=$displayId attempt=$attempts")
                        WelcomePresentation(appContext, targetDisplay).show()
                    } catch (t: Throwable) {
                        Log.w(TAG, "showWelcome failed: displayId=$displayId", t)
                        // On some ROMs the Display object may exist but WMS isn't ready to attach windows yet.
                        // Retry when we see InvalidDisplayException.
                        if (t is android.view.WindowManager.InvalidDisplayException) {
                            attempts++
                            if (attempts >= 30) return
                            h.postDelayed(this, 200L)
                            return
                        }
                    }
                    return
                }
                attempts++
                if (attempts >= 30) return
                h.postDelayed(this, 200L)
            }
        }
        Log.i(TAG, "showWelcome scheduled: displayId=$displayId")
        h.post(tryShow)
    }

    private fun isLikelyBlackBitmap(bmp: Bitmap): Boolean {
        return runCatching {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return@runCatching true

            val sampleX = 32
            val sampleY = 32
            val stepX = maxOf(1, w / sampleX)
            val stepY = maxOf(1, h / sampleY)
            var nonBlack = 0
            var total = 0

            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r > 10 || g > 10 || b > 10) {
                        nonBlack++
                        if (nonBlack >= 20) {
                            return@runCatching false
                        }
                    }
                    total++
                    x += stepX
                }
                y += stepY
            }
            total > 0 && nonBlack < 20
        }.getOrElse { true }
    }
}
