package com.example.autoglm

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display

class WelcomePresentation(
    outerContext: Context,
    display: Display,
) : Presentation(outerContext, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_welcome)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }
}
