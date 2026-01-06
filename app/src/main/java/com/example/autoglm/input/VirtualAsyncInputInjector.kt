package com.example.autoglm.input

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.util.Log
import java.lang.reflect.Method
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 虚拟屏输入注入器（异步 best-effort）。
 *
 * **用途**
 * - 通过 Shizuku 获取 `IInputManager` 并反射调用 `injectInputEvent`，用于向指定 display 注入：
 *   - 单点触摸（down/move/up）
 *   - 按键（back/home 等）
 * - 自动适配不同 Android/ROM 上 `injectInputEvent` 的方法签名：
 *   - `injectInputEvent(InputEvent, int mode)`
 *   - `injectInputEvent(InputEvent, int displayId, int mode)`
 *
 * **典型用法**
 * - 上层通常通过 [com.example.autoglm.VirtualDisplayController] 暴露的静态方法调用，
 *   例如 `injectTapBestEffort(displayId, x, y)`。
 *
 * **使用注意事项**
 * - 该类为 best-effort：所有注入均不等待结果，失败会被吞掉以避免影响主流程。
 * - 强依赖 Shizuku 权限与隐藏 API：不同 ROM 可能存在差异，因此内部会记录候选签名用于排障。
 */
internal class VirtualAsyncInputInjector {

    private class InputManagerAccess(
        val iInputManager: Any,
        val injectInputEvent: Method,
    )

    @Volatile
    private var cached: InputManagerAccess? = null

    @Volatile
    private var setDisplayIdMethod: Method? = null

    @Volatile
    private var loggedChosenInjectMethod: Boolean = false

    @Volatile
    private var loggedAllInjectCandidates: Boolean = false

    private val touchPointerPropertiesTL = ThreadLocal<Array<PointerProperties>>()
    private val touchPointerCoordsTL = ThreadLocal<Array<PointerCoords>>()

    private fun getRawService(name: String): IBinder {
        val sm = Class.forName("android.os.ServiceManager")
        val m = sm.getMethod("getService", String::class.java)
        return (m.invoke(null, name) as? IBinder)
            ?: throw IllegalStateException("Service not found: $name")
    }

    private fun getIInputManager(): InputManagerAccess {
        val existing = cached
        if (existing != null) return existing

        // Must be called via Shizuku binder wrapper so it has sufficient privileges.
        val binder = ShizukuBinderWrapper(getRawService("input"))
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        val iim = asInterface.invoke(null, binder)
            ?: throw IllegalStateException("IInputManager.asInterface returned null")

        val candidates = iim.javaClass.methods
            .asSequence()
            .filter { m -> m.name == "injectInputEvent" }
            .filter { m -> m.returnType == Boolean::class.javaPrimitiveType }
            .filter { m ->
                val p = m.parameterTypes
                if (p.isEmpty()) return@filter false
                // First parameter must be InputEvent (or a subclass). Reject Object.
                if (!InputEvent::class.java.isAssignableFrom(p[0])) return@filter false
                // Remaining parameters must be primitive int
                when (p.size) {
                    2 -> p[1] == Int::class.javaPrimitiveType
                    3 -> p[1] == Int::class.javaPrimitiveType && p[2] == Int::class.javaPrimitiveType
                    else -> false
                }
            }
            .toList()

        if (!loggedAllInjectCandidates) {
            loggedAllInjectCandidates = true
            runCatching {
                val sigs = candidates.joinToString(" | ") { it.toGenericString() }
                Log.i("InputInject", "injectInputEvent candidates: $sigs")
            }
        }

        // Prefer 2-arg overload (InputEvent, mode). OEM ROMs may repurpose 3-arg overload.
        val inject = candidates.firstOrNull { it.parameterTypes.size == 2 }
            ?: candidates.firstOrNull { it.parameterTypes.size == 3 }
            ?: throw NoSuchMethodException("IInputManager.injectInputEvent(InputEvent,int[,int])")

        if (!loggedChosenInjectMethod) {
            loggedChosenInjectMethod = true
            runCatching { Log.i("InputInject", "Using ${inject.toGenericString()}") }
        }

        return InputManagerAccess(iim, inject).also { cached = it }
    }

    private fun ensureSetDisplayIdMethod() {
        if (setDisplayIdMethod != null) return
        setDisplayIdMethod = InputEvent::class.java.methods.firstOrNull { m ->
            m.name == "setDisplayId" && m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
        }
    }

    private fun applyDisplayIdBestEffort(ev: InputEvent, displayId: Int) {
        if (displayId <= 0) return
        ensureSetDisplayIdMethod()
        val m = setDisplayIdMethod ?: return
        runCatching { m.invoke(ev, displayId) }
    }

    private fun injectInputEventAsync(access: InputManagerAccess, displayId: Int, ev: InputEvent) {
        // Always use ASYNC mode (0). Do not wait for result/finish.
        // Different Android versions expose different signatures on IInputManager:
        // - injectInputEvent(InputEvent, int mode)
        // - injectInputEvent(InputEvent, int displayId, int mode)
        runCatching {
            when (access.injectInputEvent.parameterTypes.size) {
                3 -> access.injectInputEvent.invoke(access.iInputManager, ev, displayId, 0)
                else -> access.injectInputEvent.invoke(access.iInputManager, ev, 0)
            }
        }
    }

    fun injectKeyEventAsync(displayId: Int, keyCode: Int, action: Int) {
        val access = getIInputManager()

        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(now, now, action, keyCode, 0)
        ev.source = InputDevice.SOURCE_KEYBOARD
        applyDisplayIdBestEffort(ev, displayId)

        injectInputEventAsync(access, displayId, ev)
    }

    fun injectSingleTouchAsync(displayId: Int, downTime: Long, x: Float, y: Float, action: Int) {
        val access = getIInputManager()

        val now = SystemClock.uptimeMillis()
        val props = touchPointerPropertiesTL.get() ?: arrayOf(
            PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        ).also { touchPointerPropertiesTL.set(it) }

        val coords = touchPointerCoordsTL.get() ?: arrayOf(
            PointerCoords().apply {
                pressure = 1f
                size = 1f
            }
        ).also { touchPointerCoordsTL.set(it) }

        coords[0].x = x
        coords[0].y = y

        val ev = MotionEvent.obtain(
            downTime,
            now,
            action,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        applyDisplayIdBestEffort(ev, displayId)

        injectInputEventAsync(access, displayId, ev)

        ev.recycle()
    }
}
