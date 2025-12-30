package com.example.autoglm.vdiso

import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper

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
}
