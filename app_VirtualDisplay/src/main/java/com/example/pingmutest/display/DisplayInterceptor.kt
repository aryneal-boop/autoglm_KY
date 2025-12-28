package com.example.pingmutest.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DisplayInterceptor(
    private val context: Context,
    private val callback: Callback,
) {

    private companion object {
        private const val TAG = "DisplayInterceptor"
        private const val DISPLAY_TYPE_OVERLAY = 4
        private const val DISPLAY_TYPE_VIRTUAL = 5
    }

    interface Callback {
        fun onVirtualDisplayAdded(displayId: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager: DisplayManager =
        context.getSystemService(DisplayManager::class.java)

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private val reportedDisplayIds = HashSet<Int>()

    private fun isVirtualDisplay(display: android.view.Display): Boolean {
        return getDisplayTypeBestEffort(display) == DISPLAY_TYPE_VIRTUAL
    }

    private fun isOverlayDisplay(display: android.view.Display): Boolean {
        val name = runCatching { display.name }.getOrNull().orEmpty()
        val nameHint = name.contains("overlay", ignoreCase = true)
        val type = getDisplayTypeBestEffort(display)
        return (type == DISPLAY_TYPE_OVERLAY) || nameHint
    }

    private fun getDisplayTypeBestEffort(display: android.view.Display): Int? {
        return try {
            val getTypeMethod = android.view.Display::class.java.getMethod("getType")
            getTypeMethod.invoke(display) as Int
        } catch (_: Throwable) {
            Log.e(TAG, "getDisplayTypeBestEffort failed")
            null
        }
    }

    private fun isTargetDisplay(display: android.view.Display): Boolean {
        val name = runCatching { display.name }.getOrNull()
        return isVirtualDisplay(display) ||
            isOverlayDisplay(display) ||
            (display.displayId != android.view.Display.DEFAULT_DISPLAY && (name?.contains("overlay", true) == true))
    }

    private fun reportIfTarget(display: android.view.Display, source: String) {
        val id = display.displayId
        if (!isTargetDisplay(display)) return
        synchronized(reportedDisplayIds) {
            if (!reportedDisplayIds.add(id)) return
        }
        Log.e(TAG, "reportIfTarget: source=$source id=$id name=${runCatching { display.name }.getOrNull()} type=${getDisplayTypeBestEffort(display)}")
        mainHandler.post {
            callback.onVirtualDisplayAdded(id)
        }
    }

    private fun scanExistingDisplays(source: String) {
        val displays = try {
            displayManager.displays
        } catch (t: Throwable) {
            Log.e(TAG, "scanExistingDisplays failed: source=$source", t)
            return
        }

        for (d in displays) {
            val name = runCatching { d.name }.getOrNull()
            val type = getDisplayTypeBestEffort(d)
            Log.e(TAG, "scan: source=$source id=${d.displayId} name=$name type=$type")
            reportIfTarget(d, source)
        }
    }

    private fun shizukuNewProcess(command: Array<String>): Any {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null)
    }

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
            val name = runCatching { display.name }.getOrNull()
            val type = getDisplayTypeBestEffort(display)
            Log.e(TAG, "onDisplayAdded: id=$displayId name=$name type=$type")
            reportIfTarget(display, "onDisplayAdded")
        }

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    fun start() {
        displayManager.registerDisplayListener(listener, mainHandler)
        scanExistingDisplays("start")
    }

    fun stop() {
        displayManager.unregisterDisplayListener(listener)
        worker.shutdownNow()
    }

    fun enableOverlayDisplayByShizuku(
        width: Int = 1080,
        height: Int = 1920,
        densityDpi: Int = 440,
        onResult: (success: Boolean, message: String?) -> Unit,
    ) {
        val spec = "${width}x${height}/${densityDpi}"
        val command = "settings put global overlay_display_devices $spec"

        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "enableOverlayDisplayByShizuku failed: Shizuku binder not ready")
            onResult(false, "Shizuku binder is not ready")
            return
        }

        val hasPermission = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Log.e(TAG, "enableOverlayDisplayByShizuku failed: checkSelfPermission error", t)
            false
        }

        if (!hasPermission) {
            Log.e(TAG, "enableOverlayDisplayByShizuku failed: Shizuku permission not granted")
            onResult(false, "Shizuku permission not granted")
            return
        }

        worker.execute {
            try {
                val process = shizukuNewProcess(arrayOf("sh", "-c", command))
                val processClass = process.javaClass
                val inputStream = processClass.getMethod("getInputStream").invoke(process) as java.io.InputStream
                val errorStream = processClass.getMethod("getErrorStream").invoke(process) as java.io.InputStream
                val stdout = BufferedReader(InputStreamReader(inputStream)).readText()
                val stderr = BufferedReader(InputStreamReader(errorStream)).readText()
                val exitCode = processClass.getMethod("waitFor").invoke(process) as Int

                if (exitCode == 0) {
                    runCatching {
                        val verifyCmd = "settings get global overlay_display_devices"
                        val p2 = shizukuNewProcess(arrayOf("sh", "-c", verifyCmd))
                        val p2c = p2.javaClass
                        val in2 = p2c.getMethod("getInputStream").invoke(p2) as java.io.InputStream
                        val out2 = BufferedReader(InputStreamReader(in2)).readText().trim()
                        val code2 = p2c.getMethod("waitFor").invoke(p2) as Int
                        Log.e(TAG, "overlay_display_devices verify: exit=$code2 value=$out2")
                    }.onFailure { t ->
                        Log.e(TAG, "overlay_display_devices verify failed", t)
                    }
                }

                mainHandler.post {
                    if (exitCode == 0) {
                        onResult(true, stdout.ifBlank { null })
                        mainHandler.postDelayed({
                            scanExistingDisplays("enableOverlayDisplayByShizuku")
                        }, 500)
                    } else {
                        val msg = (stderr.ifBlank { stdout }).ifBlank { "exitCode=$exitCode" }
                        onResult(false, msg)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "enableOverlayDisplayByShizuku failed", t)
                mainHandler.post {
                    onResult(false, t.message)
                }
            }
        }
    }
}
