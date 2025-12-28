package com.example.autoglm

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
