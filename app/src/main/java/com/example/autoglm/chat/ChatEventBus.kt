package com.example.autoglm.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object ChatEventBus {

    sealed class Event {
        data class StartTaskFromSpeech(val recognizedText: String) : Event()

        data class InterventionRequest(
            val requestId: Long,
            val requestType: String,
            val message: String,
        ) : Event()

        data class InterventionClear(
            val requestId: Long,
        ) : Event()

        data class AppendText(val role: MessageRole, val kind: String?, val text: String) : Event()

        data class AppendToLastText(val role: MessageRole, val kind: String?, val text: String) : Event()

        data class AppendImage(val imageBytes: ByteArray) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    private val messageBuffer: MutableList<ChatMessage> = mutableListOf()
    private var idGen: Long = 1L

    fun post(event: Event) {
        _events.tryEmit(event)
    }

    fun postInterventionRequest(requestId: Long, requestType: String, message: String) {
        if (requestId <= 0L) return
        post(Event.InterventionRequest(requestId, requestType, message))
    }

    fun postInterventionClear(requestId: Long) {
        if (requestId <= 0L) return
        post(Event.InterventionClear(requestId))
    }

    fun postText(role: MessageRole, text: String, kind: String? = null) {
        val t = text.trimEnd()
        if (t.isEmpty()) return
        val m = ChatMessage(
            id = nextId(),
            role = role,
            type = MessageType.TEXT,
            kind = kind,
            text = t,
        )
        synchronized(messageBuffer) {
            messageBuffer.add(m)
        }
        post(Event.AppendText(role, kind, t))
    }

    fun recordText(role: MessageRole, text: String, kind: String? = null) {
        val t = text.trimEnd()
        if (t.isEmpty()) return
        val m = ChatMessage(
            id = nextId(),
            role = role,
            type = MessageType.TEXT,
            kind = kind,
            text = t,
        )
        synchronized(messageBuffer) {
            messageBuffer.add(m)
        }
    }

    fun appendToLastText(role: MessageRole, kind: String?, text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty()) return false

        val appended = synchronized(messageBuffer) {
            var i = messageBuffer.size - 1
            while (i >= 0) {
                val m = messageBuffer[i]
                if (m.role == MessageRole.USER) {
                    // 以用户消息作为“任务边界”：新任务的 AI/ACTION 不允许合并到上一轮的旧气泡。
                    return@synchronized false
                }
                if (role == MessageRole.AI && m.role == MessageRole.ACTION && m.kind == "STEP") {
                    // 以步骤消息作为“阶段边界”：新步骤的 AI/THINK 不允许合并到上一步的 AI 气泡。
                    return@synchronized false
                }
                if (m.role == role && m.type == MessageType.TEXT) {
                    // 同 role 但不同 kind，说明已经进入新的阶段/步骤，不允许回溯合并到更早的旧气泡。
                    if (m.kind != kind) {
                        return@synchronized false
                    }
                    val merged = m.text.orEmpty() + t
                    messageBuffer[i] = m.copy(text = merged)
                    return@synchronized true
                }
                i--
            }
            false
        }

        if (appended) {
            post(Event.AppendToLastText(role, kind, t))
        }
        return appended
    }

    fun recordAppendToLastText(role: MessageRole, kind: String?, text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty()) return false
        return synchronized(messageBuffer) {
            var i = messageBuffer.size - 1
            while (i >= 0) {
                val m = messageBuffer[i]
                if (m.role == MessageRole.USER) {
                    // 以用户消息作为“任务边界”：新任务的 AI/ACTION 不允许合并到上一轮的旧气泡。
                    return@synchronized false
                }
                if (role == MessageRole.AI && m.role == MessageRole.ACTION && m.kind == "STEP") {
                    // 以步骤消息作为“阶段边界”：新步骤的 AI/THINK 不允许合并到上一步的 AI 气泡。
                    return@synchronized false
                }
                if (m.role == role && m.type == MessageType.TEXT) {
                    // 同 role 但不同 kind，说明已经进入新的阶段/步骤，不允许回溯合并到更早的旧气泡。
                    if (m.kind != kind) {
                        return@synchronized false
                    }
                    messageBuffer[i] = m.copy(text = m.text.orEmpty() + t)
                    return@synchronized true
                }
                i--
            }
            false
        }
    }

    fun postImage(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val m = ChatMessage(
            id = nextId(),
            role = MessageRole.AI,
            type = MessageType.IMAGE,
            imageBytes = bytes,
        )
        synchronized(messageBuffer) {
            messageBuffer.add(m)
        }
        post(Event.AppendImage(bytes))
    }

    fun recordImage(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val m = ChatMessage(
            id = nextId(),
            role = MessageRole.AI,
            type = MessageType.IMAGE,
            imageBytes = bytes,
        )
        synchronized(messageBuffer) {
            messageBuffer.add(m)
        }
    }

    fun snapshotMessages(): List<ChatMessage> {
        synchronized(messageBuffer) {
            return messageBuffer.toList()
        }
    }

    private fun nextId(): Long {
        synchronized(this) {
            val v = idGen
            idGen++
            return v
        }
    }
}
