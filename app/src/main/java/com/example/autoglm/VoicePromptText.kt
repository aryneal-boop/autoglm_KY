package com.example.autoglm

/**
 * 语音交互提示文案集合。
 *
 * **用途**
 * - 统一维护语音相关流程（录音/识别/转写）在 UI/悬浮窗中展示的短文案，避免散落硬编码。
 *
 * **引用路径（常见）**
 * - `ChatActivity`：语音模式提示与 Toast。
 * - `FloatingStatusService`：悬浮窗语音入口状态提示。
 *
 * **使用注意事项**
 * - 该文件仅存放 UI 文案常量：不要在这里引入业务逻辑。
 */
object VoicePromptText {
    const val LISTENING = "正在倾听，请讲话..."
    const val RECOGNIZING = "识别中..."

    const val RECORDING = "正在录音..."
    const val TRANSCRIBING = "转写中..."

    const val TOO_SHORT_TOAST = "说话时间太短"
    const val TOO_SHORT_STATUS = "说话时间太短"

    const val INVALID_FILE = "录音文件无效，请重试。"

    const val NO_PERMISSION_STATUS = "未授予录音权限，无法使用语音识别。"
    const val NO_PERMISSION_COMPACT = "无录音权限"

    const val RECORDING_FAILED_STATUS_PREFIX = "录音启动失败："
    const val RECORDING_FAILED_COMPACT = "录音失败"
}
