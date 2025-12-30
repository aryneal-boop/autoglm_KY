package com.example.autoglm

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.media.ImageReader
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine

object VirtualDisplayController {
    @Volatile
    private var activeDisplayId: Int? = null

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

        val dm = context.resources.displayMetrics
        val isLandscape = dm.widthPixels >= dm.heightPixels
        val vdW = if (isLandscape) 854 else 480
        val vdH = if (isLandscape) 480 else 854
        val vdDpi = if (vdW == 480 || vdH == 480) 142 else 440

        // Shizuku VirtualDisplay: strictly follow ReadVirtualDisplay.md principle.
        val r = ShizukuVirtualDisplayEngine.ensureStarted(
            ShizukuVirtualDisplayEngine.Args(
                name = "AutoGLM-Virtual",
                width = vdW,
                height = vdH,
                dpi = vdDpi,
                refreshRate = 0f,
                rotatesWithContent = false,
            )
        )
        if (r.isSuccess) {
            val did = r.getOrNull()
            activeDisplayId = did
            return did
        }
        Log.w(TAG, "ShizukuVirtualDisplayEngine.ensureStarted failed", r.exceptionOrNull())
        activeDisplayId = null
        return null
    }

    fun getDisplayId(): Int? = activeDisplayId

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
    }

    fun ensureProcessLifecycleObserverInstalled(context: Context) {
        if (lifecycleObserverInstalled) return
        synchronized(this) {
            if (lifecycleObserverInstalled) return
            val appCtx = context.applicationContext
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        try {
                            if (!TaskControl.isTaskRunning) {
                                cleanupOverlayOnlyAsync(appCtx)
                            }
                        } catch (_: Exception) {
                        }
                    }
                })
            } catch (_: Exception) {
            }
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
    }

    private const val TAG = "VirtualDisplay"

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
