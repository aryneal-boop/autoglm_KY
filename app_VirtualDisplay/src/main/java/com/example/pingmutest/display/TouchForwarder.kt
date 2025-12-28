package com.example.pingmutest.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.ImageView
import com.example.pingmutest.shizuku.ShizukuShell
import com.example.pingmutest.shizuku.ShizukuServiceManager
import com.example.pingmutest.system.AdbSecurityUtils

object TouchForwarder {

    private const val TAG = "TouchForwarder"

    fun ensureFocusedDisplay(displayId: Int): Result<Unit> {
        return runCatching {
            val inputManager = ShizukuServiceManager.getInputManager()
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
                if (current != null && current == displayId) return@runCatching
            }

            setFocused.invoke(inputManager, displayId)
        }.onFailure { t ->
            Log.w(TAG, "ensureFocusedDisplay failed: target=$displayId", t)
        }
    }

    fun forwardTapOnView(
        context: Context,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        displayId: Int,
        event: MotionEvent,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardTapOnView unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardTapOnView unavailable: displayId=$displayId", t) }
        }

        val mapped = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, event.x, event.y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardTapOnView ignored: displayId=$displayId", t) }

        val x = mapped.first.toInt().coerceIn(0, displayWidth - 1)
        val y = mapped.second.toInt().coerceIn(0, displayHeight - 1)

        runCatching { ensureFocusedDisplay(displayId) }

        val cmd = "input -d $displayId tap $x $y"
        return ShizukuShell.execForText(cmd)
            .map { Unit }
            .onFailure { t ->
                Log.e(TAG, "forwardTapOnView failed: displayId=$displayId", t)
            }
    }

    fun forwardSwipeOnView(
        context: Context,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        displayId: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardSwipeOnView unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardSwipeOnView unavailable: displayId=$displayId", t) }
        }

        val s = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, startX, startY)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardSwipeOnView ignored: displayId=$displayId", t) }
        val e = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, endX, endY)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardSwipeOnView ignored: displayId=$displayId", t) }

        val x1 = s.first.toInt().coerceIn(0, displayWidth - 1)
        val y1 = s.second.toInt().coerceIn(0, displayHeight - 1)
        val x2 = e.first.toInt().coerceIn(0, displayWidth - 1)
        val y2 = e.second.toInt().coerceIn(0, displayHeight - 1)
        val d = durationMs.coerceIn(1, 60_000).toInt()

        runCatching { ensureFocusedDisplay(displayId) }

        val cmd = "input -d $displayId swipe $x1 $y1 $x2 $y2 $d"
        return ShizukuShell.execForText(cmd)
            .map { Unit }
            .onFailure { t ->
                Log.e(TAG, "forwardSwipeOnView failed: displayId=$displayId", t)
            }
    }

    fun forwardPinchOnView(
        context: Context,
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        displayId: Int,
        startP1X: Float,
        startP1Y: Float,
        startP2X: Float,
        startP2Y: Float,
        endP1X: Float,
        endP1Y: Float,
        endP2X: Float,
        endP2Y: Float,
        durationMs: Long,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardPinchOnView unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardPinchOnView unavailable: displayId=$displayId", t) }
        }

        val sp1 = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, startP1X, startP1Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardPinchOnView ignored: displayId=$displayId", t) }
        val sp2 = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, startP2X, startP2Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardPinchOnView ignored: displayId=$displayId", t) }
        val ep1 = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, endP1X, endP1Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardPinchOnView ignored: displayId=$displayId", t) }
        val ep2 = mapTouchToDisplayFitCenter(viewWidth, viewHeight, displayWidth, displayHeight, endP2X, endP2Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside view"))
                .onFailure { t -> Log.w(TAG, "forwardPinchOnView ignored: displayId=$displayId", t) }

        val d = durationMs.coerceIn(1, 60_000)
        return runCatching {
            ensureFocusedDisplay(displayId)
            injectTwoFingerGesture(
                displayId = displayId,
                start1X = sp1.first,
                start1Y = sp1.second,
                start2X = sp2.first,
                start2Y = sp2.second,
                end1X = ep1.first,
                end1Y = ep1.second,
                end2X = ep2.first,
                end2Y = ep2.second,
                durationMs = d,
            )
        }.onFailure { t ->
            Log.e(TAG, "forwardPinchOnView failed: displayId=$displayId", t)
        }
    }

    fun forwardBack(
        context: Context,
        displayId: Int,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardBack unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardBack unavailable: displayId=$displayId", t) }
        }

        runCatching { ensureFocusedDisplay(displayId) }

        val cmd = "input -d $displayId keyevent 4"
        return ShizukuShell.execForText(cmd)
            .map { Unit }
            .onFailure { t ->
                Log.e(TAG, "forwardBack failed: displayId=$displayId", t)
            }
    }

    fun forwardTap(
        context: Context,
        imageView: ImageView,
        bitmap: Bitmap,
        displayId: Int,
        event: MotionEvent,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardTap unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardTap unavailable: displayId=$displayId", t) }
        }

        val mapped = mapTouchToBitmap(imageView, bitmap, event.x, event.y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardTap ignored: displayId=$displayId", t) }

        val x = mapped.first.toInt().coerceIn(0, bitmap.width - 1)
        val y = mapped.second.toInt().coerceIn(0, bitmap.height - 1)

        runCatching { ensureFocusedDisplay(displayId) }

        val cmd = "input -d $displayId tap $x $y"
        return ShizukuShell.execForText(cmd)
            .map { Unit }
            .onFailure { t ->
                Log.e(TAG, "forwardTap failed: displayId=$displayId", t)
            }
    }

    fun forwardSwipe(
        context: Context,
        imageView: ImageView,
        bitmap: Bitmap,
        displayId: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardSwipe unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardSwipe unavailable: displayId=$displayId", t) }
        }

        val s = mapTouchToBitmap(imageView, bitmap, startX, startY)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardSwipe ignored: displayId=$displayId", t) }
        val e = mapTouchToBitmap(imageView, bitmap, endX, endY)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardSwipe ignored: displayId=$displayId", t) }

        val x1 = s.first.toInt().coerceIn(0, bitmap.width - 1)
        val y1 = s.second.toInt().coerceIn(0, bitmap.height - 1)
        val x2 = e.first.toInt().coerceIn(0, bitmap.width - 1)
        val y2 = e.second.toInt().coerceIn(0, bitmap.height - 1)
        val d = durationMs.coerceIn(1, 60_000).toInt()

        runCatching { ensureFocusedDisplay(displayId) }

        val cmd = "input -d $displayId swipe $x1 $y1 $x2 $y2 $d"
        return ShizukuShell.execForText(cmd)
            .map { Unit }
            .onFailure { t ->
                Log.e(TAG, "forwardSwipe failed: displayId=$displayId", t)
            }
    }

    fun forwardPinch(
        context: Context,
        imageView: ImageView,
        bitmap: Bitmap,
        displayId: Int,
        startP1X: Float,
        startP1Y: Float,
        startP2X: Float,
        startP2Y: Float,
        endP1X: Float,
        endP1Y: Float,
        endP2X: Float,
        endP2Y: Float,
        durationMs: Long,
    ): Result<Unit> {
        if (!AdbSecurityUtils.isAdbEnabled(context)) {
            return Result.failure<Unit>(IllegalStateException("USB debugging is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardPinch unavailable: displayId=$displayId", t) }
        }
        if (!AdbSecurityUtils.isUsbDebuggingSecurityEnabledOnMiui(context)) {
            return Result.failure<Unit>(IllegalStateException("MIUI USB debugging (Security settings) is disabled"))
                .onFailure { t -> Log.w(TAG, "forwardPinch unavailable: displayId=$displayId", t) }
        }

        val sp1 = mapTouchToBitmap(imageView, bitmap, startP1X, startP1Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardPinch ignored: displayId=$displayId", t) }
        val sp2 = mapTouchToBitmap(imageView, bitmap, startP2X, startP2Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardPinch ignored: displayId=$displayId", t) }
        val ep1 = mapTouchToBitmap(imageView, bitmap, endP1X, endP1Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardPinch ignored: displayId=$displayId", t) }
        val ep2 = mapTouchToBitmap(imageView, bitmap, endP2X, endP2Y)
            ?: return Result.failure<Unit>(IllegalStateException("Touch is outside image"))
                .onFailure { t -> Log.w(TAG, "forwardPinch ignored: displayId=$displayId", t) }

        val d = durationMs.coerceIn(1, 60_000)
        return runCatching {
            ensureFocusedDisplay(displayId)
            injectTwoFingerGesture(
                displayId = displayId,
                start1X = sp1.first,
                start1Y = sp1.second,
                start2X = sp2.first,
                start2Y = sp2.second,
                end1X = ep1.first,
                end1Y = ep1.second,
                end2X = ep2.first,
                end2Y = ep2.second,
                durationMs = d,
            )
        }.onFailure { t ->
            Log.e(TAG, "forwardPinch failed: displayId=$displayId", t)
        }
    }

    internal fun mapTouchToBitmap(
        imageView: ImageView,
        bitmap: Bitmap,
        touchX: Float,
        touchY: Float,
    ): Pair<Float, Float>? {
        val drawable = imageView.drawable ?: return null

        val drawableRect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

        val imageMatrix = Matrix(imageView.imageMatrix)
        val inverse = Matrix()
        if (!imageMatrix.invert(inverse)) return null

        val pts = floatArrayOf(touchX, touchY)
        inverse.mapPoints(pts)

        val dx = pts[0]
        val dy = pts[1]
        if (dx < 0f || dy < 0f || dx > drawableRect.right || dy > drawableRect.bottom) return null

        val scaleX = bitmap.width.toFloat() / drawableRect.width()
        val scaleY = bitmap.height.toFloat() / drawableRect.height()

        return dx * scaleX to dy * scaleY
    }

    internal fun mapTouchToDisplay(
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        touchX: Float,
        touchY: Float,
    ): Pair<Float, Float>? {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return null
        if (touchX < 0f || touchY < 0f || touchX > viewWidth.toFloat() || touchY > viewHeight.toFloat()) return null

        val x = (touchX / viewWidth.toFloat()) * displayWidth.toFloat()
        val y = (touchY / viewHeight.toFloat()) * displayHeight.toFloat()
        return x to y
    }

    internal fun mapTouchToDisplayFitCenter(
        viewWidth: Int,
        viewHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        touchX: Float,
        touchY: Float,
    ): Pair<Float, Float>? {
        if (viewWidth <= 0 || viewHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) return null

        val scale = minOf(
            viewWidth.toFloat() / displayWidth.toFloat(),
            viewHeight.toFloat() / displayHeight.toFloat(),
        )
        if (scale <= 0f) return null

        val contentW = displayWidth.toFloat() * scale
        val contentH = displayHeight.toFloat() * scale
        val offsetX = (viewWidth.toFloat() - contentW) / 2f
        val offsetY = (viewHeight.toFloat() - contentH) / 2f

        val localX = touchX - offsetX
        val localY = touchY - offsetY
        if (localX < 0f || localY < 0f || localX > contentW || localY > contentH) return null

        val x = localX / scale
        val y = localY / scale
        return x to y
    }

    private fun injectTwoFingerGesture(
        displayId: Int,
        start1X: Float,
        start1Y: Float,
        start2X: Float,
        start2Y: Float,
        end1X: Float,
        end1Y: Float,
        end2X: Float,
        end2Y: Float,
        durationMs: Long,
    ) {
        val inputManager = ShizukuServiceManager.getInputManager()
        val injectMethod = inputManager.javaClass.methods.firstOrNull { m ->
            m.name == "injectInputEvent" && m.parameterTypes.size == 2
        } ?: throw NoSuchMethodException("injectInputEvent not found")

        val setDisplayIdMethod = MotionEvent::class.java.methods.firstOrNull { m ->
            m.name == "setDisplayId" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
        }

        fun MotionEvent.applyDisplayId(): MotionEvent {
            if (setDisplayIdMethod != null) {
                runCatching { setDisplayIdMethod.invoke(this, displayId) }
            }
            return this
        }

        val downTime = SystemClock.uptimeMillis()
        val steps = 12
        val interval = (durationMs / steps).coerceAtLeast(1)

        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
        )

        fun coords(x1: Float, y1: Float, x2: Float, y2: Float): Array<MotionEvent.PointerCoords> {
            return arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = x1
                    y = y1
                    pressure = 1f
                    size = 1f
                },
                MotionEvent.PointerCoords().apply {
                    x = x2
                    y = y2
                    pressure = 1f
                    size = 1f
                },
            )
        }

        fun inject(ev: MotionEvent) {
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            val ok = injectMethod.invoke(inputManager, ev, 0) as? Boolean ?: false
            ev.recycle()
            if (!ok) throw IllegalStateException("injectInputEvent returned false")
        }

        val evDown = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            1,
            props,
            coords(start1X, start1Y, start2X, start2Y),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        ).applyDisplayId()
        inject(evDown)

        val evP2Down = MotionEvent.obtain(
            downTime,
            downTime + 2,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords(start1X, start1Y, start2X, start2Y),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        ).applyDisplayId()
        inject(evP2Down)

        for (i in 1..steps) {
            val t = downTime + 2 + i * interval
            val f = i.toFloat() / steps.toFloat()
            val x1 = start1X + (end1X - start1X) * f
            val y1 = start1Y + (end1Y - start1Y) * f
            val x2 = start2X + (end2X - start2X) * f
            val y2 = start2Y + (end2Y - start2Y) * f
            val move = MotionEvent.obtain(
                downTime,
                t,
                MotionEvent.ACTION_MOVE,
                2,
                props,
                coords(x1, y1, x2, y2),
                0,
                0,
                1f,
                1f,
                0,
                0,
                0,
                0,
            ).applyDisplayId()
            inject(move)
        }

        val upTime = downTime + 2 + steps * interval + 2
        val evP2Up = MotionEvent.obtain(
            downTime,
            upTime,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords(end1X, end1Y, end2X, end2Y),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        ).applyDisplayId()
        inject(evP2Up)

        val evUp = MotionEvent.obtain(
            downTime,
            upTime + 2,
            MotionEvent.ACTION_UP,
            1,
            props,
            coords(end1X, end1Y, end2X, end2Y),
            0,
            0,
            1f,
            1f,
            0,
            0,
            0,
            0,
        ).applyDisplayId()
        inject(evUp)
    }
}
