package com.example.autoglm

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager

/**
 * 虚拟屏欢迎/兜底 Activity。
 *
 * **用途**
 * - 在虚拟隔离模式创建新的 VirtualDisplay 后，尽量在目标 display 上启动该 Activity 作为“可见的兜底界面”。
 * - 主动请求焦点并尝试唤起输入法（IME），用于：
 *   - 验证 display 是否可正常 attach window
 *   - 避免部分 ROM 虚拟屏无窗口时出现黑屏/不可控状态
 *
 * **引用路径（常见）**
 * - `VirtualDisplayController.showWelcomeOnActiveDisplayBestEffort`：在虚拟屏启动后展示。
 *
 * **使用注意事项**
 * - `onBackPressed` 被吞掉：该界面设计为“兜底界面”，避免用户退出导致虚拟屏进入不可控黑屏。
 */
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

    override fun onBackPressed() {
        // 欢迎界面作为虚拟屏的兜底界面：吞掉返回键，避免退出后虚拟屏变黑或落入不可控状态。
        return
    }
}
