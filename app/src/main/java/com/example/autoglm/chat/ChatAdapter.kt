package com.example.autoglm.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

class ChatAdapter(
    private val items: MutableList<ChatMessage> = mutableListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VT_TEXT_USER = 1
        private const val VT_TEXT_AI = 2
        private const val VT_IMAGE_AI = 3
    }

    fun submitAppend(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
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

    fun appendToLastText(role: MessageRole, delta: String): Boolean {
        if (delta.isEmpty()) return false
        if (items.isEmpty()) return false
        val lastIndex = items.size - 1
        val last = items[lastIndex]
        if (last.type != MessageType.TEXT) return false
        if (last.role != role) return false
        val next = (last.text.orEmpty() + delta)
        items[lastIndex] = last.copy(text = next)
        notifyItemChanged(lastIndex)
        return true
    }

    fun getItemCountSafe(): Int = items.size

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
            is ImageHolder -> holder.bind(m.imageBytes)
        }
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

    private class ImageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivImage: ImageView = itemView.findViewById(R.id.ivImage)
        fun bind(bytes: ByteArray?) {
            if (bytes == null || bytes.isEmpty()) return
            try {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ivImage.setImageBitmap(bmp)
            } catch (_: Exception) {
            }
        }
    }
}
