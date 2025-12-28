package com.example.pingmutest.display

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

object VirtualDisplayFrameGrabber {

    private const val TAG = "VdFrameGrabber"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var readerThread: HandlerThread? = null

    @Volatile
    private var readerHandler: Handler? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var displayId: Int? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var latestFrameTimeMs: Long = 0L

    @Synchronized
    fun start(args: ShizukuVirtualDisplayCreator.Args): Result<Int> {
        return runCatching {
            stop()

            val ht = HandlerThread("VdFrameGrabber")
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

            val id = ShizukuVirtualDisplayCreator.createVirtualDisplayId(
                args = args,
                surface = reader.surface,
                ownContentOnly = true,
            )

            displayId = id

            reader.setOnImageAvailableListener({ r ->
                val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val bmp = imageToBitmap(img)
                    val prev = latestBitmap
                    latestBitmap = bmp
                    latestFrameTimeMs = android.os.SystemClock.uptimeMillis()
                    prev?.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "onImageAvailable: convert failed", t)
                } finally {
                    runCatching { img.close() }
                }
            }, readerHandler ?: mainHandler)

            Log.i(TAG, "started: displayId=$id size=${args.width}x${args.height}")
            id
        }
    }

    fun getDisplayId(): Int? = displayId

    fun isStarted(): Boolean = imageReader != null && displayId != null

    fun getLatestFrameTimeMs(): Long = latestFrameTimeMs

    fun captureLatestBitmapIfNewer(lastTimeMs: Long): Result<Pair<Long, Bitmap>?> {
        val time = latestFrameTimeMs
        if (time <= 0L || time <= lastTimeMs) {
            return Result.success(null)
        }
        return captureLatestBitmap().map { bmp ->
            time to bmp
        }
    }

    fun captureLatestBitmap(): Result<Bitmap> {
        val bmp = latestBitmap
            ?: return Result.failure(IllegalStateException("No cached frame yet"))
        return runCatching {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    @Synchronized
    fun stop() {
        runCatching {
            latestBitmap?.recycle()
        }
        latestBitmap = null
        latestFrameTimeMs = 0L

        runCatching {
            imageReader?.close()
        }
        imageReader = null
        displayId = null

        runCatching {
            readerThread?.quitSafely()
        }
        readerThread = null
        readerHandler = null
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
