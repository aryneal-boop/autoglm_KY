package com.example.autoglm.vdiso

import android.graphics.Bitmap
import android.view.Surface
import android.os.Binder
import android.os.Build
import android.os.IInterface
import android.util.Log
import java.lang.reflect.Proxy

object ShizukuVirtualDisplayEngine {

    private const val TAG = "VdIsoEngine"

    data class Args(
        val name: String = "AutoGLM-Virtual",
        val width: Int = 1080,
        val height: Int = 1920,
        val dpi: Int = 440,
        val refreshRate: Float = 0f,
        val rotatesWithContent: Boolean = false,
        val ownerPackage: String = "com.android.shell",
    )

    @Volatile
    private var displayId: Int? = null

    @Volatile
    private var vdCallback: Any? = null

    @Volatile
    private var currentOutputSurface: Surface? = null

    @Volatile
    private var glDispatcher: VdGlFrameDispatcher? = null

    @Volatile
    private var latestContentWidth: Int = 0

    @Volatile
    private var latestContentHeight: Int = 0

    private val frameLock = Any()

    @Volatile
    private var stopping: Boolean = false

    private fun setVirtualDisplaySurfaceBestEffort(callback: Any, surface: Surface): Result<Unit> {
        // 通过 IDisplayManager.setVirtualDisplaySurface*(...) 反射切换 VirtualDisplay 的输出 Surface。
        // 用途：
        // - 工具箱展开（虚拟隔离模式）时把输出切到 SurfaceView.surface，实现 0 bitmap 的直出预览。
        // - 工具箱收起/切换回主屏模式时把输出切回 ImageReader.surface，便于继续做虚拟屏截图/识别。
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

    @Synchronized
    fun ensureStarted(args: Args = Args()): Result<Int> {
        // 入口：确保虚拟屏已启动。
        // 注意：VirtualDisplay 启动时默认输出到 ImageReader.surface，用于缓存帧（截图/识别）。
        // 当需要 0 bitmap 预览时，外部会调用 setOutputSurface(surfaceViewSurface) 临时切换输出。
        val existing = displayId
        if (existing != null && glDispatcher != null && vdCallback != null) {
            return Result.success(existing)
        }
        return start(args)
    }

    @Synchronized
    fun start(args: Args = Args()): Result<Int> {
        // 启动 Shizuku VirtualDisplay，并用 ImageReader 接收 RGBA 帧。
        // 帧接收线程在 HandlerThread 上运行，避免阻塞主线程。
        return runCatching {
            stop()

            stopping = false

            val dispatcher = VdGlFrameDispatcher()
            dispatcher.start(args.width, args.height)
            glDispatcher = dispatcher

            // 等待 GL 初始化完成，拿到 VirtualDisplay 的输入 Surface（SurfaceTexture）。
            val deadline = android.os.SystemClock.uptimeMillis() + 1500L
            var input: Surface? = null
            while (android.os.SystemClock.uptimeMillis() <= deadline) {
                input = dispatcher.getInputSurface()
                if (input != null && input.isValid) break
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            val inputSurface = input ?: throw IllegalStateException("GL input surface not ready")
            currentOutputSurface = inputSurface

            // 在第一帧到达前，用创建时的目标分辨率作为内容尺寸兜底。
            synchronized(frameLock) {
                latestContentWidth = args.width
                latestContentHeight = args.height
            }

            val created = createVirtualDisplay(args, inputSurface)
            vdCallback = created.second
            displayId = created.first

            Log.i(TAG, "started: displayId=${displayId} size=${args.width}x${args.height}")
            displayId ?: throw IllegalStateException("displayId is null")
        }
    }

    fun getDisplayId(): Int? = displayId

    fun isStarted(): Boolean = displayId != null && glDispatcher != null && vdCallback != null

    fun getLatestFrameTimeMs(): Long = glDispatcher?.getLatestFrameTimeMs() ?: 0L

    fun getLatestContentSize(): Pair<Int, Int> {
        // latestBitmap 可能是带 rowStride padding 的“padded bitmap”。
        // 这里返回的 content size 才是虚拟屏真实内容宽高，用于 UI 比例计算/裁剪绘制。
        val d = glDispatcher
        if (d != null) return d.getContentSize()
        val (w, h) = synchronized(frameLock) { latestContentWidth to latestContentHeight }
        return w to h
    }

    fun captureLatestBitmap(): Result<Bitmap> {
        val d = glDispatcher ?: return Result.failure(IllegalStateException("GL dispatcher not started"))
        val bmp = d.captureBitmapBlocking() ?: return Result.failure(IllegalStateException("No captured frame"))
        return Result.success(bmp)
    }

    @Synchronized
    fun stop() {
        stopping = true

        val dispatcher = glDispatcher
        glDispatcher = null
        runCatching { dispatcher?.stop() }

        synchronized(frameLock) {
            // reset timestamps/size hints
            latestContentWidth = 0
            latestContentHeight = 0
        }

        val cb = vdCallback
        vdCallback = null
        displayId = null

        currentOutputSurface = null

        if (cb != null) {
            runCatching { releaseVirtualDisplayBestEffort(cb) }
        }
    }

    @Synchronized
    fun setOutputSurface(surface: Surface): Result<Unit> {
        // OpenGL 分发架构下：VirtualDisplay 固定输出到中转 SurfaceTexture。
        // 这里的 setOutputSurface 仅用于设置“预览输出目标”（悬浮窗 TextureView 的 Surface）。
        val d = glDispatcher ?: return Result.failure(IllegalStateException("GL dispatcher not started"))
        if (stopping) return Result.failure(IllegalStateException("Engine stopping"))
        d.setPreviewSurface(surface)
        return Result.success(Unit)
    }

    @Synchronized
    fun restoreOutputSurfaceToImageReader(): Result<Unit> {
        // OpenGL 分发架构下：截图走 dispatcher 的离屏 ImageReader，不需要切换 VirtualDisplay 输出。
        val d = glDispatcher ?: return Result.failure(IllegalStateException("GL dispatcher not started"))
        d.setPreviewSurface(null)
        return Result.success(Unit)
    }

    fun ensureFocusedDisplay(targetDisplayId: Int): Result<Unit> {
        return runCatching {
            val inputManager = ShizukuServiceHub.getInputManager()
            val cls = inputManager.javaClass

            val getFocused = cls.methods.firstOrNull { m ->
                m.name == "getFocusedDisplayId" && m.parameterTypes.isEmpty()
            }
            val setFocused = cls.methods.firstOrNull { m ->
                m.name == "setFocusedDisplay" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
            }

            if (setFocused == null) return@runCatching

            if (getFocused != null) {
                val current = runCatching { getFocused.invoke(inputManager) as? Int }.getOrNull()
                if (current != null && current == targetDisplayId) return@runCatching
            }

            setFocused.invoke(inputManager, targetDisplayId)
        }.onFailure { t ->
            Log.w(TAG, "ensureFocusedDisplay failed: target=$targetDisplayId", t)
        }
    }

    private fun createVirtualDisplay(args: Args, surface: Surface): Pair<Int, Any> {
        val displayManager = ShizukuServiceHub.getDisplayManager()

        val flags = buildFlags(rotatesWithContent = args.rotatesWithContent)
        val config = buildVirtualDisplayConfig(args, surface, flags)
        val callback = createVirtualDisplayCallbackProxy()

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
        val id = (createMethod.invoke(displayManager, config, callback, null, packageName) as Int)
        Log.i(TAG, "createVirtualDisplay ok: displayId=$id flags=$flags")
        return id to callback
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
            // trusted / own display group / always unlocked / touch feedback disabled
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

        builderClass.getMethod("setSurface", android.view.Surface::class.java).invoke(builder, surface)
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

    private fun releaseVirtualDisplayBestEffort(callback: Any) {
        val displayManager = runCatching { ShizukuServiceHub.getDisplayManager() }.getOrNull() ?: return

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
                        Log.i(TAG, "releaseVirtualDisplay ok via ${m.name}(IVirtualDisplayCallback)")
                        return
                    }
                    p.size == 1 && p[0].name == "android.hardware.display.IVirtualDisplayCallback" -> {
                        m.invoke(displayManager, callback)
                        Log.i(TAG, "releaseVirtualDisplay ok via ${m.name}(IVirtualDisplayCallback)")
                        return
                    }
                    p.size == 1 && p[0].name == "android.os.IBinder" && binder != null -> {
                        m.invoke(displayManager, binder)
                        Log.i(TAG, "releaseVirtualDisplay ok via ${m.name}(IBinder)")
                        return
                    }
                    p.size == 1 && IInterface::class.java.isAssignableFrom(p[0]) -> {
                        m.invoke(displayManager, callback)
                        Log.i(TAG, "releaseVirtualDisplay ok via ${m.name}(IInterface)")
                        return
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "releaseVirtualDisplay failed on ${m.name}", t)
            }
        }
    }

}
