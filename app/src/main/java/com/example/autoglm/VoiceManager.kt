package com.example.autoglm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 系统语音识别封装（SpeechRecognizer）。
 *
 * **用途**
 * - 使用系统自带语音识别服务将语音转为文本（与本项目的“录音 -> Python 转写”不同，这是系统 ASR）。
 * - 封装识别生命周期并通过 [Listener] 统一回调：ready/listening/partial/final/error/end。
 *
 * **典型用法**
 * - `VoiceManager(context, listener).start()`
 *
 * **引用路径（常见）**
 * - 可用于 `ChatActivity` 或 `FloatingStatusService` 的备用语音输入方案（以实际接入为准）。
 *
 * **使用注意事项**
 * - 依赖设备是否安装/启用语音识别引擎：不可用时会回调错误并结束。
 * - 需要录音权限：系统会在缺少权限时返回 `ERROR_INSUFFICIENT_PERMISSIONS`。
 */
class VoiceManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onEnd()
    }

    private val appContext = context.applicationContext

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var isRunning: Boolean = false

    fun start() {
        if (isRunning) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onError("当前系统不支持语音识别：缺少/未启用语音识别服务（语音输入引擎）。")
            listener.onEnd()
            return
        }

        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (t: Throwable) {
            null
        }

        if (sr == null) {
            listener.onError("创建 SpeechRecognizer 失败")
            listener.onEnd()
            return
        }

        recognizer = sr
        isRunning = true

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listener.onReady()
            }

            override fun onBeginningOfSpeech() {
                listener.onListening()
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!isRunning) return
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "没听清，请重试"
                    SpeechRecognizer.ERROR_NETWORK -> "网络异常，请重试"
                    SpeechRecognizer.ERROR_AUDIO -> "录音异常，请重试"
                    SpeechRecognizer.ERROR_SERVER -> "语音服务异常，请重试"
                    SpeechRecognizer.ERROR_CLIENT -> "语音识别被中断，请重试"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未授予录音权限，无法使用语音识别"
                    else -> "语音识别错误：$error"
                }
                listener.onError(msg)
                stopInternal()
            }

            override fun onResults(results: Bundle?) {
                if (!isRunning) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                listener.onFinal(text)
                stopInternal()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isRunning) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (text.isNotEmpty()) {
                    listener.onPartial(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            listener.onError("启动语音识别失败：${t.message}")
            stopInternal()
        }
    }

    fun cancel() {
        stopInternal(cancel = true)
    }

    fun release() {
        stopInternal(cancel = true, release = true)
    }

    private fun stopInternal(cancel: Boolean = false, release: Boolean = true) {
        if (!isRunning && recognizer == null) return
        isRunning = false

        val sr = recognizer
        recognizer = null

        try {
            if (cancel) {
                sr?.cancel()
            } else {
                sr?.stopListening()
            }
        } catch (_: Exception) {
        }

        if (release) {
            try {
                sr?.destroy()
            } catch (_: Exception) {
            }
        }

        listener.onEnd()
    }
}
