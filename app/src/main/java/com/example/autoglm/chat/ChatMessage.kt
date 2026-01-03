package com.example.autoglm.chat

enum class MessageRole {
    USER,
    AI,
    ACTION,
}

enum class MessageType {
    TEXT,
    IMAGE,
}

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val type: MessageType,
    val kind: String? = null,
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val timeMs: Long = System.currentTimeMillis(),
)
