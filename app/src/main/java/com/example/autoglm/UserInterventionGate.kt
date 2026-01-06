package com.example.autoglm

import android.content.Intent

/**
 * 用户介入闸门（敏感操作确认/人工接管）。
 *
 * **用途**
 * - 在自动化执行过程中，对“敏感动作”进行二次确认，或触发“人工接管”流程。
 * - 通过进程内状态 + 广播协议，把请求同步到：
 *   - `ChatActivity`（顶部确认条/弹窗）
 *   - `FloatingStatusService`（悬浮窗按钮）
 *   - `ChatEventBus`（聊天流提示，避免用户漏看）
 *
 * **对外协议**
 * - 广播 Action：
 *   - [ACTION_INTERVENTION_REQUEST]：发起请求
 *   - [ACTION_INTERVENTION_CLEAR]：清理请求
 * - Extra：
 *   - [EXTRA_REQUEST_ID] / [EXTRA_REQUEST_TYPE] / [EXTRA_REQUEST_MESSAGE]
 *
 * **典型用法**
 * - 需要确认：`val ok = UserInterventionGate.requestConfirmation("是否允许...")`
 * - 人工接管：`UserInterventionGate.requestTakeover("请手动完成登录")`
 * - UI 回传结果：`UserInterventionGate.respond(id, approved)`
 *
 * **使用注意事项**
 * - 该实现为阻塞等待（wait/notify）：调用方务必在后台线程执行，避免阻塞主线程。
 * - 为兼容 MIUI 等“隐式广播不稳定”情况，同时通过 `ChatEventBus` 做进程内兜底。
 */
object UserInterventionGate {

    const val ACTION_INTERVENTION_REQUEST = "com.example.autoglm.action.INTERVENTION_REQUEST"
    const val ACTION_INTERVENTION_CLEAR = "com.example.autoglm.action.INTERVENTION_CLEAR"

    const val EXTRA_REQUEST_ID = "extra_intervention_request_id"
    const val EXTRA_REQUEST_TYPE = "extra_intervention_request_type"
    const val EXTRA_REQUEST_MESSAGE = "extra_intervention_request_message"

    private const val TYPE_CONFIRMATION = "CONFIRMATION"
    private const val TYPE_TAKEOVER = "TAKEOVER"

    private val lock = Object()

    @Volatile
    private var nextId: Long = 1L

    @Volatile
    private var pendingId: Long = 0L

    @Volatile
    private var pendingType: String = ""

    @Volatile
    private var pendingMessage: String = ""

    @Volatile
    private var pendingResult: Boolean? = null

    @JvmStatic
    fun requestConfirmation(message: String): Boolean {
        val msg = (message ?: "").trim()
        if (msg.isEmpty()) return false

        val id = allocateId()
        publishRequest(id, TYPE_CONFIRMATION, msg)

        return waitForResult(id) ?: false
    }

    @JvmStatic
    fun requestTakeover(message: String) {
        val msg = (message ?: "").trim()
        if (msg.isEmpty()) return

        val id = allocateId()
        publishRequest(id, TYPE_TAKEOVER, msg)

        // 人工接管：只需要等待用户点击“完成/同意”。
        waitForResult(id)
    }

    @JvmStatic
    fun respond(requestId: Long, approved: Boolean) {
        synchronized(lock) {
            if (pendingId != requestId || pendingId <= 0L) return
            pendingResult = approved
            lock.notifyAll()
        }
    }

    @JvmStatic
    fun getPendingRequestId(): Long = pendingId

    @JvmStatic
    fun getPendingRequestType(): String = pendingType

    @JvmStatic
    fun getPendingRequestMessage(): String = pendingMessage

    private fun allocateId(): Long {
        synchronized(this) {
            val v = nextId
            nextId++
            return v
        }
    }

    private fun publishRequest(id: Long, type: String, msg: String) {
        synchronized(lock) {
            pendingId = id
            pendingType = type
            pendingMessage = msg
            pendingResult = null
        }

        // 进程内兜底：MIUI 等系统偶发不投递隐式广播给动态 receiver。
        // 这里同步发一份给 ChatActivity（通过其已在 onCreate 中 collect 的 EventBus）。
        runCatching {
            com.example.autoglm.chat.ChatEventBus.postInterventionRequest(id, type, msg)
        }

        // 通过广播通知 ChatActivity 显示顶部确认条。
        val ctx = AppState.getAppContext() ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(ACTION_INTERVENTION_REQUEST).apply {
                    putExtra(EXTRA_REQUEST_ID, id)
                    putExtra(EXTRA_REQUEST_TYPE, type)
                    putExtra(EXTRA_REQUEST_MESSAGE, msg)
                }
            )
        }

        // 同步更新悬浮窗：显示“同意/拒绝/完成”按钮。
        runCatching {
            FloatingStatusService.setInterventionState(ctx, id, type, msg)
        }

        // 在聊天消息里也给出提示，避免用户没注意到顶部条。
        runCatching {
            val title = if (type == TYPE_TAKEOVER) "需要人工接管" else "需要敏感操作确认"
            com.example.autoglm.chat.ChatEventBus.postText(
                com.example.autoglm.chat.MessageRole.ACTION,
                "$title：$msg"
            )
        }
    }

    private fun clearRequest(id: Long) {
        val ctx = AppState.getAppContext()
        synchronized(lock) {
            if (pendingId != id) return
            pendingId = 0L
            pendingType = ""
            pendingMessage = ""
            pendingResult = null
        }

        // 进程内兜底：让前台 ChatActivity 立即隐藏提示条。
        runCatching {
            com.example.autoglm.chat.ChatEventBus.postInterventionClear(id)
        }

        if (ctx != null) {
            runCatching {
                ctx.sendBroadcast(Intent(ACTION_INTERVENTION_CLEAR).apply {
                    putExtra(EXTRA_REQUEST_ID, id)
                })
            }
            runCatching {
                FloatingStatusService.clearInterventionState(ctx, id)
            }
        }
    }

    private fun waitForResult(id: Long): Boolean? {
        try {
            while (TaskControl.shouldContinue()) {
                synchronized(lock) {
                    if (pendingId != id) return pendingResult
                    val r = pendingResult
                    if (r != null) {
                        clearRequest(id)
                        return r
                    }
                    lock.wait(250L)
                }
            }
        } catch (_: InterruptedException) {
        }

        // 用户停止/线程中断：认为拒绝并清理。
        clearRequest(id)
        return false
    }
}
