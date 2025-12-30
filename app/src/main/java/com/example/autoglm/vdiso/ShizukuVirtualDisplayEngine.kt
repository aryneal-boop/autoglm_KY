package com.example.autoglm.vdiso

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IInterface
import android.os.Looper
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
    )

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var readerThread: HandlerThread? = null

    @Volatile
    private var readerHandler: Handler? = null

    @Volatile
    private var displayId: Int? = null

    @Volatile
    private var vdCallback: Any? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var latestFrameTimeMs: Long = 0L

    private val frameLock = Any()

    @Volatile
    private var stopping: Boolean = false

    @Synchronized
    fun ensureStarted(args: Args = Args()): Result<Int> {
        val existing = displayId
        if (existing != null && imageReader != null && vdCallback != null) {
            return Result.success(existing)
        }
        return start(args)
    }

    @Synchronized
    fun start(args: Args = Args()): Result<Int> {
        return runCatching {
            stop()

            stopping = false

            val ht = HandlerThread("VdIsoFrame")
            ht.start()
            readerThread = ht
            readerHandler = Handler(ht.looper)

            val reader = ImageReader.newInstance(
                args.width,
                args.height,
                PixelFormat.RGBA_8888,
                3,
            )
            imageReader = reader

            val created = createVirtualDisplay(args, reader)
            vdCallback = created.second
            displayId = created.first

            reader.setOnImageAvailableListener({ r ->
                if (stopping) return@setOnImageAvailableListener
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val bmp = imageToBitmap(img)
                    val prev = synchronized(frameLock) {
                        if (stopping) {
                            null
                        } else {
                            val old = latestBitmap
                            latestBitmap = bmp
                            latestFrameTimeMs = android.os.SystemClock.uptimeMillis()
                            old
                        }
                    }
                    // Recycle previous frame outside the lock.
                    runCatching { prev?.recycle() }
                } catch (t: Throwable) {
                    Log.w(TAG, "onImageAvailable: convert failed", t)
                } finally {
                    runCatching { img.close() }
                }
            }, readerHandler ?: Handler(Looper.getMainLooper()))

            Log.i(TAG, "started: displayId=${displayId} size=${args.width}x${args.height}")
            displayId ?: throw IllegalStateException("displayId is null")
        }
    }

    fun getDisplayId(): Int? = displayId

    fun isStarted(): Boolean = displayId != null && imageReader != null && vdCallback != null

    fun getLatestFrameTimeMs(): Long = latestFrameTimeMs

    fun captureLatestBitmap(): Result<Bitmap> {
        val bmp = latestBitmap ?: return Result.failure(IllegalStateException("No cached frame yet"))
        return runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }
    }

    @Synchronized
    fun stop() {
        stopping = true

        val toRecycle = synchronized(frameLock) {
            val b = latestBitmap
            latestBitmap = null
            latestFrameTimeMs = 0L
            b
        }
        runCatching { toRecycle?.recycle() }

        val cb = vdCallback
        vdCallback = null
        displayId = null

        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { readerThread?.quitSafely() }
        readerThread = null
        readerHandler = null

        if (cb != null) {
            runCatching { releaseVirtualDisplayBestEffort(cb) }
        }
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

    private fun createVirtualDisplay(args: Args, reader: ImageReader): Pair<Int, Any> {
        val displayManager = ShizukuServiceHub.getDisplayManager()

        val flags = buildFlags(rotatesWithContent = args.rotatesWithContent)
        val config = buildVirtualDisplayConfig(args, reader, flags)
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

        val packageName = "com.android.shell"
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

    private fun buildVirtualDisplayConfig(args: Args, reader: ImageReader, flags: Int): Any {
        val surface = reader.surface
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

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw IllegalStateException("No planes")
        val buffer = plane.buffer

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}
