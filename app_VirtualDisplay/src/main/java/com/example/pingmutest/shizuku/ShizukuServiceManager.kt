package com.example.pingmutest.shizuku

import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

object ShizukuServiceManager {

    private const val TAG = "ShizukuServiceManager"

    private fun getServiceBinder(serviceName: String): IBinder {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, serviceName) as IBinder
            ShizukuBinderWrapper(binder)
        } catch (t: Throwable) {
            Log.e(TAG, "getServiceBinder failed: service=$serviceName", t)
            throw t
        }
    }

    private fun asInterface(stubClassName: String, binder: IBinder): Any {
        return try {
            val stubClass = Class.forName(stubClassName)
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, binder)
        } catch (t: Throwable) {
            Log.e(TAG, "asInterface failed: stub=$stubClassName", t)
            throw t
        }
    }

    @Volatile
    private var displayManager: Any? = null

    @Volatile
    private var windowManager: Any? = null

    @Volatile
    private var inputManager: Any? = null

    @Volatile
    private var activityTaskManager: Any? = null

    fun getDisplayManager(): Any {
        val cached = displayManager
        if (cached != null) return cached
        return synchronized(this) {
            val cached2 = displayManager
            if (cached2 != null) return@synchronized cached2
            val binder = getServiceBinder("display")
            val service = asInterface("android.hardware.display.IDisplayManager\$Stub", binder)
            displayManager = service
            service
        }
    }

    fun getWindowManager(): Any {
        val cached = windowManager
        if (cached != null) return cached
        return synchronized(this) {
            val cached2 = windowManager
            if (cached2 != null) return@synchronized cached2
            val binder = getServiceBinder("window")
            val service = asInterface("android.view.IWindowManager\$Stub", binder)
            windowManager = service
            service
        }
    }

    fun getInputManager(): Any {
        val cached = inputManager
        if (cached != null) return cached
        return synchronized(this) {
            val cached2 = inputManager
            if (cached2 != null) return@synchronized cached2
            val binder = getServiceBinder("input")
            val service = asInterface("android.hardware.input.IInputManager\$Stub", binder)
            inputManager = service
            service
        }
    }

    fun getActivityTaskManager(): Any {
        val cached = activityTaskManager
        if (cached != null) return cached
        return synchronized(this) {
            val cached2 = activityTaskManager
            if (cached2 != null) return@synchronized cached2
            val binder = getServiceBinder("activity_task")
            val service = asInterface("android.app.IActivityTaskManager\$Stub", binder)
            activityTaskManager = service
            service
        }
    }

    fun getRawDisplayManagerBinder(): IBinder = getServiceBinder("display")

    fun getRawWindowManagerBinder(): IBinder = getServiceBinder("window")

    fun getRawInputManagerBinder(): IBinder = getServiceBinder("input")

    fun getRawActivityTaskManagerBinder(): IBinder = getServiceBinder("activity_task")
}
