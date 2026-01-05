package com.example.autoglm

/**
 * 悬浮窗状态文案映射器。
 *
 * **用途**
 * - 将模型/执行过程中的“动作流文本”映射为更短、更易读的中文状态，用于悬浮窗状态条展示。
 * - 典型输入来源：
 *   - Python 回调 `on_action` 的输出
 *   - [TaskStreamClassifier] 分类后的 kind/text
 *
 * **典型用法**
 * - `val short = OverlayStatusTextMapper.map(kind, text)`
 *
 * **使用注意事项**
 * - 该映射是 best-effort：无法识别时返回 `null`，上层应保留原文或维持上一次状态。
 */
object OverlayStatusTextMapper {

    fun map(kind: String?, text: String?): String? {
        val t = text.orEmpty().trim()
        if (t.isEmpty()) return null

        fun mapOperationToZh(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val upper = s.uppercase()
            return when {
                upper.contains("TAKE_OVER") -> "请求人工接管..."
                upper.contains("DOUBLE TAP") || upper.contains("DOUBLE_TAP") || upper.contains("DOUBLETAP") -> "正在双击..."
                upper.contains("LONG PRESS") || upper.contains("LONG_PRESS") || upper.contains("LONGPRESS") -> "正在长按..."
                upper.contains("LAUNCH") -> "正在启动应用..."
                upper.contains("TAP") -> "正在点击..."
                upper.contains("TYPE") -> "正在输入..."
                upper.contains("SWIPE") -> "正在滑动..."
                upper.contains("BACK") -> "正在返回..."
                upper.contains("HOME") -> "正在返回桌面..."
                upper.contains("WAIT") -> "正在等待页面加载..."
                else -> null
            }
        }

        return when {
            t.contains("正在调用模型") || t.contains("调用模型") -> "正在思考..."

            t.contains("屏幕截图") || t.contains("截取屏幕") || t.contains("截屏") -> "正在查阅屏幕"

            t.startsWith("计划动作：") -> {
                val action = t.removePrefix("计划动作：").trim()
                mapOperationToZh(action) ?: if (action.isEmpty()) "正在执行..." else "正在 $action..."
            }

            kind?.trim()?.uppercase() == "OPERATION" -> mapOperationToZh(t) ?: t

            t.startsWith("计划动作") && t.contains("Launch") -> {
                val idx = t.indexOf("Launch")
                val rest = if (idx >= 0) t.substring(idx + "Launch".length).trim() else ""
                val target = rest.ifEmpty { "应用" }
                "正在打开$target..."
            }

            kind?.trim()?.uppercase() == "STATUS" || kind?.trim()?.uppercase() == "PLAN" -> t

            else -> null
        }
    }
}
