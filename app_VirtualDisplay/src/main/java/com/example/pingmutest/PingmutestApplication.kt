package com.example.pingmutest

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PingmutestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }
}
