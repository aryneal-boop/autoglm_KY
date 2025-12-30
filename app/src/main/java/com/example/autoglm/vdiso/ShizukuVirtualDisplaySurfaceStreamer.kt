package com.example.autoglm.vdiso

import android.os.Binder
import android.os.Build
import android.os.IInterface
import android.util.Log
import android.view.Surface
import java.lang.reflect.Proxy

object ShizukuVirtualDisplaySurfaceStreamer {

    private const val TAG = "VdIsoSurface"

    data class Args(
        val name: String = "AutoGLM-Virtual-Surface",
        val width: Int = 1080,
        val height: Int = 1920,
        val dpi: Int = 440,
        val refreshRate: Float = 0f,
        val rotatesWithContent: Boolean = false,
    )

    @Volatile
    private var displayId: Int? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var vdCallback: Any? = null

    @Synchronized
    fun start(args: Args, surface: Surface): Result<Int> {
        return runCatching {
            val existing = displayId
            if (existing != null && this.surface != null && vdCallback != null) {
                Log.i(TAG, "start ignored (already started): displayId=$existing")
                return@runCatching existing
            }

            stop()
            this.surface = surface

            val flags = buildFlags(rotatesWithContent = args.rotatesWithContent)
            val config = buildVirtualDisplayConfig(args, surface, flags)
            val callback = createVirtualDisplayCallbackProxy()

            val displayManager = ShizukuServiceHub.getDisplayManager()
            val callbackInterfaceClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val createMethod = displayManager.javaClass.methods.firstOrNull { m ->
                m.name == "createVirtualDisplay" &&
                    m.parameterTypes.size == 4 &&
                    m.parameterTypes[0].name == "android.hardware.display.VirtualDisplayConfig" &&
                    (callbackInterfaceClass.isAssignableFrom(m.parameterTypes[1]) || IInterface::class.java.isAssignableFrom(m.parameterTypes[1])) &&
                    m.parameterTypes[2].name == "android.media.projection.IMediaProjection" &&
                    m.parameterTypes[3] == String::class.java
            } ?: throw NoSuchMethodException("IDisplayManager.createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String)")

            val packageName = "com.android.shell"
            val id = (createMethod.invoke(displayManager, config, callback, null, packageName) as Int)

            vdCallback = callback
            displayId = id
            Log.i(TAG, "started: displayId=$id size=${args.width}x${args.height}")
            id
        }
    }

    fun isStarted(): Boolean = displayId != null && surface != null && vdCallback != null

    fun getDisplayId(): Int? = displayId

    @Synchronized
    fun updateSurface(surface: Surface): Result<Unit> {
        val cb = vdCallback ?: return Result.failure(IllegalStateException("No virtual display callback"))
        return setVirtualDisplaySurfaceBestEffort(cb, surface)
            .onSuccess {
                this.surface = surface
                Log.i(TAG, "updateSurface ok: displayId=$displayId")
            }
    }

    @Synchronized
    fun destroy(): Result<Unit> {
        val cb = vdCallback ?: return Result.failure(IllegalStateException("No virtual display callback"))
        return releaseVirtualDisplayBestEffort(cb)
            .onSuccess {
                Log.i(TAG, "destroy ok: displayId=$displayId")
                stop()
            }
    }

    @Synchronized
    fun stop() {
        surface = null
        displayId = null
        vdCallback = null
    }

    private fun buildFlags(rotatesWithContent: Boolean): Int {
        val dm = Class.forName("android.hardware.display.DisplayManager")
        val publicFlag = dm.getField("VIRTUAL_DISPLAY_FLAG_PUBLIC").getInt(null)
        val ownContentOnlyFlag = dm.getField("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY").getInt(null)

        var flags = publicFlag or ownContentOnlyFlag

        // supports-touch (hidden in some versions)
        flags = flags or (1 shl 6)
        // destroy-content-on-removal
        flags = flags or (1 shl 8)

        if (rotatesWithContent) {
            flags = flags or (1 shl 7)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            flags = flags or (1 shl 10)
            flags = flags or (1 shl 11)
            flags = flags or (1 shl 12)
            flags = flags or (1 shl 13)
            if (Build.VERSION.SDK_INT >= 34) {
                flags = flags or (1 shl 14)
                flags = flags or (1 shl 15)
            }
        }

        return flags
    }

    private fun buildVirtualDisplayConfig(args: Args, surface: Surface, flags: Int): Any {
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
            m.name == "setRequestedRefreshRate" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Float::class.javaPrimitiveType
        }
        if (setRequestedRefreshRate != null) {
            setRequestedRefreshRate.invoke(builder, args.refreshRate)
        }

        return builderClass.getMethod("build").invoke(builder)
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

    private fun releaseVirtualDisplayBestEffort(callback: Any): Result<Unit> {
        return runCatching {
            val displayManager = ShizukuServiceHub.getDisplayManager()

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
                            return@runCatching
                        }
                        p.size == 1 && p[0].name == "android.hardware.display.IVirtualDisplayCallback" -> {
                            m.invoke(displayManager, callback)
                            return@runCatching
                        }
                        p.size == 1 && p[0].name == "android.os.IBinder" && binder != null -> {
                            m.invoke(displayManager, binder)
                            return@runCatching
                        }
                        p.size == 1 && IInterface::class.java.isAssignableFrom(p[0]) -> {
                            m.invoke(displayManager, callback)
                            return@runCatching
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "releaseVirtualDisplay failed on ${m.name}", t)
                }
            }
            throw IllegalStateException("No release method matched")
        }
    }

    private fun setVirtualDisplaySurfaceBestEffort(callback: Any, surface: Surface): Result<Unit> {
        return runCatching {
            val displayManager = ShizukuServiceHub.getDisplayManager()

            val cbInterface = runCatching { Class.forName("android.hardware.display.IVirtualDisplayCallback") }.getOrNull()
            val asBinder = callback.javaClass.methods.firstOrNull { m ->
                m.name == "asBinder" && m.parameterTypes.isEmpty() && m.returnType.name == "android.os.IBinder"
            }
            val binder = asBinder?.invoke(callback)

            val candidates = displayManager.javaClass.methods.filter { m ->
                m.name == "setVirtualDisplaySurface" || m.name == "setVirtualDisplaySurfaceAsync"
            }

            for (m in candidates) {
                try {
                    val p = m.parameterTypes
                    when {
                        p.size == 2 && cbInterface != null && p[0].isAssignableFrom(cbInterface) && p[1] == Surface::class.java -> {
                            m.invoke(displayManager, callback, surface)
                            return@runCatching
                        }
                        p.size == 2 && p[0].name == "android.hardware.display.IVirtualDisplayCallback" && p[1] == Surface::class.java -> {
                            m.invoke(displayManager, callback, surface)
                            return@runCatching
                        }
                        p.size == 2 && p[0].name == "android.os.IBinder" && p[1] == Surface::class.java && binder != null -> {
                            m.invoke(displayManager, binder, surface)
                            return@runCatching
                        }
                        p.size == 2 && IInterface::class.java.isAssignableFrom(p[0]) && p[1] == Surface::class.java -> {
                            m.invoke(displayManager, callback, surface)
                            return@runCatching
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "setVirtualDisplaySurface failed on ${m.name}", t)
                }
            }
            throw IllegalStateException("No setVirtualDisplaySurface method matched")
        }
    }
}
