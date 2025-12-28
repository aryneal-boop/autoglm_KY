package com.example.pingmutest.display

import android.os.Build
import android.os.IInterface
import android.os.Binder
import android.util.Log
import android.view.Surface
import com.example.pingmutest.shizuku.ShizukuServiceManager
import java.lang.reflect.Proxy

object ShizukuVirtualDisplayCreator {

    private const val TAG = "ShizukuVdc"

    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14

    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    data class Args(
        val name: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val refreshRate: Float = 0f,
        val rotatesWithContent: Boolean = false,
    )

    data class CreateResult(
        val displayId: Int,
        val callback: Any,
    )

    fun buildFlags(
        ownContentOnly: Boolean,
        rotatesWithContent: Boolean,
    ): Int {
        var flags = VIRTUAL_DISPLAY_FLAG_PUBLIC or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

        if (ownContentOnly) {
            // keep param for compatibility
        }
        if (rotatesWithContent) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
        }

        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_TRUSTED or
                VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED

            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                flags = flags or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
            }
        }

        return flags
    }

    fun createVirtualDisplayId(
        args: Args,
        surface: Surface,
        ownContentOnly: Boolean = true,
    ): Int {
        return createVirtualDisplay(args, surface, ownContentOnly).displayId
    }

    fun createVirtualDisplay(
        args: Args,
        surface: Surface,
        ownContentOnly: Boolean = true,
    ): CreateResult {
        val displayManager = ShizukuServiceManager.getDisplayManager()

        val flags = buildFlags(
            ownContentOnly = ownContentOnly,
            rotatesWithContent = args.rotatesWithContent,
        )

        val config = buildVirtualDisplayConfig(args, surface, flags)
        val callback = createVirtualDisplayCallbackProxy()
        val packageName = "com.android.shell"
        val callbackInterfaceClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")

        val createMethod = displayManager.javaClass.methods.firstOrNull { m ->
            m.name == "createVirtualDisplay" &&
                m.parameterTypes.size == 4 &&
                m.parameterTypes[0].name == "android.hardware.display.VirtualDisplayConfig" &&
                (callbackInterfaceClass.isAssignableFrom(m.parameterTypes[1]) || IInterface::class.java.isAssignableFrom(m.parameterTypes[1])) &&
                m.parameterTypes[2].name == "android.media.projection.IMediaProjection" &&
                m.parameterTypes[3] == String::class.java
        } ?: throw NoSuchMethodException(
            "IDisplayManager.createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String)",
        )

        val displayId = (createMethod.invoke(displayManager, config, callback, null, packageName) as Int)
        Log.i(TAG, "createVirtualDisplay: displayId=$displayId flags=$flags")
        return CreateResult(displayId = displayId, callback = callback)
    }

    fun releaseVirtualDisplayBestEffort(callback: Any): Result<Unit> {
        return runCatching {
            val displayManager = ShizukuServiceManager.getDisplayManager()

            val cbInterface = runCatching { Class.forName("android.hardware.display.IVirtualDisplayCallback") }.getOrNull()
            val asBinder = callback.javaClass.methods.firstOrNull { m ->
                m.name == "asBinder" && m.parameterTypes.isEmpty() && m.returnType.name == "android.os.IBinder"
            }
            val binder = asBinder?.invoke(callback)

            val candidates = displayManager.javaClass.methods.filter { m ->
                val n = m.name
                n == "releaseVirtualDisplay" || n == "destroyVirtualDisplay" || n == "removeVirtualDisplay"
            }

            for (m in candidates) {
                try {
                    val p = m.parameterTypes
                    when {
                        p.size == 1 && cbInterface != null && p[0].isAssignableFrom(cbInterface) -> {
                            m.invoke(displayManager, callback)
                            Log.i(TAG, "releaseVirtualDisplayBestEffort ok via ${m.name}(IVirtualDisplayCallback)")
                            return@runCatching
                        }
                        p.size == 1 && p[0].name == "android.hardware.display.IVirtualDisplayCallback" -> {
                            m.invoke(displayManager, callback)
                            Log.i(TAG, "releaseVirtualDisplayBestEffort ok via ${m.name}(IVirtualDisplayCallback)")
                            return@runCatching
                        }
                        p.size == 1 && p[0].name == "android.os.IBinder" && binder != null -> {
                            m.invoke(displayManager, binder)
                            Log.i(TAG, "releaseVirtualDisplayBestEffort ok via ${m.name}(IBinder)")
                            return@runCatching
                        }
                        p.size == 1 && IInterface::class.java.isAssignableFrom(p[0]) -> {
                            m.invoke(displayManager, callback)
                            Log.i(TAG, "releaseVirtualDisplayBestEffort ok via ${m.name}(IInterface)")
                            return@runCatching
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "releaseVirtualDisplayBestEffort failed on ${m.name}", t)
                }
            }

            throw NoSuchMethodException("No usable release/destroy/removeVirtualDisplay method")
        }
    }

    fun setVirtualDisplaySurfaceBestEffort(
        callback: Any,
        surface: Surface,
    ): Result<Unit> {
        return runCatching {
            val displayManager = ShizukuServiceManager.getDisplayManager()

            val cbInterface = runCatching { Class.forName("android.hardware.display.IVirtualDisplayCallback") }.getOrNull()
            val asBinder = callback.javaClass.methods.firstOrNull { m ->
                m.name == "asBinder" && m.parameterTypes.isEmpty() && m.returnType.name == "android.os.IBinder"
            }
            val binder = asBinder?.invoke(callback)

            val candidates = displayManager.javaClass.methods.filter { m ->
                val n = m.name
                n == "setVirtualDisplaySurface" || n == "setVirtualDisplay" || n == "setVirtualDisplayState"
            }

            for (m in candidates) {
                try {
                    val p = m.parameterTypes
                    when {
                        p.size == 2 && cbInterface != null && p[0].isAssignableFrom(cbInterface) && p[1] == Surface::class.java -> {
                            m.invoke(displayManager, callback, surface)
                            Log.i(TAG, "setVirtualDisplaySurfaceBestEffort ok via ${m.name}(IVirtualDisplayCallback, Surface)")
                            return@runCatching
                        }
                        p.size == 2 && p[0].name == "android.hardware.display.IVirtualDisplayCallback" && p[1] == Surface::class.java -> {
                            m.invoke(displayManager, callback, surface)
                            Log.i(TAG, "setVirtualDisplaySurfaceBestEffort ok via ${m.name}(IVirtualDisplayCallback, Surface)")
                            return@runCatching
                        }
                        p.size == 2 && p[0].name == "android.os.IBinder" && p[1] == Surface::class.java && binder != null -> {
                            m.invoke(displayManager, binder, surface)
                            Log.i(TAG, "setVirtualDisplaySurfaceBestEffort ok via ${m.name}(IBinder, Surface)")
                            return@runCatching
                        }
                        p.size == 2 && IInterface::class.java.isAssignableFrom(p[0]) && p[1] == Surface::class.java -> {
                            m.invoke(displayManager, callback, surface)
                            Log.i(TAG, "setVirtualDisplaySurfaceBestEffort ok via ${m.name}(IInterface, Surface)")
                            return@runCatching
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "setVirtualDisplaySurfaceBestEffort failed on ${m.name}", t)
                }
            }

            throw NoSuchMethodException("No usable setVirtualDisplaySurface method")
        }
    }

    private fun createVirtualDisplayCallbackProxy(): Any {
        val callbackInterfaceClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val binder = Binder()
        return Proxy.newProxyInstance(
            callbackInterfaceClass.classLoader,
            arrayOf(callbackInterfaceClass, IInterface::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> binder
                else -> Unit
            }
        }
    }

    private fun buildVirtualDisplayConfig(
        args: Args,
        surface: Surface,
        flags: Int,
    ): Any {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val ctor = builderClass.getConstructor(
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val builder = ctor.newInstance(args.name, args.width, args.height, args.dpi)

        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)

        val setRequestedRefreshRate = builderClass.methods.firstOrNull { m ->
            m.name == "setRequestedRefreshRate" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == Float::class.javaPrimitiveType
        }
        if (setRequestedRefreshRate != null) {
            setRequestedRefreshRate.invoke(builder, args.refreshRate)
        }

        return builderClass.getMethod("build").invoke(builder)
    }
}
