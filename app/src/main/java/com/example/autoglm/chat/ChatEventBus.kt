package com.example.autoglm.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object ChatEventBus {

    sealed class Event {
        data class StartTaskFromSpeech(val recognizedText: String) : Event()

        data class AppendText(val role: MessageRole, val text: String) : Event()

        data class AppendToLastText(val role: MessageRole, val text: String) : Event()

        data class AppendImage(val imageBytes: ByteArray) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    private val messageBuffer: MutableList<ChatMessage> = mutableListOf()
    private var idGen: Long = 1L

    fun post(event: Event) {
        _events.tryEmit(event)
    }

    fun postText(role: MessageRole, text: String) {
        val t = text.trimEnd()
        if (t.isEmpty()) return
        val m = ChatMessage(
            id = nextId(),
            role = role,
            type = MessageType.TEXT,
            text = t,
        )
        synchronized(messageBuffer) {
            messageBuffer.add(m)
        }
        post(Event.AppendText(role, t))
    }

    fun appendToLastText(role: MessageRole, text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty()) return false

        val appended = synchronized(messageBuffer) {
            val last = messageBuffer.lastOrNull() ?: return@synchronized false
            if (last.role != role) return@synchronized false
            if (last.type != MessageType.TEXT) return@synchronized false
            val lastText = last.text.orEmpty()
            val merged = lastText + t
            messageBuffer[messageBuffer.size - 1] = last.copy(text = merged)
            true
        }

        if (appended) {
            post(Event.AppendToLastText(role, t))
        }
        return appended
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
