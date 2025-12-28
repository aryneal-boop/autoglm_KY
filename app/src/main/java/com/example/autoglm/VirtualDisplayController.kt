package com.example.autoglm

import android.content.Context
import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Display
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

object VirtualDisplayController {

    private class CaptureState(
        val virtualDisplay: VirtualDisplay,
        var imageReader: ImageReader,
        val thread: HandlerThread,
        val handler: Handler,
    )

    @Volatile
    private var captureState: CaptureState? = null

    @Volatile
    private var monitorImageReader: ImageReader? = null

    @Volatile
    private var activeDisplayId: Int? = null

    @Volatile
    private var overlayRequested: Boolean = false

    @Volatile
    private var lifecycleObserverInstalled: Boolean = false

    @Synchronized
    fun prepareForTask(context: Context, adbExecPath: String): Int? {
        val execEnv = try {
            ConfigManager(context).getExecutionEnvironment()
        } catch (_: Exception) {
            ConfigManager.EXEC_ENV_MAIN
        }

        if (execEnv != ConfigManager.EXEC_ENV_VIRTUAL) {
            try {
                releaseCaptureLocked()
            } catch (_: Exception) {
            }
            activeDisplayId = null
            overlayRequested = false
            return null
        }

        val existingCapture = captureState
        if (existingCapture != null) {
            val did = try {
                existingCapture.virtualDisplay.display?.displayId
            } catch (_: Exception) {
                null
            }
            if (did != null) {
                activeDisplayId = did
                overlayRequested = false
                return did
            }
        }

        try {
            val created = createCapture(context.applicationContext)
            if (created != null) {
                captureState = created
                val did = created.virtualDisplay.display?.displayId
                if (did != null) {
                    Log.i(TAG, "created virtual displayId=$did")
                    activeDisplayId = did
                    overlayRequested = false
                    return did
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "create virtual display failed: ${t.message}")
        }

        val mgr = VirtualDisplayManager(context.applicationContext, adbExecPath)
        overlayRequested = true
        val displayId = mgr.enableOverlayDisplay720p()

        if (displayId == null) {
            overlayRequested = false
            activeDisplayId = null
            return null
        }

        activeDisplayId = displayId
        return displayId
    }

    fun getDisplayId(): Int? = activeDisplayId

    @Synchronized
    fun ensureVirtualDisplayForMonitor(context: Context): Int? {
        val existingCapture = captureState
        if (existingCapture != null) {
            val did = try {
                existingCapture.virtualDisplay.display?.displayId
            } catch (_: Exception) {
                null
            }
            if (did != null) {
                activeDisplayId = did
                overlayRequested = false
                return did
            }
        }

        val created = try {
            createCapture(context.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "createCapture(monitor) failed: ${t.message}")
            null
        }
        if (created != null) {
            captureState = created
            var did: Int? = null
            var attempts = 0
            while (did == null && attempts < 25) {
                did = try {
                    created.virtualDisplay.display?.displayId
                } catch (_: Exception) {
                    null
                }
                if (did != null) break
                try {
                    Thread.sleep(40)
                } catch (_: Exception) {
                }
                attempts++
            }
            if (did != null) {
                activeDisplayId = did
                overlayRequested = false
                return did
            }

            Log.w(TAG, "createCapture(monitor) got null displayId")
        }

        return null
    }

    @Synchronized
    fun startMonitor(context: Context): Boolean {
        val did = ensureVirtualDisplayForMonitor(context) ?: return false
        val cap = captureState ?: return false
        val existing = monitorImageReader
        if (existing != null) return true

        val reader = try {
            ImageReader.newInstance(720, 1280, android.graphics.PixelFormat.RGBA_8888, 2)
        } catch (_: Exception) {
            null
        } ?: return false

        return try {
            cap.virtualDisplay.setSurface(reader.surface)
            monitorImageReader = reader
            activeDisplayId = did
            true
        } catch (_: Exception) {
            try {
                reader.close()
            } catch (_: Exception) {
            }
            false
        }
    }

    fun getMonitorImageReader(): ImageReader? = monitorImageReader

    fun getVirtualDisplayDisplay(): Display? {
        val cap = captureState ?: return null
        return try {
            cap.virtualDisplay.display
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun stopMonitor() {
        val cap = captureState ?: return
        val reader = monitorImageReader ?: return
        monitorImageReader = null

        try {
            reader.setOnImageAvailableListener(null, null)
        } catch (_: Exception) {
        }

        try {
            reader.close()
        } catch (_: Exception) {
        }

        val restored = try {
            ImageReader.newInstance(720, 1280, android.graphics.PixelFormat.RGBA_8888, 3)
        } catch (_: Exception) {
            null
        }

        if (restored != null) {
            try {
                cap.virtualDisplay.setSurface(restored.surface)
                try {
                    cap.imageReader.close()
                } catch (_: Exception) {
                }
                cap.imageReader = restored
            } catch (_: Exception) {
                try {
                    restored.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    @JvmStatic
    fun screenshotPngBase64(): String {
        val cap = captureState ?: return ""
        val targetId = activeDisplayId
        val currentId = try {
            cap.virtualDisplay.display?.displayId
        } catch (_: Exception) {
            null
        }
        if (targetId == null || currentId == null || targetId != currentId) return ""

        var image = try {
            cap.imageReader.acquireLatestImage()
        } catch (_: Exception) {
            null
        }
        var attempts = 0
        while (image == null && attempts < 12) {
            try {
                Thread.sleep(40)
            } catch (_: Exception) {
            }
            image = try {
                cap.imageReader.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            attempts++
        }
        if (image == null) return ""

        return try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bmp = Bitmap.createBitmap(
                width + (rowPadding / pixelStride),
                height,
                Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
            if (cropped != bmp) {
                try {
                    bmp.recycle()
                } catch (_: Exception) {
                }
            }

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG, 100, baos)
            try {
                cropped.recycle()
            } catch (_: Exception) {
            }
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return ""
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
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
        val adbExecPath = try {
            LocalAdb.resolveAdbExecPath(ctx)
        } catch (_: Exception) {
            "adb"
        }

        try {
            releaseCaptureLocked()
        } catch (_: Exception) {
        }

        try {
            VirtualDisplayManager(ctx, adbExecPath).cleanup()
        } catch (_: Exception) {
        }

        activeDisplayId = null
        overlayRequested = false
    }

    fun cleanupOverlayOnlyAsync(context: Context) {
        thread(start = true, name = "VirtualDisplayOverlayOnlyCleanup") {
            cleanupOverlayOnly(context)
        }
    }

    @Synchronized
    fun cleanupOverlayOnly(context: Context) {
        val ctx = context.applicationContext
        val adbExecPath = try {
            LocalAdb.resolveAdbExecPath(ctx)
        } catch (_: Exception) {
            "adb"
        }

        try {
            releaseCaptureLocked()
        } catch (_: Exception) {
        }

        try {
            VirtualDisplayManager(ctx, adbExecPath).cleanup()
        } catch (_: Exception) {
        }

        activeDisplayId = null
        overlayRequested = false
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
        val adbExecPath = try {
            LocalAdb.resolveAdbExecPath(ctx)
        } catch (_: Exception) {
            "adb"
        }

        try {
            releaseCaptureLocked()
        } catch (_: Exception) {
        }

        val hadOverlay = overlayRequested || activeDisplayId != null
        val mgr = VirtualDisplayManager(ctx, adbExecPath)

        try {
            if (hadOverlay) {
                forceStopBestEffort(ctx, adbExecPath)
            }
        } catch (_: Exception) {
        }

        try {
            if (hadOverlay) {
                mgr.cleanup()
            }
        } catch (_: Exception) {
        }

        activeDisplayId = null
        overlayRequested = false
    }

    private fun forceStopBestEffort(context: Context, adbExecPath: String) {
        val dumpsys = LocalAdb.runCommand(
            context,
            LocalAdb.buildAdbCommand(context, adbExecPath, listOf("shell", "dumpsys", "activity", "activities")),
            timeoutMs = 8_000L,
        ).output

        val pkg = tryExtractResumedPackage(dumpsys)
        if (pkg.isNullOrBlank()) return

        val r = LocalAdb.runCommand(
            context,
            LocalAdb.buildAdbCommand(context, adbExecPath, listOf("shell", "am", "force-stop", pkg)),
            timeoutMs = 6_000L,
        )
        android.util.Log.i(TAG, "force-stop $pkg exit=${r.exitCode}")
    }

    private fun tryExtractResumedPackage(text: String): String? {
        val patterns = listOf(
            Regex("ResumedActivity:.*? ([a-zA-Z0-9_.]+)\\/"),
            Regex("mResumedActivity:.*? ([a-zA-Z0-9_.]+)\\/"),
            Regex("topResumedActivity=.*? ([a-zA-Z0-9_.]+)\\/"),
        )
        for (p in patterns) {
            val m = p.find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private const val TAG = "VirtualDisplay"

    private fun createCapture(context: Context): CaptureState? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: run {
            Log.w(TAG, "DisplayManager not available")
            return null
        }
        val width = 720
        val height = 1280
        val densityDpi = try {
            context.resources.displayMetrics.densityDpi
        } catch (_: Exception) {
            320
        }

        val ht = HandlerThread("VirtualDisplayCapture").apply { start() }
        val handler = Handler(ht.looper)

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 3)
        val flags =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val vd = try {
            dm.createVirtualDisplay(
                "AutoGLM-Virtual",
                width,
                height,
                densityDpi,
                reader.surface,
                flags
            )
        } catch (t: Throwable) {
            Log.w(TAG, "createVirtualDisplay failed: ${t.message}")
            null
        } ?: run {
            Log.w(TAG, "createVirtualDisplay returned null")
            try {
                reader.close()
            } catch (_: Exception) {
            }
            try {
                ht.quitSafely()
            } catch (_: Exception) {
            }
            return null
        }

        return CaptureState(
            virtualDisplay = vd,
            imageReader = reader,
            thread = ht,
            handler = handler,
        )
    }

    @Synchronized
    private fun releaseCaptureLocked() {
        val cap = captureState ?: return
        captureState = null
        val monitor = monitorImageReader
        monitorImageReader = null
        try {
            cap.virtualDisplay.release()
        } catch (_: Exception) {
        }
        try {
            cap.imageReader.close()
        } catch (_: Exception) {
        }
        try {
            monitor?.close()
        } catch (_: Exception) {
        }
        try {
            cap.thread.quitSafely()
        } catch (_: Exception) {
        }
    }
}
