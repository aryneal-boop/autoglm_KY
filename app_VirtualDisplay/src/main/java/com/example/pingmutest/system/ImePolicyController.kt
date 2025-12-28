package com.example.pingmutest.system

import android.view.Display
import android.util.Log
import com.example.pingmutest.shizuku.ShizukuServiceManager

object ImePolicyController {

    private const val TAG = "ImePolicyController"

    fun moveImeToDisplay(displayId: Int): Result<Unit> {
        return runCatching {
            val wm = ShizukuServiceManager.getWindowManager()
            val wmClass = wm.javaClass
            val method = wmClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

            method.invoke(wm, Display.DEFAULT_DISPLAY, 1)
            try {
                method.invoke(wm, displayId, 0)
            } catch (t: Throwable) {
                method.invoke(wm, Display.DEFAULT_DISPLAY, 0)
                throw t
            }

            Unit
        }.onFailure { t ->
            Log.e(TAG, "moveImeToDisplay failed: displayId=$displayId", t)
        }
    }
}
