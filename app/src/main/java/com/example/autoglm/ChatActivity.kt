package com.example.autoglm

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.os.Build
import android.app.ActivityOptions
import android.provider.Settings
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoglm.ui.GlowButton
import com.example.autoglm.ui.NeonColors
import com.example.autoglm.ui.NeonLiquidBackground
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.app.AlertDialog
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.autoglm.chat.ChatAdapter
import com.example.autoglm.chat.ChatEventBus
import com.example.autoglm.chat.ChatMessage
import com.example.autoglm.chat.MessageRole
import com.example.autoglm.chat.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import rikka.shizuku.Shizuku

class ChatActivity : ComponentActivity() {

    private val scrollDebugTag = "ChatScrollDebug"

    private val enableScrollDebug: Boolean = false

    private var lastUserTouchUptime: Long = 0L

    private var lastScrollOffset: Int = -1
    private var lastScrollUptime: Long = 0L

    private var lastAnchorPos: Int = RecyclerView.NO_POSITION
    private var lastAnchorTop: Int = 0

    private val idGen = AtomicLong(1L)

    private lateinit var tvAdbStatus: TextView
    private lateinit var tvModelName: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var btnSettings: ImageButton
    private lateinit var composeInputBar: ComposeView
    private lateinit var composeBackground: ComposeView

    private lateinit var voiceOverlay: View
    private lateinit var tvVoiceOverlay: TextView

    // Compose state for input bar
    private var inputText = mutableStateOf("")
    private var sendEnabled = mutableStateOf(false)

    private var interventionVisible = mutableStateOf(false)
    private var interventionRequestId = mutableStateOf(0L)
    private var interventionRequestType = mutableStateOf("")
    private var interventionRequestMessage = mutableStateOf("")

    private var interventionReceiver: BroadcastReceiver? = null

    private lateinit var adapter: ChatAdapter
    private lateinit var rootContainer: View

    private var userDraggingMessages: Boolean = false
    private var followBottom: Boolean = true

    private var isRunningTask: Boolean = false
    private var mergeStreamAfterStop: Boolean = false
    private var lastActionStreamKind: String? = null
    private var lastAiStreamKind: String? = null

    private val pendingUiAppends: MutableList<ChatMessage> = ArrayList()
    private var pendingUiAppendFlushPosted: Boolean = false
    private var lastProgrammaticScrollAtUptime: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingAutoHideRunnable: Runnable? = null

    private data class StreamAppendKey(val role: MessageRole, val kind: String?)

    private val pendingAppendText: MutableMap<StreamAppendKey, StringBuilder> = HashMap()
    @Volatile
    private var pendingAppendScheduled = false
    private val flushPendingAppendRunnable = Runnable {
        pendingAppendScheduled = false
        val entries = ArrayList(pendingAppendText.entries)
        pendingAppendText.clear()

        var anyUpdated = false
        for ((k, sb) in entries) {
            val role = k.role
            val kind = k.kind
            val delta = sb.toString()
            if (delta.isEmpty()) continue
            if (adapter.appendToLastText(role, kind, delta)) {
                anyUpdated = true
            } else {
                appendTextInternal(role, delta, syncToEventBusBuffer = true, kind = kind)
                // 流式期间如果创建了新气泡，需要立即落地到 adapter，避免后续 token 抢跑导致刷屏。
                if (isRunningTask || mergeStreamAfterStop) {
                    flushUiAppendsNow()
                }
                anyUpdated = true
            }
        }

        if (anyUpdated) {
            forceScrollToBottom(smooth = true)
        }
    }

    private fun hideInterventionUiLocalIfMatching(requestId: Long) {
        runOnUiThread {
            if (requestId <= 0L) return@runOnUiThread
            if (interventionRequestId.value != requestId) return@runOnUiThread
            interventionVisible.value = false
            interventionRequestId.value = 0L
            interventionRequestType.value = ""
            interventionRequestMessage.value = ""
            sendEnabled.value = inputText.value.isNotBlank()
            setupComposeInputBar()
        }
    }

    private fun syncPendingInterventionFromGate() {
        val id = try {
            UserInterventionGate.getPendingRequestId()
        } catch (_: Exception) {
            0L
        }
        val type = try {
            UserInterventionGate.getPendingRequestType().orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
        val msg = try {
            UserInterventionGate.getPendingRequestMessage().orEmpty().trim()
        } catch (_: Exception) {
            ""
        }

        runOnUiThread {
            if (id > 0L) {
                val changed = interventionRequestId.value != id ||
                    interventionRequestType.value != type ||
                    interventionRequestMessage.value != msg ||
                    !interventionVisible.value
                interventionRequestId.value = id
                interventionRequestType.value = type
                interventionRequestMessage.value = msg
                interventionVisible.value = true
                sendEnabled.value = false
                if (changed) {
                    setupComposeInputBar()
                }
            } else {
                if (interventionVisible.value || interventionRequestId.value != 0L) {
                    interventionVisible.value = false
                    interventionRequestId.value = 0L
                    interventionRequestType.value = ""
                    interventionRequestMessage.value = ""
                    sendEnabled.value = inputText.value.isNotBlank()
                    setupComposeInputBar()
                }
            }
        }
    }

    private fun scheduleFlushUiAppends() {
        if (pendingUiAppendFlushPosted) return
        pendingUiAppendFlushPosted = true
        mainHandler.postDelayed({
            pendingUiAppendFlushPosted = false
            if (pendingUiAppends.isEmpty()) return@postDelayed
            val batch = ArrayList(pendingUiAppends)
            pendingUiAppends.clear()

            adapter.submitAppendBatch(batch)
            forceScrollToBottom(smooth = false)
        }, 50L)
    }

    private fun flushUiAppendsNow() {
        try {
            pendingUiAppendFlushPosted = false
            if (pendingUiAppends.isEmpty()) return
            val batch = ArrayList(pendingUiAppends)
            pendingUiAppends.clear()
            adapter.submitAppendBatch(batch)
            forceScrollToBottom(smooth = false)
        } catch (_: Exception) {
        }
    }

    private fun enqueueUiAppend(message: ChatMessage) {
        pendingUiAppends.add(message)
        scheduleFlushUiAppends()
    }

    private fun appendText(role: MessageRole, text: String) {
        appendTextInternal(role, text, syncToEventBusBuffer = true, kind = null)
    }

    private fun appendTextInternal(role: MessageRole, text: String, syncToEventBusBuffer: Boolean, kind: String?) {
        val t = text.trimEnd()
        if (t.isEmpty()) return
        if (syncToEventBusBuffer) {
            ChatEventBus.recordText(role, t, kind = kind)
        }
        runOnUiThread {
            val m = ChatMessage(
                id = idGen.getAndIncrement(),
                role = role,
                type = MessageType.TEXT,
                kind = kind,
                text = t,
            )
            enqueueUiAppend(m)
        }
    }

    private fun updateFloatingStatusFromAction(kind: String?, text: String) {
        // 根据动作类型更新悬浮窗文案（若悬浮窗不可用则忽略）。
        try {
            val display = OverlayStatusTextMapper.map(kind, text) ?: text
            FloatingStatusService.update(this@ChatActivity, display)
        } catch (_: Exception) {
        }
    }

    private fun updateAdbStatus() {
        // 仅用于 UI 展示，不强制触发 connect/stop 等副作用。
        lifecycleScope.launch {
            val mode = getCurrentConnectMode()
            val status = if (mode == ConfigManager.ADB_MODE_SHIZUKU) {
                val ok = try {
                    Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
                if (ok) {
                    "模式：Shizuku\n状态：已授权"
                } else {
                    "模式：Shizuku\n状态：未授权"
                }
            } else {
                val last = try {
                    ConfigManager(this@ChatActivity).getLastAdbEndpoint().trim()
                } catch (_: Exception) {
                    ""
                }
                if (last.isNotEmpty()) {
                    "模式：无线调试\n最近：$last"
                } else {
                    "模式：无线调试\n状态：未连接"
                }
            }
            runOnUiThread {
                try {
                    tvAdbStatus.text = status
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun computeInternalAdbPath(): String {
        val adbPath = File(filesDir, "bin/adb").absolutePath
        val adbBinPath = File(filesDir, "bin/adb.bin").absolutePath
        val adbBinFile = File(adbBinPath)
        return if (adbBinFile.exists() && adbBinFile.length() > 0L) adbBinPath else adbPath
    }

    private var stopReceiver: BroadcastReceiver? = null

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingThread: Thread? = null
    private var recordingFile: File? = null
    @Volatile
    private var isRecordingLoop: Boolean = false
    private var isRecordingVoice: Boolean = false
    private var isVoiceMode: Boolean = false

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private var holdDownAtMs: Long = 0L

    private fun showVoiceOverlayListening() {
        try {
            voiceOverlay.visibility = View.VISIBLE
            tvVoiceOverlay.text = VoicePromptText.LISTENING
        } catch (_: Exception) {
        }
    }

    private fun showVoiceOverlayRecognizing() {
        try {
            voiceOverlay.visibility = View.VISIBLE
            tvVoiceOverlay.text = VoicePromptText.RECOGNIZING
        } catch (_: Exception) {
        }
    }

    private fun hideVoiceOverlay() {
        try {
            voiceOverlay.visibility = View.GONE
        } catch (_: Exception) {
        }
    }

    private fun requestRecordingAudioFocus(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val listener = AudioManager.OnAudioFocusChangeListener { }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                audioFocusListener = listener
                audioFocusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun abandonRecordingAudioFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest
                if (req != null) {
                    am.abandonAudioFocusRequest(req)
                }
                audioFocusRequest = null
                audioFocusListener = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRecordingInternal(cancel: Boolean) {
        isRecordingLoop = false
        isRecordingVoice = false

        val ar = audioRecord
        audioRecord = null
        try {
            ar?.stop()
        } catch (_: Exception) {
        }
        try {
            ar?.release()
        } catch (_: Exception) {
        }

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null

        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null

        val t = recordingThread
        recordingThread = null
        try {
            t?.interrupt()
        } catch (_: Exception) {
        }

        val f = recordingFile
        recordingFile = null
        if (f != null) {
            if (cancel) {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
            } else {
                // 回填 WAV header（单声道、16k、16bit）。
                try {
                    val totalLen = f.length().coerceAtLeast(44L)
                    val dataLen = (totalLen - 44L).coerceAtLeast(0L)
                    val byteRate = 16000 * 1 * 16 / 8
                    RandomAccessFile(f, "rw").use { raf ->
                        raf.seek(0)
                        raf.writeBytes("RIFF")
                        raf.writeInt(Integer.reverseBytes((36L + dataLen).toInt()))
                        raf.writeBytes("WAVE")
                        raf.writeBytes("fmt ")
                        raf.writeInt(Integer.reverseBytes(16))
                        raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                        raf.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                        raf.writeInt(Integer.reverseBytes(16000))
                        raf.writeInt(Integer.reverseBytes(byteRate))
                        raf.writeShort(java.lang.Short.reverseBytes((1 * 16 / 8).toShort()).toInt())
                        raf.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
                        raf.writeBytes("data")
                        raf.writeInt(Integer.reverseBytes(dataLen.toInt()))
                    }
                } catch (_: Exception) {
                }
            }
        }

        abandonRecordingAudioFocus()
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // 权限通过：如果用户正在按住说话或通过悬浮窗触发，会在后续继续流程。
        } else {
            appendText(MessageRole.ACTION, "未授予录音权限，无法使用语音识别。")
            FloatingStatusService.setIdle(this, "等待指令")
        }
    }

    private fun relaunchToMainDisplayIfNeeded(): Boolean {
        val currentDisplayId = runCatching { display?.displayId ?: 0 }.getOrElse { 0 }
        if (currentDisplayId == 0) return false
        if (intent?.getBooleanExtra(EXTRA_RELAUNCHED_TO_MAIN, false) == true) return false

        return runCatching {
            val i = Intent(this, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_RELAUNCHED_TO_MAIN, true)
            }
            val opts = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(0)
            }
            startActivity(i, opts.toBundle())
            finish()
            true
        }.getOrElse { false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (relaunchToMainDisplayIfNeeded()) {
            return
        }
        setContentView(R.layout.activity_chat)

        try {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        } catch (_: Exception) {
        }

        rootContainer = findViewById(R.id.rootContainer)

        tvAdbStatus = findViewById(R.id.tvAdbStatus)
        tvModelName = findViewById(R.id.tvModelName)
        rvMessages = findViewById(R.id.rvMessages)
        btnSettings = findViewById(R.id.btnSettings)
        composeInputBar = findViewById(R.id.composeInputBar)
        composeBackground = findViewById(R.id.composeBackground)

        ensureFloatingStatusReady()

        composeBackground.setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFF0A0E14))
            ) {
                NeonLiquidBackground(modifier = Modifier.fillMaxSize())
            }
        }

        voiceOverlay = findViewById(R.id.voiceOverlay)
        tvVoiceOverlay = findViewById(R.id.tvVoiceOverlay)

        adapter = ChatAdapter()
        rvMessages.layoutManager = object : LinearLayoutManager(this) {
            override fun supportsPredictiveItemAnimations(): Boolean {
                return false
            }
        }.apply {
            stackFromEnd = true
        }
        rvMessages.adapter = adapter

        try {
            rvMessages.itemAnimator = null
        } catch (_: Exception) {
        }

        try {
            rvMessages.setItemViewCacheSize(50)
        } catch (_: Exception) {
        }

        try {
            rvMessages.recycledViewPool.setMaxRecycledViews(com.example.autoglm.chat.ChatAdapter.VT_IMAGE_AI, 50)
        } catch (_: Exception) {
        }

        try {
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                private fun logChange(event: String) {
                    if (!enableScrollDebug) return

                    try {
                        val st = Throwable().stackTrace
                            .drop(1)
                            .take(10)
                            .joinToString(" | ") { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
                        Log.w(scrollDebugTag, "AdapterDataObserver $event t=${SystemClock.uptimeMillis()} stack=$st")
                    } catch (_: Exception) {
                    }
                    dumpScrollState("AdapterDataObserver $event")
                }

                override fun onChanged() {
                    logChange("onChanged")
                }

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    logChange("onItemRangeInserted start=$positionStart count=$itemCount")
                }

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    logChange("onItemRangeRemoved start=$positionStart count=$itemCount")
                }

                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    logChange("onItemRangeChanged start=$positionStart count=$itemCount")
                }

                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    logChange("onItemRangeMoved from=$fromPosition to=$toPosition count=$itemCount")
                }
            })
        } catch (_: Exception) {
        }
        try {
            rvMessages.itemAnimator = null
        } catch (_: Exception) {
        }

        try {
            rvMessages.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        lastUserTouchUptime = SystemClock.uptimeMillis()
                    }
                }
                false
            }
        } catch (_: Exception) {
        }

        try {
            rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    userDraggingMessages = newState == RecyclerView.SCROLL_STATE_DRAGGING
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        followBottom = isNearBottom()
                    }

                    val now = SystemClock.uptimeMillis()
                    val sinceTouch = now - lastUserTouchUptime
                    if (!userDraggingMessages && newState == RecyclerView.SCROLL_STATE_SETTLING && sinceTouch > 400L) {
                        dumpScrollState("NonTouch state->SETTLING sinceTouch=${sinceTouch}ms")
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (DISABLE_PROGRAMMATIC_SCROLL_FOR_TEST) {
                        return
                    }
                    val now = SystemClock.uptimeMillis()
                    val sinceTouch = now - lastUserTouchUptime

                    val anchorView = try {
                        recyclerView.getChildAt(0)
                    } catch (_: Exception) {
                        null
                    }
                    val anchorPos = try {
                        if (anchorView != null) recyclerView.getChildAdapterPosition(anchorView) else RecyclerView.NO_POSITION
                    } catch (_: Exception) {
                        RecyclerView.NO_POSITION
                    }
                    val anchorTop = try {
                        anchorView?.top ?: 0
                    } catch (_: Exception) {
                        0
                    }

                    val offsetNow = try {
                        recyclerView.computeVerticalScrollOffset()
                    } catch (_: Exception) {
                        -1
                    }

                    // Anchor-based jump detection (more reliable than computeVerticalScrollOffset on variable-height lists).
                    if (!userDraggingMessages && dy != 0 && lastAnchorPos != RecyclerView.NO_POSITION && anchorPos != RecyclerView.NO_POSITION) {
                        val dPos = anchorPos - lastAnchorPos
                        val dTop = anchorTop - lastAnchorTop
                        // When the first visible item changes (dPos != 0), dTop can jump by an entire item height
                        // and that is normal. Only treat it as a jump when the direction is inconsistent.
                        val posDirMismatch = (dy > 0 && dPos < 0) || (dy < 0 && dPos > 0)
                        // Only evaluate top-direction mismatch when the anchor item itself did not change.
                        val topDirMismatch = (dPos == 0) && ((dy > 0 && dTop > 200) || (dy < 0 && dTop < -200))
                        if (sinceTouch > 200L && (posDirMismatch || topDirMismatch)) {
                            dumpScrollState(
                                "AnchorJump dy=$dy dPos=$dPos dTop=$dTop anchorPos=$anchorPos"
                            )
                        }
                    }

                    // Detect anchor/offset jumps: offset changes direction or magnitude doesn't match dy.
                    if (!userDraggingMessages && dy != 0 && lastScrollOffset >= 0 && offsetNow >= 0) {
                        val dOffset = offsetNow - lastScrollOffset
                        val dirMismatch = (dy > 0 && dOffset < -50) || (dy < 0 && dOffset > 50)
                        if (sinceTouch > 200L && dirMismatch) {
                            dumpScrollState(
                                "OffsetJump dy=$dy dOffset=$dOffset sinceTouch=${sinceTouch}ms"
                            )
                        }
                    }

                    // Fling inertia after ACTION_UP is expected; only warn when no recent touch and not settling.
                    val stateNow = try {
                        recyclerView.scrollState
                    } catch (_: Exception) {
                        -1
                    }
                    if (!userDraggingMessages && sinceTouch > 1200L && dy != 0 && stateNow != RecyclerView.SCROLL_STATE_SETTLING) {
                        dumpScrollState("NonTouch onScrolled dy=$dy sinceTouch=${sinceTouch}ms")
                    }

                    lastScrollOffset = offsetNow
                    lastScrollUptime = now

                    lastAnchorPos = anchorPos
                    lastAnchorTop = anchorTop
                }
            })
        } catch (_: Exception) {
        }

        try {
            val history = ChatEventBus.snapshotMessages()
            adapter.submitAppendBatch(history)
            scrollToBottom()
        } catch (_: Exception) {
        }

        setupInsetsAndAutoScroll()

        val cfg = ConfigManager(this).getConfig()
        tvModelName.text = "模型：${cfg.modelName}"
        updateAdbStatus()

        // 启动时自动申请录音权限：避免用户点击麦克风时再弹窗导致体验割裂。
        try {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } catch (_: Exception) {
        }

        // 设置 Compose 输入栏
        setupComposeInputBar()

        btnSettings.setOnClickListener {
            try {
                startActivity(Intent(this, SettingsActivity::class.java))
            } catch (_: Exception) {
            }
        }

        appendText(MessageRole.AI, "欢迎回来，执行官。我是 AutoGLM。\n\n系统已就绪，等待您的下一步指令。")

        try {
            handleConfirmRedirectIntentIfNeeded(intent)
            handleOverlayMicIntentIfNeeded(intent)
        } catch (_: Exception) {
        }

        stopReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    FloatingStatusService.ACTION_STOP_CLICKED -> {
                        handleForceStopAndCleanup("已停止：用户点击停止按钮。")
                    }

                    FloatingStatusService.ACTION_START_TASK -> {
                        val text = intent.getStringExtra(FloatingStatusService.EXTRA_TASK_TEXT).orEmpty().trim()
                        val onlyHash = text.isNotEmpty() && text.all { it == '#' }
                        if (text.isEmpty() || onlyHash) {
                            appendText(MessageRole.ACTION, "没听清，请重试")
                            FloatingStatusService.setIdle(this@ChatActivity, "没听清，请重试")
                            mainHandler.postDelayed({
                                FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                            }, 3000L)
                            return
                        }
                        lifecycleScope.launch {
                            if (!checkConnectMethodAvailable()) {
                                fallbackToPairing()
                                return@launch
                            }
                            appendText(MessageRole.USER, text)
                            flushUiAppendsNow()
                            followBottom = true
                            scrollToBottom()
                            runPythonTask(text)
                        }
                    }

                    FloatingStatusService.ACTION_MIC_CLICKED -> {
                        // 兼容旧行为：点击触发切换到语音模式
                        setVoiceMode(true)
                    }

                    FloatingStatusService.ACTION_MIC_DOWN -> {
                        startHoldToTalkFromOverlay()
                    }

                    FloatingStatusService.ACTION_MIC_UP -> {
                        stopHoldToTalkFromOverlay()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(FloatingStatusService.ACTION_STOP_CLICKED)
            addAction(FloatingStatusService.ACTION_MIC_CLICKED)
            addAction(FloatingStatusService.ACTION_MIC_DOWN)
            addAction(FloatingStatusService.ACTION_MIC_UP)
            addAction(FloatingStatusService.ACTION_START_TASK)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }

        interventionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UserInterventionGate.ACTION_INTERVENTION_REQUEST -> {
                        val id = try {
                            intent.getLongExtra(UserInterventionGate.EXTRA_REQUEST_ID, 0L)
                        } catch (_: Exception) {
                            0L
                        }
                        val type = try {
                            intent.getStringExtra(UserInterventionGate.EXTRA_REQUEST_TYPE).orEmpty().trim()
                        } catch (_: Exception) {
                            ""
                        }
                        val msg = try {
                            intent.getStringExtra(UserInterventionGate.EXTRA_REQUEST_MESSAGE).orEmpty().trim()
                        } catch (_: Exception) {
                            ""
                        }
                        if (id > 0L) {
                            runOnUiThread {
                                interventionRequestId.value = id
                                interventionRequestType.value = type
                                interventionRequestMessage.value = msg
                                interventionVisible.value = true
                                // 提示期间禁止发送普通指令，避免误触继续执行。
                                sendEnabled.value = false
                                setupComposeInputBar()
                            }
                        }
                    }

                    UserInterventionGate.ACTION_INTERVENTION_CLEAR -> {
                        val id = try {
                            intent.getLongExtra(UserInterventionGate.EXTRA_REQUEST_ID, 0L)
                        } catch (_: Exception) {
                            0L
                        }
                        runOnUiThread {
                            if (id <= 0L || interventionRequestId.value == id) {
                                interventionVisible.value = false
                                interventionRequestId.value = 0L
                                interventionRequestType.value = ""
                                interventionRequestMessage.value = ""
                                sendEnabled.value = inputText.value.isNotBlank()
                                setupComposeInputBar()
                            }
                        }
                    }
                }
            }
        }
        val iFilter = IntentFilter().apply {
            addAction(UserInterventionGate.ACTION_INTERVENTION_REQUEST)
            addAction(UserInterventionGate.ACTION_INTERVENTION_CLEAR)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(interventionReceiver, iFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(interventionReceiver, iFilter)
        }

        // 兜底：如果 ChatActivity 启动/恢复时错过了广播（例如请求发生在其它 app 前台），
        // 这里主动同步一次 pending 状态，确保聊天界面也能看到按钮。
        syncPendingInterventionFromGate()

        lifecycleScope.launch {
            ChatEventBus.events.collect { ev ->
                when (ev) {
                    is ChatEventBus.Event.InterventionRequest -> {
                        val id = try {
                            ev.requestId
                        } catch (_: Exception) {
                            0L
                        }
                        val type = try {
                            ev.requestType
                        } catch (_: Exception) {
                            ""
                        }
                        val msg = try {
                            ev.message
                        } catch (_: Exception) {
                            ""
                        }
                        if (id > 0L) {
                            runOnUiThread {
                                interventionRequestId.value = id
                                interventionRequestType.value = type
                                interventionRequestMessage.value = msg
                                interventionVisible.value = true
                                sendEnabled.value = false
                                setupComposeInputBar()
                            }
                        }
                    }

                    is ChatEventBus.Event.InterventionClear -> {
                        val id = try {
                            ev.requestId
                        } catch (_: Exception) {
                            0L
                        }
                        runOnUiThread {
                            if (id <= 0L || interventionRequestId.value == id) {
                                interventionVisible.value = false
                                interventionRequestId.value = 0L
                                interventionRequestType.value = ""
                                interventionRequestMessage.value = ""
                                sendEnabled.value = inputText.value.isNotBlank()
                                setupComposeInputBar()
                            }
                        }
                    }

                    is ChatEventBus.Event.StartTaskFromSpeech -> {
                        val text = ev.recognizedText.trim()
                        val onlyHash = text.isNotEmpty() && text.all { it == '#' }
                        if (text.isEmpty() || onlyHash) {
                            appendText(MessageRole.ACTION, "没听清，请重试")
                            FloatingStatusService.setIdle(this@ChatActivity, "没听清，请重试")
                            mainHandler.postDelayed({
                                FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                            }, 3000L)
                            return@collect
                        }
                        if (!checkConnectMethodAvailable()) {
                            fallbackToPairing()
                            return@collect
                        }
                        appendText(MessageRole.USER, text)
                        flushUiAppendsNow()
                        followBottom = true
                        scrollToBottom()
                        runPythonTask(text)
                        appendText(MessageRole.AI, "正在为您执行...")
                    }

                    is ChatEventBus.Event.AppendText -> {
                        if (ev.role == MessageRole.USER) {
                            try {
                                lastActionStreamKind = null
                                lastAiStreamKind = null
                                pendingAppendText.clear()
                                pendingAppendScheduled = false
                                mainHandler.removeCallbacks(flushPendingAppendRunnable)
                            } catch (_: Exception) {
                            }
                        }
                        appendTextInternal(ev.role, ev.text, syncToEventBusBuffer = false, kind = ev.kind)
                        if (ev.role == MessageRole.USER) {
                            flushUiAppendsNow()
                        }
                    }

                    is ChatEventBus.Event.AppendToLastText -> {
                        runOnUiThread {
                            val key = StreamAppendKey(ev.role, ev.kind)
                            val sb = pendingAppendText[key] ?: StringBuilder().also { pendingAppendText[key] = it }
                            sb.append(ev.text)
                            if (!pendingAppendScheduled) {
                                pendingAppendScheduled = true
                                mainHandler.postDelayed(flushPendingAppendRunnable, 32L)
                            }
                        }
                    }

                    is ChatEventBus.Event.AppendImage -> {
                        val b64 = try {
                            android.util.Base64.encodeToString(ev.imageBytes, android.util.Base64.NO_WRAP)
                        } catch (_: Exception) {
                            ""
                        }
                        if (b64.isNotEmpty()) {
                            appendImageInternal(b64, syncToEventBusBuffer = false)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAdbStatus()
        syncPendingInterventionFromGate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            setIntent(intent)
        } catch (_: Exception) {
        }
        try {
            handleConfirmRedirectIntentIfNeeded(intent)
            handleOverlayMicIntentIfNeeded(intent)
        } catch (_: Exception) {
        }
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        try {
            stopReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        stopReceiver = null

        try {
            interventionReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        interventionReceiver = null

        stopRecordingInternal(cancel = true)
        super.onDestroy()
    }

    private fun ensureFloatingStatusReady() {
        val isShizukuMode = try {
            ConfigManager(this).getAdbConnectMode() == ConfigManager.ADB_MODE_SHIZUKU
        } catch (_: Exception) {
            false
        }

        if (!isShizukuMode) {
            try {
                FloatingStatusService.setIdle(this, "等待指令")
            } catch (_: Exception) {
            }
            return
        }

        val overlayOk = try {
            Settings.canDrawOverlays(this)
        } catch (_: Exception) {
            false
        }

        if (overlayOk) {
            try {
                FloatingStatusService.setIdle(this, "等待指令")
            } catch (_: Exception) {
            }
            return
        }

        lifecycleScope.launch {
            val grant = try {
                withContext(Dispatchers.IO) {
                    AdbPermissionGranter.applyShizukuPowerUserPermissions(applicationContext)
                }
            } catch (t: Throwable) {
                AdbPermissionGranter.GrantResult(false, "失败：${t.message}")
            }

            val overlayOkAfter = try {
                Settings.canDrawOverlays(this@ChatActivity)
            } catch (_: Exception) {
                false
            }

            if (grant.ok || overlayOkAfter) {
                try {
                    FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun ensureAdbOrFallbackToPairing() {
        lifecycleScope.launch {
            if (checkConnectMethodAvailable()) return@launch
            confirmFallbackToPairing("当前连接模式不可用，请回到校准页完成授权/配对。")
        }
    }

    private fun setupInsetsAndAutoScroll() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
                val bars = insets.getInsets(Type.systemBars())
                val ime = insets.getInsets(Type.ime())
                v.updatePadding(top = bars.top, bottom = maxOf(ime.bottom, bars.bottom))
                if (insets.isVisible(Type.ime())) {
                    if (followBottom && !userDraggingMessages) {
                        if (enableScrollDebug) {
                            Log.d(scrollDebugTag, "IME visible -> scrollToBottom at ${SystemClock.uptimeMillis()}")
                        }
                        if (!DISABLE_PROGRAMMATIC_SCROLL_FOR_TEST) {
                            scrollToBottom()
                        }
                    }
                }
                insets
            }
        } catch (_: Exception) {
        }

        // Compose 输入框焦点变化时自动滚动（由 Compose 内部处理）
    }

    private fun scrollToBottom() {
        forceScrollToBottom(smooth = false)
    }

    private fun forceScrollToBottom(smooth: Boolean) {
        if (DISABLE_PROGRAMMATIC_SCROLL_FOR_TEST) return

        // 需求：Python 任务执行期间的每次输出，都要把消息列表刷到最底部。
        // 这里统一入口，并做轻量节流，避免高频流式输出导致卡顿。
        followBottom = true

        val now = SystemClock.uptimeMillis()
        if (now - lastProgrammaticScrollAtUptime < 80L) return
        lastProgrammaticScrollAtUptime = now

        val doScroll = Runnable {
            val last = adapter.getItemCountSafe() - 1
            if (last < 0) return@Runnable

            if (enableScrollDebug) {
                try {
                    val st = Throwable().stackTrace
                        .drop(1)
                        .take(6)
                        .joinToString(" | ") { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
                    Log.d(scrollDebugTag, "forceScrollToBottom -> pos=$last smooth=$smooth dragging=$userDraggingMessages t=${SystemClock.uptimeMillis()} stack=$st")
                } catch (_: Exception) {
                }
            }

            try {
                if (smooth) {
                    rvMessages.smoothScrollToPosition(last)
                } else {
                    rvMessages.scrollToPosition(last)
                }
            } catch (_: Exception) {
            }
        }

        // 用户正在拖动时不要强抢手势，等一小会儿再补一次到底部。
        if (userDraggingMessages) {
            mainHandler.postDelayed({
                if (!userDraggingMessages) {
                    rvMessages.post(doScroll)
                }
            }, 120L)
            return
        }

        rvMessages.post(doScroll)
    }

    private fun isNearBottom(): Boolean {
        val lm = rvMessages.layoutManager as? LinearLayoutManager ?: return true
        val last = adapter.getItemCountSafe() - 1
        if (last < 0) return true
        val lastVisible = lm.findLastVisibleItemPosition()
        return lastVisible >= (last - 1)
    }

    private fun dumpScrollState(reason: String) {
        if (!enableScrollDebug) return
        try {
            val lm = rvMessages.layoutManager as? LinearLayoutManager ?: return
            val first = try {
                lm.findFirstVisibleItemPosition()
            } catch (_: Exception) {
                -1
            }
            val last = lm.findLastVisibleItemPosition()
            val offset = try {
                rvMessages.computeVerticalScrollOffset()
            } catch (_: Exception) {
                -1
            }
            val state = try {
                rvMessages.scrollState
            } catch (_: Exception) {
                -1
            }
            Log.w(scrollDebugTag, "${reason} t=${SystemClock.uptimeMillis()} state=$state dragging=$userDraggingMessages followBottom=$followBottom first=$first last=$last offset=$offset")
        } catch (_: Exception) {
        }
    }

    private suspend fun checkAdbStatus(): Boolean {
        val ok = withContext(Dispatchers.IO) {
            try {
                val adbExecPath = computeInternalAdbPath()

                val lastEndpoint = try {
                    ConfigManager(this@ChatActivity).getLastAdbEndpoint().trim()
                } catch (_: Exception) {
                    ""
                }
                if (lastEndpoint.isEmpty()) {
                    return@withContext false
                }

                LocalAdb.runCommand(
                    this@ChatActivity,
                    LocalAdb.buildAdbCommand(this@ChatActivity, adbExecPath, listOf("connect", lastEndpoint)),
                    timeoutMs = 6_000L
                )

                val devicesResult = LocalAdb.runCommand(
                    this@ChatActivity,
                    LocalAdb.buildAdbCommand(this@ChatActivity, adbExecPath, listOf("devices")),
                    timeoutMs = 6_000L
                )
                devicesResult.output.lines().any { it.contains(lastEndpoint) && it.contains("device") }
            } catch (_: Exception) {
                false
            }
        }

        if (!ok) {
            try {
                TaskControl.forceStopPython()
            } catch (_: Exception) {
            }
        }
        return ok
    }

    private fun getCurrentConnectMode(): String {
        return try {
            ConfigManager(this).getAdbConnectMode()
        } catch (_: Exception) {
            ConfigManager.ADB_MODE_WIRELESS_DEBUG
        }
    }

    private suspend fun checkConnectMethodAvailable(): Boolean {
        val mode = getCurrentConnectMode()
        if (mode == ConfigManager.ADB_MODE_SHIZUKU) {
            repeat(2) {
                val ok = try {
                    Shizuku.pingBinder() &&
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) {
                    false
                }
                if (ok) return true
                kotlinx.coroutines.delay(200L)
            }
            return false
        }
        repeat(2) {
            val ok = checkAdbStatus()
            if (ok) return true
            kotlinx.coroutines.delay(200L)
        }
        return false
    }

    private fun confirmFallbackToPairing(message: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            try {
                AlertDialog.Builder(this@ChatActivity)
                    .setTitle("连接不可用")
                    .setMessage(message)
                    .setNegativeButton("取消") { d, _ ->
                        try {
                            d.dismiss()
                        } catch (_: Exception) {
                        }
                    }
                    .setPositiveButton("去校准") { d, _ ->
                        try {
                            d.dismiss()
                        } catch (_: Exception) {
                        }
                        fallbackToPairingInternal()
                    }
                    .show()
            } catch (_: Exception) {
                fallbackToPairingInternal()
            }
        }
    }

    private fun fallbackToPairing(message: String = "当前连接模式不可用，请回到校准页完成授权/配对。") {
        confirmFallbackToPairing(message)
    }

    private fun fallbackToPairingInternal() {
        try {
            stopService(Intent(this@ChatActivity, FloatingStatusService::class.java))
        } catch (_: Exception) {
        }
        try {
            startActivity(
                Intent(this@ChatActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        } catch (_: Exception) {
        }
        finish()
    }

    private fun handleConfirmRedirectIntentIfNeeded(intent: Intent) {
        val msg = try {
            intent.getStringExtra(EXTRA_CONFIRM_REDIRECT_MESSAGE).orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
        if (msg.isEmpty()) return

        try {
            intent.removeExtra(EXTRA_CONFIRM_REDIRECT_MESSAGE)
        } catch (_: Exception) {
        }
        confirmFallbackToPairing(msg)
    }

    private fun setVoiceMode(enabled: Boolean) {
        isVoiceMode = enabled
        // Compose 会自动响应 isVoiceMode 状态变化重新渲染输入栏
        setupComposeInputBar()
        if (!enabled) {
            FloatingStatusService.setIdle(this, "等待指令")
        }
    }

    /** 设置 Compose 输入栏：发光边框按钮 + 输入框 */
    private fun setupComposeInputBar() {
        composeInputBar.setContent {
            var localText by inputText
            var localVoiceMode by remember { mutableStateOf(isVoiceMode) }

            val iv by interventionVisible
            val reqId by interventionRequestId
            val reqType by interventionRequestType
            val reqMsg by interventionRequestMessage

            localVoiceMode = isVoiceMode

            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (iv && reqId > 0L) {
                    val isTakeover = reqType.equals("TAKEOVER", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(color = NeonColors.bgDark.copy(alpha = 0.35f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isTakeover) "需要人工接管" else "需要敏感操作确认",
                                color = NeonColors.neonCyan,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            if (reqMsg.isNotEmpty()) {
                                Text(
                                    text = reqMsg,
                                    color = NeonColors.textPrimary,
                                    fontSize = 13.sp,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GlowButton(
                                    modifier = Modifier.height(40.dp),
                                    cornerRadius = 10.dp,
                                    durationBase = 4400,
                                    onClick = {
                                        val id = reqId
                                        hideInterventionUiLocalIfMatching(id)
                                        UserInterventionGate.respond(id, true)
                                    }
                                ) {
                                    Text(
                                        text = if (isTakeover) "已完成" else "同意",
                                        color = NeonColors.neonCyan,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                if (!isTakeover) {
                                    GlowButton(
                                        modifier = Modifier.height(40.dp),
                                        cornerRadius = 10.dp,
                                        durationBase = 4400,
                                        onClick = {
                                            val id = reqId
                                            hideInterventionUiLocalIfMatching(id)
                                            UserInterventionGate.respond(id, false)
                                        }
                                    ) {
                                        Text(
                                            text = "拒绝",
                                            color = NeonColors.neonCyan,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlowButton(
                        modifier = Modifier.height(44.dp),
                        cornerRadius = 10.dp,
                        durationBase = 4400,
                        onClick = { setVoiceMode(!localVoiceMode) }
                    ) {
                        Text(
                            text = if (localVoiceMode) "键盘" else "语音",
                            color = NeonColors.neonCyan,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    if (localVoiceMode) {
                        GlowButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .pointerInteropFilter { event ->
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_DOWN -> {
                                            startHoldToTalkFromUi()
                                            true
                                        }

                                        MotionEvent.ACTION_UP,
                                        MotionEvent.ACTION_CANCEL -> {
                                            stopHoldToTalkFromUi()
                                            true
                                        }

                                        else -> false
                                    }
                                },
                            cornerRadius = 10.dp,
                            durationBase = 4400,
                            onClick = { }
                        ) {
                            Text(
                                text = "按住 说话",
                                color = NeonColors.neonCyan,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        GlowButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            cornerRadius = 10.dp,
                            durationBase = 5500,
                            onClick = { }
                        ) {
                            BasicTextField(
                                value = localText,
                                onValueChange = {
                                    localText = it
                                    inputText.value = it
                                    sendEnabled.value = it.isNotBlank()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(
                                    color = NeonColors.textPrimary,
                                    fontSize = 14.sp,
                                ),
                                cursorBrush = SolidColor(NeonColors.neonCyan),
                                singleLine = false,
                                maxLines = 3,
                                decorationBox = { innerTextField ->
                                    if (localText.isEmpty()) {
                                        Text(
                                            text = "输入你的指令...",
                                            color = NeonColors.textHint,
                                            fontSize = 14.sp,
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        GlowButton(
                            modifier = Modifier.height(44.dp),
                            cornerRadius = 10.dp,
                            durationBase = 4400,
                            enabled = sendEnabled.value && !interventionVisible.value,
                            onClick = { handleSendClick() }
                        ) {
                            Text(
                                text = "发送",
                                color = if (sendEnabled.value) NeonColors.neonCyan else NeonColors.textHint,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }

    /** 处理发送按钮点击 */
    private fun handleSendClick() {
        val text = inputText.value.trim()
        val onlyHash = text.isNotEmpty() && text.all { it == '#' }
        if (text.isEmpty() || onlyHash) {
            appendText(MessageRole.ACTION, "没听清，请重试")
            FloatingStatusService.setIdle(this@ChatActivity, "没听清，请重试")
            mainHandler.postDelayed({
                FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
            }, 3000L)
            return
        }
        lifecycleScope.launch {
            if (!checkConnectMethodAvailable()) {
                fallbackToPairing()
                return@launch
            }
            inputText.value = ""
            sendEnabled.value = false
            appendText(MessageRole.USER, text)
            flushUiAppendsNow()
            scrollToBottom()
            runPythonTask(text)
        }
    }

    private fun startHoldToTalkFromUi() {
        startHoldToTalk(source = "UI")
    }

    private fun stopHoldToTalkFromUi() {
        stopHoldToTalk(source = "UI")
    }

    private fun startHoldToTalkFromOverlay() {
        // 悬浮窗触发时也切换到语音模式，但不强制打断用户键盘输入焦点
        setVoiceMode(true)
        startHoldToTalk(source = "OVERLAY")
    }

    private fun stopHoldToTalkFromOverlay() {
        stopHoldToTalk(source = "OVERLAY")
    }

    private fun handleOverlayMicIntentIfNeeded(intent: Intent) {
        val action = try {
            intent.getStringExtra(EXTRA_OVERLAY_MIC_ACTION).orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
        if (action.isEmpty()) return

        try {
            intent.removeExtra(EXTRA_OVERLAY_MIC_ACTION)
        } catch (_: Exception) {
        }

        when (action) {
            "CLICK" -> {
                setVoiceMode(true)
            }

            "DOWN" -> {
                startHoldToTalkFromOverlay()
            }

            "UP" -> {
                stopHoldToTalkFromOverlay()
            }
        }
    }

    private fun startHoldToTalk(source: String) {
        if (isRunningTask) {
            appendText(MessageRole.ACTION, "任务执行中，无法开启语音录音。")
            return
        }
        if (isRecordingVoice) return

        holdDownAtMs = System.currentTimeMillis()
        showVoiceOverlayListening()

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            hideVoiceOverlay()
            return
        }

        requestRecordingAudioFocus()

        val dir = File(cacheDir, "voice").apply { mkdirs() }
        val outFile = File(dir, "stt_${System.currentTimeMillis()}.wav")

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = (minBuf.coerceAtLeast(sampleRate) * 2).coerceAtLeast(4096)

        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufSize
            )

            val stateOk = ar.state == AudioRecord.STATE_INITIALIZED
            if (!stateOk) {
                try {
                    ar.release()
                } catch (_: Exception) {
                }
                throw IllegalStateException("AudioRecord 初始化失败")
            }

            // 预留 WAV 头部（44 bytes），后续停止时回填长度。
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
            }

            audioRecord = ar
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true }
                }
            } catch (_: Exception) {
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true }
                }
            } catch (_: Exception) {
            }
            recordingFile = outFile
            isRecordingVoice = true
            isRecordingLoop = true

            ar.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufSize)
                try {
                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.seek(44)
                        while (isRecordingLoop) {
                            val read = ar.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                raf.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                name = "AudioRecordThread"
                start()
            }

            FloatingStatusService.update(this, VoicePromptText.RECORDING)
        } catch (t: Throwable) {
            stopRecordingInternal(cancel = true)
            hideVoiceOverlay()
            appendText(MessageRole.ACTION, "录音启动失败：${t.message}")
            FloatingStatusService.setIdle(this, "等待指令")
        }
    }

    private fun stopHoldToTalk(source: String) {
        if (!isRecordingVoice) return

        val durMs = (System.currentTimeMillis() - holdDownAtMs).coerceAtLeast(0L)
        val audio = recordingFile

        showVoiceOverlayRecognizing()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    stopRecordingInternal(cancel = false)
                } catch (_: Exception) {
                }
            }

            if (durMs < 2_000L) {
                withContext(Dispatchers.IO) {
                    try {
                        audio?.delete()
                    } catch (_: Exception) {
                    }
                }
                hideVoiceOverlay()
                Toast.makeText(this@ChatActivity, VoicePromptText.TOO_SHORT_TOAST, Toast.LENGTH_SHORT).show()
                FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                return@launch
            }

            if (audio == null || !audio.exists() || audio.length() <= 0L) {
                appendText(MessageRole.ACTION, VoicePromptText.INVALID_FILE)
                FloatingStatusService.setIdle(this@ChatActivity, "没听清，请重试")
                hideVoiceOverlay()
                return@launch
            }

            FloatingStatusService.update(this@ChatActivity, VoicePromptText.TRANSCRIBING)

            val text = withContext(Dispatchers.IO) {
                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(this@ChatActivity))
                    }
                    val py = Python.getInstance()
                    PythonConfigBridge.injectModelServiceConfig(this@ChatActivity, py)
                    val mod = py.getModule("autoglm.bridge")
                    mod.callAttr("speech_to_text", audio.absolutePath).toString()
                } catch (t: Throwable) {
                    "识别失败: ${t.message}"
                }
            }.trim()

            val trimmed = text.trim()
            val onlyHash = trimmed.isNotEmpty() && trimmed.all { it == '#' }
            if (trimmed.isEmpty() || onlyHash || trimmed.startsWith("识别失败") || trimmed.startsWith("JWT请求失败")) {
                val msg = if (trimmed.isEmpty() || onlyHash) {
                    "没听清，请重试"
                } else if (trimmed.startsWith("JWT请求失败")) {
                    if (trimmed.contains("\"code\"\\s*:\\s*\"1301\"".toRegex()) || trimmed.contains("contentFilter", ignoreCase = true)) {
                        "语音请求失败：内容安全策略拦截，请调整输入后重试"
                    } else {
                        "语音请求失败，请稍后重试"
                    }
                } else {
                    trimmed
                }
                appendText(MessageRole.ACTION, msg)
                FloatingStatusService.setIdle(this@ChatActivity, "没听清，请重试")
                mainHandler.postDelayed({
                    FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                }, 3000L)
                hideVoiceOverlay()
                return@launch
            }
            runOnUiThread {
                hideVoiceOverlay()
                inputText.value = trimmed
                appendText(MessageRole.USER, trimmed)
                flushUiAppendsNow()
                inputText.value = ""
                sendEnabled.value = false
                followBottom = true
                scrollToBottom()
                runPythonTask(trimmed)
            }
        }
    }

    private fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        lifecycleScope.launch {
            appendText(MessageRole.USER, trimmed)
            flushUiAppendsNow()
            runPythonTask(trimmed)
        }
    }

    private fun isMetricChunk(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains("性能指标")) return true
        if (t.contains("TTFT", ignoreCase = true)) return true
        if (t.contains("首 Token")) return true
        if (t.contains("思考完成延迟")) return true
        if (t.contains("总推理时间")) return true
        // 分隔线（==== / ----）在性能指标块中通常会单独出现，需要跟随归类。
        if (t.matches(Regex("^[=\\-]{10,}.*$"))) return true
        return false
    }

    private fun formatMetricText(raw: String): String {
        var s = raw
        // 把分隔线变成独立行（很多时候是连在一起输出的）
        s = s.replace(Regex("={10,}"), "\n$0\n")
        s = s.replace(Regex("-{10,}"), "\n$0\n")

        // 把常见字段前插入换行，避免整段挤在一行
        val keys = listOf(
            "⏱️",
            "性能指标",
            "首 Token",
            "思考完成延迟",
            "总推理时间",
            "TTFT",
        )
        for (k in keys) {
            s = s.replace(k, "\n$k")
        }

        // 清理多余空行
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun appendOrMergeActionText(deltaOrFull: String) {
        val classified = TaskStreamClassifier.classifyAction(deltaOrFull, lastActionStreamKind)
        val kind = classified.kind
        val text = classified.text
        if (text.isEmpty()) return

        updateFloatingStatusFromAction(kind, text)

        runOnUiThread {
            // 合并策略：仅同 kind 允许合并；步骤/操作类信息默认不与其他类型合并。
            val shouldMerge = (isRunningTask || mergeStreamAfterStop) && !kind.startsWith("STEP") && !kind.startsWith("OP_") && kind != "OPERATION"
            if (shouldMerge && adapter.appendToLastText(MessageRole.ACTION, kind, text)) {
                ChatEventBus.recordAppendToLastText(MessageRole.ACTION, kind, text)
                scheduleFlushUiAppends()
                lastActionStreamKind = classified.nextLastKind
                return@runOnUiThread
            }
            val m = ChatMessage(
                id = idGen.getAndIncrement(),
                role = MessageRole.ACTION,
                type = MessageType.TEXT,
                kind = kind,
                text = text,
            )
            ChatEventBus.recordText(MessageRole.ACTION, text, kind = kind)
            enqueueUiAppend(m)
            // 流式期间新建的气泡需要立即落地，避免下一段 token 继续新建导致刷屏。
            if (isRunningTask || mergeStreamAfterStop) {
                flushUiAppendsNow()
            }
            lastActionStreamKind = classified.nextLastKind
        }
    }

    private fun appendOrMergeAssistantText(deltaOrFull: String) {
        val classified = TaskStreamClassifier.classifyAssistant(deltaOrFull)
        val kind = classified.kind
        val text = classified.text
        if (text.isEmpty()) return
        runOnUiThread {
            // 合并策略：当任务执行中，且最后一条是 AI 文本，则把内容拼接到最后一条；
            // 否则创建新气泡。
            if ((isRunningTask || mergeStreamAfterStop) && adapter.appendToLastText(MessageRole.AI, kind, text)) {
                ChatEventBus.recordAppendToLastText(MessageRole.AI, kind, text)
                scheduleFlushUiAppends()
                lastAiStreamKind = classified.nextLastKind
                return@runOnUiThread
            }
            val m = ChatMessage(
                id = idGen.getAndIncrement(),
                role = MessageRole.AI,
                type = MessageType.TEXT,
                kind = kind,
                text = text,
            )
            ChatEventBus.recordText(MessageRole.AI, text, kind = kind)
            enqueueUiAppend(m)
            // 流式期间新建的气泡需要立即落地，避免下一段 token 继续新建导致刷屏。
            if (isRunningTask || mergeStreamAfterStop) {
                flushUiAppendsNow()
            }
            lastAiStreamKind = classified.nextLastKind
        }
    }

    private fun appendImage(base64: String) {
        appendImageInternal(base64, syncToEventBusBuffer = true)
    }

    private fun appendImageInternal(base64: String, syncToEventBusBuffer: Boolean) {
        val bytes = try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
        if (bytes == null || bytes.isEmpty()) return
        if (syncToEventBusBuffer) {
            ChatEventBus.recordImage(bytes)
        }
        runOnUiThread {
            val m = ChatMessage(
                id = idGen.getAndIncrement(),
                role = MessageRole.AI,
                type = MessageType.IMAGE,
                imageBytes = bytes,
            )
            enqueueUiAppend(m)
        }
    }

    private fun runPythonTask(task: String) {
        try {
            lastActionStreamKind = null
            lastAiStreamKind = null
            pendingAppendText.clear()
            pendingAppendScheduled = false
            mainHandler.removeCallbacks(flushPendingAppendRunnable)
        } catch (_: Exception) {
        }
        sendEnabled.value = false
        updateAdbStatus()

        // Python 层使用 subprocess 执行 ADB 时更偏好脚本包装器（可避免部分 ROM 对私有目录 ELF 的限制）。
        // 真实 ELF 由包装器内部自行选择（或由其它层通过 linker 启动）。
        val adbExecPath = File(filesDir, "bin/adb").absolutePath

        lifecycleScope.launch {
            val mode = getCurrentConnectMode()

            // 任务启动前强制检查“当前模式”是否可用：
            // - Shizuku：binder + 授权
            // - 无线调试：ADB 连接可用
            if (!checkConnectMethodAvailable()) {
                val msg = if (mode == ConfigManager.ADB_MODE_SHIZUKU) {
                    "Shizuku 不可用：请先完成 Shizuku 授权后再执行任务。"
                } else {
                    "ADB 未连接：请先完成无线调试配对并连接设备。"
                }
                appendText(MessageRole.ACTION, msg)
                sendEnabled.value = true
                updateAdbStatus()
                fallbackToPairing()
                return@launch
            }

            if (mode != ConfigManager.ADB_MODE_SHIZUKU) {
                // 无线调试模式：启动前再做一次 connect + devices 确认（兼容 stop 时 kill-server 的副作用）
                val adbReady = withContext(Dispatchers.IO) {
                    try {
                        val adbExecPath2 = computeInternalAdbPath()

                        val lastEndpoint = try {
                            ConfigManager(this@ChatActivity).getLastAdbEndpoint().trim()
                        } catch (_: Exception) {
                            ""
                        }
                        if (lastEndpoint.isEmpty()) return@withContext false

                        LocalAdb.runCommand(
                            this@ChatActivity,
                            LocalAdb.buildAdbCommand(this@ChatActivity, adbExecPath2, listOf("connect", lastEndpoint)),
                            timeoutMs = 6_000L
                        )
                        val devicesResult = LocalAdb.runCommand(
                            this@ChatActivity,
                            LocalAdb.buildAdbCommand(this@ChatActivity, adbExecPath2, listOf("devices")),
                            timeoutMs = 6_000L
                        )
                        devicesResult.output.lines().any { it.contains(lastEndpoint) && it.contains("device") }
                    } catch (_: Exception) {
                        false
                    }
                }

                if (!adbReady) {
                    appendText(MessageRole.ACTION, "ADB 未连接/未恢复历史记录：请先完成无线调试配对并连接设备（确保已保存连接记录），然后再开始任务。")
                    sendEnabled.value = true
                    updateAdbStatus()
                    fallbackToPairing()
                    return@launch
                }
            }

            val started = TaskControl.tryStartPythonTask(this.coroutineContext[Job], Runnable {
                if (Thread.currentThread().isInterrupted) return@Runnable
                TaskControl.bindPythonThread(Thread.currentThread())

                var assistantTextUpdated = false
                lastActionStreamKind = null
                lastAiStreamKind = null

                fun isAdbBrokenMsg(s: String): Boolean {
                    val t = s.trim()
                    if (t.isEmpty()) return false
                    return t.contains("ADB 未连接") ||
                        t.contains("ADB 连接已断开") ||
                        t.contains("ADB 断开") ||
                        t.contains("device offline", ignoreCase = true) ||
                        t.contains("no devices", ignoreCase = true)
                }

                val callback = AndroidAgentCallback(
                    onAction = { msg -> appendOrMergeActionText(msg) },
                    onScreenshotBase64 = { b64 ->
                        var used = b64
                        try {
                            val execEnv = ConfigManager(this@ChatActivity).getExecutionEnvironment()
                            if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
                                val vd = VirtualDisplayController.screenshotPngBase64NonBlack()
                                if (vd.isNotEmpty()) {
                                    used = vd
                                }
                            }
                        } catch (_: Exception) {
                        }
                        appendImage(used)
                    },
                    onAssistant = { msg ->
                        assistantTextUpdated = true
                        appendOrMergeAssistantText(msg)
                    },
                    onError = { msg ->
                        appendText(MessageRole.ACTION, "错误：$msg")
                        if (isAdbBrokenMsg(msg)) {
                            runOnUiThread {
                                try {
                                    TaskControl.forceStopPython()
                                } catch (_: Exception) {
                                }
                                fallbackToPairing()
                            }
                        }
                    },
                    onDone = { msg ->
                        assistantTextUpdated = true
                        lastAiStreamKind = "SUMMARY"
                        appendText(MessageRole.AI, msg)
                        runOnUiThread {
                            FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                            try {
                                FloatingStatusService.showSummaryBubble(this@ChatActivity, msg)
                            } catch (_: Exception) {
                            }
                        }
                    },
                    onTapIndicator = { x, y ->
                        // 在点击坐标显示圆形指示器
                        runOnUiThread {
                            try {
                                val execEnv = ConfigManager(this@ChatActivity).getExecutionEnvironment()
                                if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
                                    val did = VirtualDisplayController.getDisplayId() ?: 0
                                    if (did > 0) {
                                        TapIndicatorService.showAtOnDisplay(this@ChatActivity, x, y, did)
                                    } else {
                                        TapIndicatorService.showAt(this@ChatActivity, x, y)
                                    }
                                } else {
                                    TapIndicatorService.showAt(this@ChatActivity, x, y)
                                }
                            } catch (_: Exception) {
                                TapIndicatorService.showAt(this@ChatActivity, x, y)
                            }
                        }
                    },
                )

                isRunningTask = true
                mergeStreamAfterStop = false
                runOnUiThread {
                    FloatingStatusService.start(this@ChatActivity, "正在执行任务...")
                }

                var finalText: String? = null
                try {
                    if (Thread.currentThread().isInterrupted) return@Runnable
                    if (!TaskControl.shouldContinue()) return@Runnable

                    try {
                        val execEnv = ConfigManager(this@ChatActivity).getExecutionEnvironment()
                        if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
                            val internalAdb = computeInternalAdbPath()
                            val okDisplayId = VirtualDisplayController.prepareForTask(this@ChatActivity, internalAdb)
                            if (okDisplayId == null) {
                                callback.on_error("虚拟隔离控制启动失败：请在开发者选项中启用‘模拟辅助显示’，或切回主屏幕控制。")
                                return@Runnable
                            }
                        }
                    } catch (_: Exception) {
                    }

                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(this@ChatActivity))
                    }
                    val py = Python.getInstance()
                    PythonConfigBridge.injectModelServiceConfig(this@ChatActivity, py)
                    val mod = py.getModule("phone_agent.android_bridge")
                    val connectMode = try {
                        ConfigManager(this@ChatActivity).getAdbConnectMode()
                    } catch (_: Exception) {
                        ConfigManager.ADB_MODE_WIRELESS_DEBUG
                    }
                    finalText = if (connectMode == ConfigManager.ADB_MODE_SHIZUKU) {
                        mod.callAttr("run_task", task, callback, null).toString()
                    } else {
                        mod.callAttr("run_task", task, callback, adbExecPath).toString()
                    }
                } catch (_: InterruptedException) {
                    return@Runnable
                } catch (t: Throwable) {
                    if (Thread.currentThread().isInterrupted) return@Runnable
                    finalText = "失败：${t::class.java.simpleName}: ${t.message}"
                } finally {
                    runOnUiThread {
                        try {
                            if (!assistantTextUpdated && !finalText.isNullOrBlank()) {
                                appendText(MessageRole.AI, finalText.orEmpty())
                            }
                        } catch (_: Exception) {
                        }

                        try {
                            if (isAdbBrokenMsg(finalText.orEmpty())) {
                                fallbackToPairing()
                                return@runOnUiThread
                            }
                        } catch (_: Exception) {
                        }

                        isRunningTask = false
                        mergeStreamAfterStop = false
                        TaskControl.onTaskFinished()
                        try {
                            FloatingStatusService.setIdle(this@ChatActivity, "等待指令")
                        } catch (_: Exception) {
                        }
                        sendEnabled.value = true
                        updateAdbStatus()
                    }
                }
            })

            if (!started) {
                appendText(MessageRole.ACTION, "当前已有任务在执行，请先停止或等待完成。")
                sendEnabled.value = true
                updateAdbStatus()
                return@launch
            }
        }
    }

    private fun handleForceStopAndCleanup(uiMsg: String) {
        mergeStreamAfterStop = true
        TaskControl.forceStopPython()
        isRunningTask = false

        try {
            FloatingStatusService.setIdle(this@ChatActivity, "已停止")
        } catch (_: Exception) {
        }

        appendText(MessageRole.ACTION, uiMsg)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val adbExecPath = computeInternalAdbPath()
                    LocalAdb.sendGlobalTouchUp(this@ChatActivity, adbExecPath)
                } catch (_: Exception) {
                }

                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(this@ChatActivity))
                    }
                    val py = Python.getInstance()
                    val mod = py.getModule("phone_agent.android_bridge")
                    mod.callAttr("reset_state")
                } catch (_: Exception) {
                }
            }

            sendEnabled.value = true
            updateAdbStatus()
        }
    }

    companion object {
        private const val DISABLE_PROGRAMMATIC_SCROLL_FOR_TEST = false

        const val EXTRA_CONFIRM_REDIRECT_MESSAGE = "extra_confirm_redirect_message"
        const val EXTRA_OVERLAY_MIC_ACTION = "extra_overlay_mic_action"
        private const val EXTRA_RELAUNCHED_TO_MAIN = "extra_relaunched_to_main_display"
    }

    class AndroidAgentCallback(
        private val onAction: (String) -> Unit,
        private val onScreenshotBase64: (String) -> Unit,
        private val onAssistant: (String) -> Unit,
        private val onError: (String) -> Unit,
        private val onDone: (String) -> Unit,
        private val onTapIndicator: (Float, Float) -> Unit,
    ) {
        fun on_action(text: String) {
            onAction(text)
        }

        fun on_screenshot(base64Png: String) {
            onScreenshotBase64(base64Png)
        }

        fun on_assistant(text: String) {
            onAssistant(text)
        }

        fun on_error(text: String) {
            onError(text)
        }

        fun on_done(text: String) {
            onDone(text)
        }

        fun on_tap_indicator(x: Float, y: Float) {
            onTapIndicator(x, y)
        }
    }
}
