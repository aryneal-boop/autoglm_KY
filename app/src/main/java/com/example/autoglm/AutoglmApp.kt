package com.example.autoglm

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class AutoglmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.addHiddenApiExemptions("L")
            }
        } catch (_: Throwable) {
        }
        AppState.init(this)
    }
}

object AppState {

    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        TaskControl.init(ctx)

        try {
            VirtualDisplayController.ensureProcessLifecycleObserverInstalled(ctx)
        } catch (_: Exception) {
        }

        try {
            VirtualDisplayController.hardResetOverlayAsync(ctx)
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun getAppContext(): Context? = appContext
}
