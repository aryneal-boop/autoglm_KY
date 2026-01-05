package com.example.autoglm

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display

/**
 * 虚拟屏欢迎页 Presentation（备用展示路径）。
 *
 * **用途**
 * - 当无法通过 `ActivityOptions.setLaunchDisplayId` 在目标 display 启动 [WelcomeActivity] 时，
 *   作为 fallback：在指定 [Display] 上直接弹出一个 [Presentation] 来展示 `presentation_welcome` 布局。
 *
 * **引用路径（常见）**
 * - `VirtualDisplayController.showWelcomePresentationOnDisplay` 的最后兜底分支。
 *
 * **使用注意事项**
 * - Presentation 依赖目标 display 的 WindowManager 就绪；部分 ROM 需要重试才能成功 attach。
 */
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
