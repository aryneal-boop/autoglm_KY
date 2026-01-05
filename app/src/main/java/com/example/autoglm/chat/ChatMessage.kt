package com.example.autoglm.chat

/**
 * 聊天消息角色。
 *
 * - USER：用户输入
 * - AI：模型/助手输出（含思考/最终回复等）
 * - ACTION：执行过程与动作日志（例如“正在点击/正在查阅屏幕/步骤”等）
 */
enum class MessageRole {
    USER,
    AI,
    ACTION,
}

/**
 * 聊天消息类型。
 *
 * - TEXT：文本消息
 * - IMAGE：图片消息（通常为截图）
 */
enum class MessageType {
    TEXT,
    IMAGE,
}

/**
 * 聊天消息数据模型。
 *
 * **用途**
 * - 作为 `ChatAdapter` 的渲染输入，统一承载文本/图片两类消息。
 *
 * **字段说明**
 * - `id`：稳定 id，用于 RecyclerView diff/稳定复用。
 * - `role`：消息来源角色（见 [MessageRole]）。
 * - `type`：消息类型（见 [MessageType]）。
 * - `kind`：可选的细分类型（例如 `STEP/STATUS/THINK`），用于不同样式渲染。
 * - `text`：文本内容（type=TEXT）。
 * - `imageBytes`：图片内容（type=IMAGE），通常为 PNG/JPEG bytes。
 * - `timeMs`：创建时间戳。
 */
data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val type: MessageType,
    val kind: String? = null,
    val text: String? = null,
    val imageBytes: ByteArray? = null,
    val timeMs: Long = System.currentTimeMillis(),
)
