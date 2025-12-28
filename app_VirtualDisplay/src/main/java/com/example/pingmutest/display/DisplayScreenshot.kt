package com.example.pingmutest.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.example.pingmutest.shizuku.ShizukuShell
import java.lang.reflect.Modifier

object DisplayScreenshot {

    private const val TAG = "DisplayScreenshot"

    fun screencapPngToFile(
        displayId: Int,
        outputPath: String,
    ): Result<Unit> {
        Log.i(TAG, "capture file requested: displayId=$displayId path=$outputPath")

        val candidates = arrayOf(
            "screencap -p -d $displayId",
            "screencap -d $displayId -p",
        )

        val errors = StringBuilder()

        for (cmd in candidates) {
            val full = "set -e; $cmd > '$outputPath'"
            Log.i(TAG, "fallback to shell capture file: cmd=$full")
            val r = ShizukuShell.execForText(full)
            if (r.isSuccess) {
                return Result.success(Unit)
            }

            val msg = r.exceptionOrNull()?.message ?: "unknown"
            if (errors.isNotEmpty()) errors.append(" | ")
            errors.append(cmd)
            errors.append(" => ")
            errors.append(msg)
        }

        val ex = IllegalStateException(errors.toString().ifBlank { "screencap failed" })
        Log.e(TAG, "screencapPngToFile failed: displayId=$displayId path=$outputPath", ex)
        return Result.failure(ex)
    }

    fun screencapPng(displayId: Int): Result<Bitmap> {
        Log.i(TAG, "capture requested: displayId=$displayId")

        runCatching {
            captureDisplayBitmapBestEffort(displayId)
        }.onFailure { t ->
            Log.w(TAG, "captureDisplayBitmapBestEffort threw: displayId=$displayId", t)
        }.getOrNull()?.let { bmp ->
            Log.i(TAG, "capture ok via framework APIs: displayId=$displayId size=${bmp.width}x${bmp.height}")
            return Result.success(bmp)
        }

        val candidates = arrayOf(
            "screencap -p -d $displayId",
            "screencap -d $displayId -p",
        )

        val errors = StringBuilder()

        for (cmd in candidates) {
            Log.i(TAG, "fallback to shell capture: cmd=$cmd")
            val r = ShizukuShell.execForBytes(cmd)
            if (r.isSuccess) {
                return r.mapCatching { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("Failed to decode PNG")
                }
            }

            val msg = r.exceptionOrNull()?.message ?: "unknown"
            if (errors.isNotEmpty()) errors.append(" | ")
            errors.append(cmd)
            errors.append(" => ")
            errors.append(msg)
        }

        val ex = IllegalStateException(
            errors.toString().ifBlank { "screencap failed" } +
                " (device may not support screencap by displayId)",
        )
        Log.e(TAG, "screencapPng failed: displayId=$displayId", ex)
        return Result.failure(ex)
    }

    private fun captureDisplayBitmapBestEffort(displayId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null

        val bmp = runCatching { captureByAndroidWindowScreenCapture(displayId) }
            .onFailure { t ->
                Log.w(TAG, "captureByAndroidWindowScreenCapture failed: displayId=$displayId", t)
            }
            .getOrNull()

        if (bmp != null) return bmp

        return runCatching { captureBySurfaceControlScreenshot(displayId) }
            .onFailure { t ->
                Log.w(TAG, "captureBySurfaceControlScreenshot failed: displayId=$displayId", t)
            }
            .getOrNull()
    }

    private fun captureByAndroidWindowScreenCapture(displayId: Int): Bitmap? {
        val screenCaptureClass = runCatching { Class.forName("android.window.ScreenCapture") }.getOrNull()
            ?: run {
                Log.d(TAG, "ScreenCapture class not found")
                return null
            }
        val captureDisplayMethod = screenCaptureClass.methods.firstOrNull { m ->
            m.name == "captureDisplay" && Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1
        } ?: return null

        val argsType = captureDisplayMethod.parameterTypes[0]
        val builderClassName = argsType.name + "\$Builder"
        val builderClass = runCatching { Class.forName(builderClassName) }.getOrNull()
            ?: run {
                Log.d(TAG, "DisplayCaptureArgs.Builder not found: $builderClassName")
                return null
            }

        val displayToken = getDisplayTokenBestEffort(displayId)
        Log.d(TAG, "ScreenCapture: displayId=$displayId token=${displayToken?.javaClass?.name ?: "null"}")

        val builder = builderClass.constructors.firstNotNullOfOrNull { ctor ->
            val p = ctor.parameterTypes
            when {
                p.size == 1 && p[0].name == "android.os.IBinder" && displayToken != null -> ctor.newInstance(displayToken)
                p.size == 1 && p[0] == Int::class.javaPrimitiveType -> ctor.newInstance(displayId)
                else -> null
            }
        } ?: return null

        val buildMethod = builderClass.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }
            ?: return null
        val args = buildMethod.invoke(builder)

        val screenshotBuffer = runCatching { captureDisplayMethod.invoke(null, args) }.getOrNull()
            ?: run {
                Log.d(TAG, "ScreenCapture.captureDisplay returned null")
                return null
            }

        val asBitmap = screenshotBuffer.javaClass.methods.firstOrNull { m ->
            m.name == "asBitmap" && m.parameterTypes.isEmpty() && m.returnType == Bitmap::class.java
        }
        if (asBitmap != null) {
            return asBitmap.invoke(screenshotBuffer) as? Bitmap
        }

        val hwBufferGetter = screenshotBuffer.javaClass.methods.firstOrNull { m ->
            m.name == "getHardwareBuffer" && m.parameterTypes.isEmpty()
        }
        val colorSpaceGetter = screenshotBuffer.javaClass.methods.firstOrNull { m ->
            m.name == "getColorSpace" && m.parameterTypes.isEmpty()
        }
        if (hwBufferGetter != null && colorSpaceGetter != null) {
            val hardwareBuffer = hwBufferGetter.invoke(screenshotBuffer) as? android.hardware.HardwareBuffer ?: return null
            val colorSpace = colorSpaceGetter.invoke(screenshotBuffer) as? android.graphics.ColorSpace
            return Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
        }

        return null
    }

    private fun getDisplayTokenBestEffort(displayId: Int): Any? {
        val tokenFromSurfaceControl = runCatching {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val getDisplayToken = surfaceControlClass.methods.firstOrNull { m ->
                m.name == "getDisplayToken" && Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching null
            getDisplayToken.invoke(null, displayId)
        }.getOrNull()

        if (tokenFromSurfaceControl != null) {
            Log.d(TAG, "getDisplayTokenBestEffort: token from SurfaceControl")
            return tokenFromSurfaceControl
        }

        val tokenFromDmg = runCatching {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val getInstance = dmgClass.methods.firstOrNull { m ->
                m.name == "getInstance" && Modifier.isStatic(m.modifiers) && m.parameterTypes.isEmpty()
            } ?: return@runCatching null
            val dmg = getInstance.invoke(null) ?: return@runCatching null
            val getDisplayToken = dmgClass.methods.firstOrNull { m ->
                m.name == "getDisplayToken" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching null
            getDisplayToken.invoke(dmg, displayId)
        }.getOrNull()

        if (tokenFromDmg != null) {
            Log.d(TAG, "getDisplayTokenBestEffort: token from DisplayManagerGlobal")
        }
        return tokenFromDmg
    }

    private fun captureBySurfaceControlScreenshot(displayId: Int): Bitmap? {
        val surfaceControlClass = runCatching { Class.forName("android.view.SurfaceControl") }.getOrNull()
            ?: run {
                Log.d(TAG, "SurfaceControl class not found")
                return null
            }
        val getDisplayToken = surfaceControlClass.methods.firstOrNull { m ->
            m.name == "getDisplayToken" && Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null

        val displayToken = getDisplayToken.invoke(null, displayId) as? android.os.IBinder
            ?: run {
                Log.d(TAG, "SurfaceControl.getDisplayToken returned null: displayId=$displayId")
                return null
            }

        val screenshotMethod = surfaceControlClass.methods.firstOrNull { m ->
            m.name == "screenshot" && Modifier.isStatic(m.modifiers) && m.parameterTypes.isNotEmpty() &&
                m.parameterTypes[0].name == "android.os.IBinder"
        } ?: return null

        val params = screenshotMethod.parameterTypes
        val args: Array<Any?> = when (params.size) {
            1 -> arrayOf(displayToken)
            3 -> arrayOf(displayToken, 0, 0)
            else -> return null
        }

        val result = screenshotMethod.invoke(null, *args) ?: return null
        if (result is Bitmap) return result

        val asBitmap = result.javaClass.methods.firstOrNull { m ->
            m.name == "asBitmap" && m.parameterTypes.isEmpty() && m.returnType == Bitmap::class.java
        }
        return asBitmap?.invoke(result) as? Bitmap
    }
}
