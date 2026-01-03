package com.example.autoglm.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.RecyclerView
import com.example.autoglm.R
import com.example.autoglm.ui.GlowPanel
import com.example.autoglm.ui.NeonColors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ChatAdapter(
    private val items: MutableList<ChatMessage> = mutableListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var attachedRv: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    companion object {
        const val VT_TEXT_USER = 1
        const val VT_TEXT_AI = 2
        const val VT_IMAGE_AI = 3

        private const val DISABLE_SCROLL_AWARE_BITMAP_SET_FOR_TEST = true

        private val mainHandler = Handler(Looper.getMainLooper())
        private val decodeExecutor = Executors.newFixedThreadPool(2)

        private val inFlightDecodes: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        private val bitmapCache: LruCache<Long, Bitmap> = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 4L).toInt()) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024

            override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                // Do NOT call Bitmap.recycle() here.
                // RecyclerView/ImageView may still be drawing this bitmap even if it is evicted from cache.
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            if (reqWidth <= 0 || reqHeight <= 0) return 1
            while ((height / inSampleSize) > reqHeight || (width / inSampleSize) > reqWidth) {
                inSampleSize *= 2
            }
            return inSampleSize.coerceAtLeast(1)
        }

        private fun decodeScaled(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
                inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRv = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRv === recyclerView) {
            attachedRv = null
        }
    }

    fun submitAppend(message: ChatMessage) {
        submitAppendBatch(listOf(message))
    }

    fun submitAppendBatch(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val start = items.size
        items.addAll(messages)
        notifyItemRangeInserted(start, messages.size)
    }

    fun updateLastText(role: MessageRole, newText: String): Boolean {
        if (items.isEmpty()) return false
        val lastIndex = items.size - 1
        val last = items[lastIndex]
        if (last.type != MessageType.TEXT) return false
        if (last.role != role) return false
        if (last.text == newText) return true
        items[lastIndex] = last.copy(text = newText)
        notifyItemChanged(lastIndex)
        return true
    }

    fun getLastText(role: MessageRole): String? {
        if (items.isEmpty()) return null
        val last = items[items.size - 1]
        if (last.type != MessageType.TEXT) return null
        if (last.role != role) return null
        return last.text
    }

    fun appendToLastText(role: MessageRole, kind: String?, delta: String): Boolean {
        if (delta.isEmpty()) return false
        if (items.isEmpty()) return false
        var i = items.size - 1
        while (i >= 0) {
            val m = items[i]
            if (m.role == MessageRole.USER) {
                // 以用户消息作为“任务边界”：新任务的 AI/ACTION 不允许合并到上一轮的旧气泡。
                return false
            }
            if (role == MessageRole.AI && m.role == MessageRole.ACTION && m.kind == "STEP") {
                // 以步骤消息作为“阶段边界”：新步骤的 AI/THINK 不允许合并到上一步的 AI 气泡。
                return false
            }
            if (m.type == MessageType.TEXT && m.role == role) {
                // 同 role 但不同 kind，说明已进入新的阶段/步骤，不允许回溯合并到更早的旧气泡。
                if (m.kind != kind) {
                    return false
                }
                val next = (m.text.orEmpty() + delta)
                items[i] = m.copy(text = next)
                notifyItemChanged(i)
                return true
            }
            i--
        }
        return false
    }

    fun getItemCountSafe(): Int = items.size

    override fun getItemId(position: Int): Long {
        return try {
            items[position].id
        } catch (_: Exception) {
            RecyclerView.NO_ID
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        val m = items[position]
        return when {
            m.type == MessageType.IMAGE -> VT_IMAGE_AI
            m.role == MessageRole.USER -> VT_TEXT_USER
            else -> VT_TEXT_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_TEXT_USER -> TextHolder(inflater.inflate(R.layout.item_message_text_user, parent, false), isUser = true)
            VT_TEXT_AI -> TextHolder(inflater.inflate(R.layout.item_message_text_ai, parent, false), isUser = false)
            VT_IMAGE_AI -> ImageHolder(inflater.inflate(R.layout.item_message_image_ai, parent, false))
            else -> TextHolder(inflater.inflate(R.layout.item_message_text_ai, parent, false), isUser = false)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val m = items[position]
        when (holder) {
            is TextHolder -> holder.bind(m.text.orEmpty())
            is ImageHolder -> holder.bind(m.id, m.imageBytes)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ImageHolder) {
            holder.clear()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
    }

    private class TextHolder(itemView: View, private val isUser: Boolean) : RecyclerView.ViewHolder(itemView) {
        private val composeView: ComposeView = itemView.findViewById(R.id.composeContainer)

        @OptIn(ExperimentalFoundationApi::class)
        fun bind(text: String) {
            composeView.setContent {
                GlowPanel(
                    modifier = Modifier.widthIn(max = 280.dp),
                    cornerRadius = if (isUser) 12.dp else 10.dp,
                    bgAlpha = if (isUser) 0.35f else 0.28f,
                    durationBase = 5500,
                ) {
                    Text(
                        text = text,
                        color = if (isUser) NeonColors.neonCyan else NeonColors.textPrimary,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = {
                                try {
                                    val cm = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("chat", text))
                                } catch (_: Exception) {
                                }
                            }
                        )
                    )
                }
            }
        }
    }

    private inner class ImageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivImage: ImageView = itemView.findViewById(R.id.ivImage)

        private fun setBitmapWithScrollAwareness(messageId: Long, bmp: Bitmap?) {
            if (DISABLE_SCROLL_AWARE_BITMAP_SET_FOR_TEST) {
                if (bmp == null || bmp.isRecycled) {
                    try {
                        ivImage.setImageDrawable(null)
                    } catch (_: Exception) {
                    }
                    return
                }
                try {
                    val currentKey = ivImage.tag as? Long
                    if (currentKey != messageId) return
                } catch (_: Exception) {
                }
                try {
                    ivImage.setImageBitmap(bmp)
                } catch (_: Exception) {
                }
                return
            }
            if (bmp == null || bmp.isRecycled) {
                try {
                    ivImage.setImageDrawable(null)
                } catch (_: Exception) {
                }
                return
            }

            val rv = attachedRv

            val maxTries = 200
            fun trySet(tryIdx: Int) {
                val currentKey = ivImage.tag as? Long
                if (currentKey != messageId) return

                val st = try {
                    rv?.scrollState ?: -1
                } catch (_: Exception) {
                    -1
                }

                // Only set bitmap when RecyclerView is truly idle to avoid anchor jumps during fling/drag.
                if (rv != null && st != RecyclerView.SCROLL_STATE_IDLE && tryIdx < maxTries) {
                    rv.postDelayed({ trySet(tryIdx + 1) }, 32L)
                    return
                }

                // If we still can't reach IDLE within the wait window, don't force-set during SETTLING.
                if (rv != null && st == RecyclerView.SCROLL_STATE_SETTLING) {
                    return
                }

                try {
                    val st2 = try {
                        rv?.scrollState ?: -1
                    } catch (_: Exception) {
                        -1
                    }
                    Log.w("ChatScrollDebug", "Image setBitmap id=$messageId pos=${bindingAdapterPosition} state=$st2 t=${SystemClock.uptimeMillis()} try=$tryIdx")
                } catch (_: Exception) {
                }

                try {
                    ivImage.setImageBitmap(bmp)
                } catch (_: Exception) {
                }
            }

            if (rv != null) {
                rv.post { trySet(0) }
            } else {
                trySet(maxTries)
            }
        }

        fun clear() {
            try {
                ivImage.tag = null
            } catch (_: Exception) {
            }
            try {
                ivImage.setImageDrawable(null)
            } catch (_: Exception) {
            }
        }

        fun bind(messageId: Long, bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty()) {
                try {
                    ivImage.setImageDrawable(null)
                } catch (_: Exception) {
                }
                return
            }

            val key = messageId
            ivImage.tag = key

            val reqWidth = try {
                val w = ivImage.width
                if (w > 0) w else (240f * itemView.resources.displayMetrics.density).toInt()
            } catch (_: Exception) {
                (240f * itemView.resources.displayMetrics.density).toInt()
            }
            val reqHeight = try {
                val h = ivImage.height
                if (h > 0) h else (420f * itemView.resources.displayMetrics.density).toInt()
            } catch (_: Exception) {
                (420f * itemView.resources.displayMetrics.density).toInt()
            }

            val cached = bitmapCache.get(key)
            if (cached != null && !cached.isRecycled) {
                // Cached bitmap is already in memory; show it immediately to avoid perceived reload/flicker.
                // If the same Bitmap instance is already displayed, skip to avoid redundant invalidations.
                val currentBmp = try {
                    ivImage.drawable
                } catch (_: Exception) {
                    null
                }
                val alreadyShowing = try {
                    (currentBmp as? android.graphics.drawable.BitmapDrawable)?.bitmap === cached
                } catch (_: Exception) {
                    false
                }
                if (!alreadyShowing) {
                    try {
                        Log.w("ChatScrollDebug", "Image setCachedImmediate id=$messageId pos=${bindingAdapterPosition} t=${SystemClock.uptimeMillis()}")
                    } catch (_: Exception) {
                    }
                    try {
                        ivImage.setImageBitmap(cached)
                    } catch (_: Exception) {
                    }
                }
                return
            }

            try {
                ivImage.setImageDrawable(null)
            } catch (_: Exception) {
            }

            decodeExecutor.execute {
                if (!inFlightDecodes.add(key)) {
                    return@execute
                }
                val bmp = try {
                    decodeScaled(bytes, reqWidth = reqWidth.coerceAtLeast(1), reqHeight = reqHeight.coerceAtLeast(1))
                } catch (_: Exception) {
                    null
                }

                try {
                    inFlightDecodes.remove(key)
                } catch (_: Exception) {
                }

                if (bmp != null && !bmp.isRecycled) {
                    try {
                        bitmapCache.put(key, bmp)
                    } catch (_: Exception) {
                    }
                }

                mainHandler.post {
                    val currentKey = ivImage.tag as? Long
                    if (currentKey != key) return@post
                    try {
                        setBitmapWithScrollAwareness(messageId, bmp)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
