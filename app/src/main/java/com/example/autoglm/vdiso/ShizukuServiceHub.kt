package com.example.autoglm.vdiso

import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 系统服务获取中心（通过 Shizuku 代理）。
 *
 * **用途**
 * - 通过反射 `android.os.ServiceManager.getService(name)` 获取系统 service binder，
 *   再用 `ShizukuBinderWrapper` 包装成“可跨进程/提权访问”的 binder。
 * - 向 `vdiso` 虚拟隔离模块提供关键系统服务的 `asInterface` 实例：
 *   - DisplayManager / InputManager / ActivityTaskManager / WindowManager
 *
 * **引用路径（常见）**
 * - `ShizukuVirtualDisplayEngine`：创建 VirtualDisplay、切换输出 Surface、设置 IME 策略等。
 *
 * **使用注意事项**
 * - 依赖隐藏 API/系统 AIDL：不同 Android 版本类名/方法签名可能变化，调用方要做好兜底。
 */
object ShizukuServiceHub {

    private const val TAG = "VdIsoServices"

    private fun getRawService(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val m = sm.getMethod("getService", String::class.java)
            m.invoke(null, name) as? IBinder
        } catch (t: Throwable) {
            Log.w(TAG, "getRawService failed: $name", t)
            null
        }
    }

    fun getService(name: String): IBinder {
        val b = getRawService(name) ?: throw IllegalStateException("Service not found: $name")
        return ShizukuBinderWrapper(b)
    }

    fun getDisplayManager(): Any {
        val binder = getService("display")
        val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    fun getInputManager(): Any {
        val binder = getService("input")
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    fun getActivityTaskManager(): Any {
        val binder = getService("activity_task")
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    fun getWindowManager(): Any {
        val binder = getService("window")
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }
}
