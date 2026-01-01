package com.example.autoglm

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class WelcomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_welcome)

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { finish() }
        }, 1200L)
    }
}
