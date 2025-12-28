package com.example.autoglm

import android.app.Application
import android.content.Context

class AutoglmApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
