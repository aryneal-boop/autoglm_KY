package com.example.autoglm

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

class WelcomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_welcome)

        runCatching {
            val dc = createDisplayContext(display)
            val imm = dc.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

            val root = window?.decorView
            root?.isFocusable = true
            root?.isFocusableInTouchMode = true
            root?.requestFocus()

            val v: View? = currentFocus ?: root
            if (imm != null && v != null) {
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
