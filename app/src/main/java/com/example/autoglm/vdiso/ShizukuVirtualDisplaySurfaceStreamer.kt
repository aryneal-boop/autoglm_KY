package com.example.autoglm.vdiso

import android.os.Binder
import android.os.Build
import android.os.IInterface
import android.util.Log
import android.view.Surface
import java.lang.reflect.Proxy

/**
 * VirtualDisplay -> Surface 直出预览（调试/旧链路）。
 *
 * **用途**
 * - 通过 Shizuku 反射创建 VirtualDisplay，并把输出直接绑定到外部传入的 [Surface]（例如 `SurfaceView`）。
 * - 主要用于调试/验证虚拟屏是否能正常渲染到某个 Surface。
 *
 * **与 [ShizukuVirtualDisplayEngine] 的区别**
 * - 本类更“直通”：VirtualDisplay 直接输出到传入 Surface，不经过 GL 分发与离屏截图缓存。
 * - [ShizukuVirtualDisplayEngine] 提供完整的“截图 + 预览切换”能力，更适合主流程。
 *
 * **引用路径（常见）**
 * - `MonitorActivity`：调试页面使用该类把虚拟屏输出渲染到 `SurfaceView`。
 *
 * **使用注意事项**
 * - 依赖 Shizuku 权限与系统服务反射：不同 ROM 可能失败。
 * - 该类持有外部 Surface：调用方需自行保证生命周期（例如 Surface destroyed 时停止）。
 */
object ShizukuVirtualDisplaySurfaceStreamer {

    private const val TAG = "VdIsoSurface"

    data class Args(
        val name: String = "AutoGLM-Virtual-Surface",
        val width: Int = 1080,
        val height: Int = 1920,
        val dpi: Int = 142,
        val refreshRate: Float = 0f,
        val rotatesWithContent: Boolean = false,
        val ownerPackage: String = "com.android.shell",
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

            val packageName = args.ownerPackage

            val flags = buildFlags(rotatesWithContent = args.rotatesWithContent)
            val trustedFlag = getVirtualDisplayFlagBestEffort("VIRTUAL_DISPLAY_FLAG_TRUSTED", fallback = (1 shl 10))
            val flagsWithoutTrusted = if (trustedFlag != null) (flags and trustedFlag.inv()) else flags
            val id = try {
                val config = buildVirtualDisplayConfig(args, surface, flags)
                (createMethod.invoke(displayManager, config, callback, null, packageName) as Int)
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                if (cause is SecurityException && trustedFlag != null && (flags and trustedFlag) != 0) {
                    Log.w(TAG, "createVirtualDisplay with TRUSTED failed, retry without TRUSTED", cause)
                    val config = buildVirtualDisplayConfig(args, surface, flagsWithoutTrusted)
                    (createMethod.invoke(displayManager, config, callback, null, packageName) as Int)
                } else {
                    throw t
                }
            }

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
        val publicFlag = getVirtualDisplayFlagBestEffort("VIRTUAL_DISPLAY_FLAG_PUBLIC", fallback = null) ?: 0
        val ownContentOnlyFlag = getVirtualDisplayFlagBestEffort("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", fallback = null) ?: 0
        val presentationFlag = getVirtualDisplayFlagBestEffort("VIRTUAL_DISPLAY_FLAG_PRESENTATION", fallback = (1 shl 1)) ?: 0
        val trustedFlag = getVirtualDisplayFlagBestEffort("VIRTUAL_DISPLAY_FLAG_TRUSTED", fallback = (1 shl 10))

        var flags = publicFlag or ownContentOnlyFlag

        flags = flags or presentationFlag
        if (trustedFlag != null) {
            flags = flags or trustedFlag
        }

        // supports-touch (hidden in some versions)
        flags = flags or (1 shl 6)
        // destroy-content-on-removal
        flags = flags or (1 shl 8)

        if (rotatesWithContent) {
            flags = flags or (1 shl 7)
        }

        if (Build.VERSION.SDK_INT >= 33) {
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

    private fun getVirtualDisplayFlagBestEffort(name: String, fallback: Int?): Int? {
        return runCatching {
            val dm = Class.forName("android.hardware.display.DisplayManager")
            dm.getField(name).getInt(null)
        }.getOrElse { fallback }
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
