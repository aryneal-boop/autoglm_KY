package com.example.autoglm

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.animation.ValueAnimator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.autoglm.chat.ChatEventBus
import com.example.autoglm.chat.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

class FloatingStatusService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var windowManager: WindowManager? = null
    private var barView: View? = null
    private var statusTextView: TextView? = null
    private var stopButtonView: View? = null
    private var barBackgroundDrawable: GradientDrawable? = null

    private var statusScrollAnimator: ValueAnimator? = null

    private var barLayoutParams: WindowManager.LayoutParams? = null

    private var tempFocusableEnabled: Boolean = false

    private var isTaskRunningState: Boolean = false
    private var mergeStreamAfterStop: Boolean = false

    private var isRecording: Boolean = false
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var recordingLoop: Boolean = false
    private var recordingFile: File? = null

    private val isRecognizing = AtomicBoolean(false)

    private var holdDownAtMs: Long = 0L

    private var notificationManager: NotificationManager? = null

    private var notificationContentIntent: PendingIntent? = null

    private var pressDownRawY: Float = 0f
    private var canceledByMove: Boolean = false

    private var dragDownRawX: Float = 0f
    private var dragDownRawY: Float = 0f
    private var dragDownLpX: Int = 0
    private var dragDownLpY: Int = 0
    private var dragging: Boolean = false
    private var touchSlopPx: Int = 8
    private var dockOnRight: Boolean = false
    private var longPressRunnable: Runnable? = null
    private var longPressActive: Boolean = false

    private var micLongPressRunnable: Runnable? = null
    private var micPressed: Boolean = false
    private var micCanceledByMove: Boolean = false
    private var micLongPressTriggered: Boolean = false
    private var micDownRawX: Float = 0f
    private var micDownRawY: Float = 0f
    private var micDownAtMs: Long = 0L
    private var micFlowDrawable: FlowBorderDrawable? = null
    private var micFlowAnimator: ValueAnimator? = null

    private var summaryBubbleView: View? = null
    private var summaryBubbleTextView: TextView? = null
    private var summaryBubbleLayoutParams: WindowManager.LayoutParams? = null
    private var summaryBubbleHideRunnable: Runnable? = null
    private var summaryBubbleRemoving: Boolean = false

    private var isShowing = false

    private fun screenWidthPx(): Int = resources.displayMetrics.widthPixels
    private fun screenHeightPx(): Int = resources.displayMetrics.heightPixels

    private fun clampOverlayPosition(lp: WindowManager.LayoutParams, barWidthPx: Int, barHeightPx: Int) {
        val sw = screenWidthPx().coerceAtLeast(1)
        val sh = screenHeightPx().coerceAtLeast(1)
        val maxX = (sw - barWidthPx).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)

        // gravity = CENTER_VERTICAL: y 是相对于屏幕中线的偏移
        val maxY = ((sh - barHeightPx) / 2).coerceAtLeast(0)
        lp.y = lp.y.coerceIn(-maxY, maxY)
    }

    private fun snapToEdge(lp: WindowManager.LayoutParams, barWidthPx: Int) {
        val sw = screenWidthPx().coerceAtLeast(1)
        val centerX = lp.x + barWidthPx / 2
        val dockRight = centerX >= sw / 2
        dockOnRight = dockRight
        lp.x = if (dockRight) (sw - barWidthPx).coerceAtLeast(0) else 0
        applyDockBackground(dockRight)
        updateSummaryBubblePositionIfNeeded()
    }

    private fun applyDockBackground(dockRight: Boolean) {
        val r = dp(12).toFloat()
        barBackgroundDrawable?.cornerRadii = if (dockRight) {
            floatArrayOf(
                r, r,
                0f, 0f,
                0f, 0f,
                r, r
            )
        } else {
            floatArrayOf(
                0f, 0f,
                r, r,
                r, r,
                0f, 0f
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlopPx = try {
            ViewConfiguration.get(this).scaledTouchSlop
        } catch (_: Exception) {
            dp(8)
        }

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationContentIntent = try {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ChatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (_: Exception) {
            null
        }

        try {
            startForegroundInternal(contentText = "语音助手运行中")
        } catch (_: Exception) {
        }
    }

    private fun startForegroundInternal(contentText: String) {
        val nm = notificationManager
            ?: (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).also {
                notificationManager = it
            }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val ch = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "AutoGLM",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                nm.createNotificationChannel(ch)
            } catch (_: Exception) {
            }
        }

        val pi = notificationContentIntent
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("AutoGLM")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (pi != null) setContentIntent(pi)
            }
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                return
            } catch (_: Exception) {
            }
        }
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val wm = windowManager ?: return
        val bar = barView ?: return
        val lp = barLayoutParams ?: return
        val barW = bar.width.takeIf { it > 0 } ?: dp(30)
        val barH = bar.height.takeIf { it > 0 } ?: dp(120)
        clampOverlayPosition(lp, barW, barH)
        snapToEdge(lp, barW)
        applyDockBackground(dockOnRight)
        try {
            wm.updateViewLayout(bar, lp)
        } catch (_: Exception) {
        }

        updateSummaryBubblePositionIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                mainHandler.post {
                    ensureViewCreatedIfNeeded()
                    applyLayoutParamsIfPossible()
                    setTaskRunningState(true)
                    updateText(text)
                    showIfNeeded()
                }
            }
            ACTION_UPDATE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                mainHandler.post {
                    applyLayoutParamsIfPossible()
                    updateText(text)
                }
            }
            ACTION_SET_IDLE -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                mainHandler.post {
                    ensureViewCreatedIfNeeded()
                    applyLayoutParamsIfPossible()
                    setTaskRunningState(false)
                    updateText(text)
                    showIfNeeded()
                }
            }
            ACTION_SHOW_SUMMARY -> {
                val msg = intent.getStringExtra(EXTRA_SUMMARY_TEXT).orEmpty()
                mainHandler.post {
                    ensureViewCreatedIfNeeded()
                    applyLayoutParamsIfPossible()
                    showSummaryBubbleInternal(msg)
                }
            }
            ACTION_STOP -> {
                mainHandler.post { hideAndStopSelf() }
            }
            else -> {
                // ignore
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.post {
            removeViewIfNeeded()
            windowManager = null
        }
        try {
            stopMicFlowEffect()
        } catch (_: Exception) {
        }
        try {
            workerScope.cancel()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun ensureViewCreatedIfNeeded() {
        if (barView != null) return

        val context = this
        val barWidth = dp(30)

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#99000000"))
        }
        barBackgroundDrawable = bgDrawable
        applyDockBackground(dockOnRight)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            layoutParams = LinearLayout.LayoutParams(
                barWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val paddingV = dp(8)
            setPadding(0, paddingV, 0, paddingV)
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLines(10)
            text = ""
            setEms(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> false }
            setOnClickListener {
                try {
                    val i = Intent().apply {
                        component = ComponentName(context, ChatActivity::class.java)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(i)
                } catch (_: Exception) {
                }
            }
        }

        val btn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            micFlowDrawable = FlowBorderDrawable(
                fillColor = Color.parseColor("#D32F2F"),
                strokeWidthPx = dp(3).toFloat(),
                cornerRadiusPx = dp(10).toFloat()
            )
            background = micFlowDrawable
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        micPressed = true
                        micCanceledByMove = false
                        micLongPressTriggered = false
                        micDownRawX = ev.rawX
                        micDownRawY = ev.rawY
                        micDownAtMs = System.currentTimeMillis()

                        micLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        val r = Runnable {
                            if (micPressed && !micCanceledByMove) {
                                micLongPressTriggered = true
                                startMicFlowEffect()
                                if (!isTaskRunningState) {
                                    try {
                                        sendBroadcast(Intent(ACTION_MIC_DOWN))
                                        startHoldToTalkInBackground()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        micLongPressRunnable = r
                        mainHandler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        return@setOnTouchListener true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!micPressed) return@setOnTouchListener false
                        val dx = kotlin.math.abs(ev.rawX - micDownRawX)
                        val dy = kotlin.math.abs(ev.rawY - micDownRawY)
                        if (dx > touchSlopPx || dy > touchSlopPx) {
                            micCanceledByMove = true
                            micLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            micLongPressRunnable = null
                            stopMicFlowEffect()
                        }
                        return@setOnTouchListener true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!micPressed) return@setOnTouchListener false
                        micPressed = false
                        micLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        micLongPressRunnable = null

                        val isCancel = ev.actionMasked == MotionEvent.ACTION_CANCEL
                        if (!isCancel && !micCanceledByMove) {
                            val upAt = System.currentTimeMillis()
                            val duration = upAt - micDownAtMs
                            val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

                            if (isTaskRunningState) {
                                mergeStreamAfterStop = true
                                TaskControl.forceStopPython()
                                sendBroadcast(Intent(ACTION_STOP_CLICKED))
                                setTaskRunningState(false)
                                updateText("已停止")
                            } else {
                                if (micLongPressTriggered || duration >= longPressTimeout) {
                                    try {
                                        sendBroadcast(Intent(ACTION_MIC_UP))
                                        stopHoldToTalkInBackground()
                                    } catch (_: Exception) {
                                    }
                                } else {
                                    sendBroadcast(Intent(ACTION_MIC_CLICKED))
                                    tryStartChatForOverlayMic(OverlayMicAction.CLICK)
                                }
                            }
                        } else {
                            if (!isTaskRunningState) {
                                if (micLongPressTriggered) {
                                    try {
                                        sendBroadcast(Intent(ACTION_MIC_UP))
                                        stopHoldToTalkInBackground()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }

                        stopMicFlowEffect()
                        return@setOnTouchListener true
                    }
                }
                false
            }
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = dp(10) }
        }

        container.addView(tv)
        container.addView(btn)

        container.setOnTouchListener { _, ev ->
            val lp = barLayoutParams
            val wm = windowManager
            val bar = barView
            val stopBtn = stopButtonView
            if (lp == null || wm == null || bar == null || stopBtn == null) return@setOnTouchListener false

            fun isInStopButton(e: MotionEvent): Boolean {
                val x = e.x.toInt()
                val y = e.y.toInt()
                return x >= stopBtn.left && x < stopBtn.right && y >= stopBtn.top && y < stopBtn.bottom
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInStopButton(ev)) return@setOnTouchListener false
                    dragging = false
                    longPressActive = false
                    dragDownRawX = ev.rawX
                    dragDownRawY = ev.rawY
                    dragDownLpX = lp.x
                    dragDownLpY = lp.y

                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    val r = Runnable {
                        if (!dragging) {
                            longPressActive = true
                            setDragHighlight(true)
                        }
                    }
                    longPressRunnable = r
                    mainHandler.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - dragDownRawX).toInt()
                    val dy = (ev.rawY - dragDownRawY).toInt()
                    if (!dragging) {
                        if (kotlin.math.abs(dx) < touchSlopPx && kotlin.math.abs(dy) < touchSlopPx) {
                            return@setOnTouchListener true
                        }
                        dragging = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        if (!longPressActive) {
                            longPressActive = true
                            setDragHighlight(true)
                        }
                    }

                    val barW = bar.width.takeIf { it > 0 } ?: dp(30)
                    val barH = bar.height.takeIf { it > 0 } ?: dp(120)
                    lp.x = dragDownLpX + dx
                    lp.y = dragDownLpY + dy
                    clampOverlayPosition(lp, barW, barH)
                    try {
                        wm.updateViewLayout(bar, lp)
                    } catch (_: Exception) {
                    }
                    updateSummaryBubblePositionIfNeeded()
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null

                    if (dragging || longPressActive) {
                        val barW = bar.width.takeIf { it > 0 } ?: dp(30)
                        val barH = bar.height.takeIf { it > 0 } ?: dp(120)
                        clampOverlayPosition(lp, barW, barH)
                        snapToEdge(lp, barW)
                        try {
                            wm.updateViewLayout(bar, lp)
                        } catch (_: Exception) {
                        }
                        updateSummaryBubblePositionIfNeeded()
                    }

                    if (longPressActive) {
                        setDragHighlight(false)
                        longPressActive = false
                    }

                    // 非拖动情况下：轻触空白区域打开 ChatActivity
                    if (!dragging) {
                        try {
                            val i = Intent().apply {
                                component = ComponentName(context, ChatActivity::class.java)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(i)
                        } catch (_: Exception) {
                        }
                    }
                    return@setOnTouchListener true
                }
            }
            false
        }

        val barParams = WindowManager.LayoutParams(
            barWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = if (dockOnRight) (resources.displayMetrics.widthPixels - barWidth).coerceAtLeast(0) else 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager?.addView(container, barParams)
        container.translationX = if (dockOnRight) barWidth.toFloat() else (-barWidth).toFloat()

        barView = container
        statusTextView = tv
        stopButtonView = btn
        barLayoutParams = barParams

        updateText("等待指令")
    }

    private fun applyLayoutParamsIfPossible() {
        val wm = windowManager ?: return
        val bar = barView
        val lp = barLayoutParams
        if (bar == null || lp == null) return

        val barW = bar.width.takeIf { it > 0 } ?: dp(30)
        val barH = bar.height.takeIf { it > 0 } ?: dp(120)
        clampOverlayPosition(lp, barW, barH)
        snapToEdge(lp, barW)
        applyDockBackground(dockOnRight)
        try {
            wm.updateViewLayout(bar, lp)
        } catch (_: Exception) {
        }
    }

    private fun setTaskRunningState(running: Boolean) {
        isTaskRunningState = running
        val btn = stopButtonView as? ImageButton
        if (btn != null) {
            btn.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_mic)
        }
    }

    private fun setDragHighlight(enabled: Boolean) {
        val d = barBackgroundDrawable ?: return
        if (enabled) {
            d.setStroke(dp(3), Color.parseColor("#CCFFFFFF"))
        } else {
            d.setStroke(0, Color.TRANSPARENT)
        }
    }

    private fun showIfNeeded() {
        if (isShowing) return
        val bar = barView ?: return
        val w = bar.width.takeIf { it > 0 } ?: dp(30)
        isShowing = true
        bar.animate().cancel()
        bar.translationX = if (dockOnRight) w.toFloat() else (-w).toFloat()
        bar.animate()
            .translationX(0f)
            .setDuration(220L)
            .start()
    }

    private fun hideAndStopSelf() {
        if (!isShowing) {
            stopSelf()
            return
        }
        val bar = barView
        if (bar == null) {
            stopSelf()
            return
        }
        val w = bar.width.takeIf { it > 0 } ?: dp(30)
        bar.animate().cancel()
        bar.animate()
            .translationX(if (dockOnRight) w.toFloat() else (-w).toFloat())
            .setDuration(220L)
            .withEndAction {
                removeViewIfNeeded()
                stopSelf()
            }
            .start()
        isShowing = false
    }

    private fun removeViewIfNeeded() {
        val wm = windowManager ?: return
        try {
            barView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            removeSummaryBubbleIfNeeded(animate = false)
        } catch (_: Exception) {
        }

        try {
            stopMicFlowEffect()
        } catch (_: Exception) {
        }

        stopStatusAutoScroll()

        barView = null
        statusTextView = null
        stopButtonView = null
        barLayoutParams = null
        tempFocusableEnabled = false
        isShowing = false
    }

    private fun showSummaryBubbleInternal(message: String) {
        val msg = message.trim()
        if (msg.isEmpty()) return

        removeSummaryBubbleIfNeeded(animate = false)

        summaryBubbleRemoving = false

        val wm = windowManager ?: return
        val bar = barView ?: return
        val barLp = barLayoutParams ?: return

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val bubbleBg = GradientDrawable().apply {
            setColor(Color.parseColor("#E6111111"))
        }
        bubble.background = bubbleBg
        applyBubbleBackground(bubbleBg, dockOnRight)

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                removeSummaryBubbleIfNeeded(animate = true)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(closeBtn, LinearLayout.LayoutParams(dp(20), dp(20)))
        }

        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 8
            text = msg
        }

        bubble.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bubble.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }

        // 控制气泡宽度（更窄一些，避免占用过大屏幕空间）
        bubbleLp.width = kotlin.math.min(dp(260), (screenWidthPx() * 0.62f).toInt().coerceAtLeast(dp(180)))

        summaryBubbleView = bubble
        summaryBubbleTextView = tv
        summaryBubbleLayoutParams = bubbleLp

        bubble.alpha = 0f
        try {
            wm.addView(bubble, bubbleLp)
        } catch (_: Exception) {
            summaryBubbleView = null
            summaryBubbleTextView = null
            summaryBubbleLayoutParams = null
            return
        }

        bubble.post {
            updateSummaryBubblePositionIfNeeded()
            bubble.animate().alpha(1f).setDuration(160L).start()
        }

        summaryBubbleHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hide = Runnable { removeSummaryBubbleIfNeeded(animate = true) }
        summaryBubbleHideRunnable = hide
        mainHandler.postDelayed(hide, 5000L)
    }

    private fun removeSummaryBubbleIfNeeded(animate: Boolean) {
        summaryBubbleHideRunnable?.let { mainHandler.removeCallbacks(it) }
        summaryBubbleHideRunnable = null

        val wm = windowManager
        val v = summaryBubbleView
        if (wm == null || v == null) {
            summaryBubbleView = null
            summaryBubbleTextView = null
            summaryBubbleLayoutParams = null
            return
        }

        fun doRemove() {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
            summaryBubbleView = null
            summaryBubbleTextView = null
            summaryBubbleLayoutParams = null
            summaryBubbleRemoving = false
        }

        if (!animate) {
            summaryBubbleRemoving = false
            doRemove()
            return
        }

        summaryBubbleRemoving = true

        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction { doRemove() }
            .start()
    }

    private fun applyBubbleBackground(bg: GradientDrawable, dockRight: Boolean) {
        val r = dp(14).toFloat()
        bg.cornerRadius = r
    }

    private enum class OverlayMicAction {
        CLICK,
        DOWN,
        UP,
    }

    private fun tryStartChatForOverlayMic(action: OverlayMicAction) {
        try {
            val i = Intent(this, ChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(ChatActivity.EXTRA_OVERLAY_MIC_ACTION, action.name)
            }
            startActivity(i)
        } catch (_: Throwable) {
        }
    }

    private fun updateSummaryBubblePositionIfNeeded() {
        if (summaryBubbleRemoving) return
        val wm = windowManager ?: return
        val bubble = summaryBubbleView ?: return
        val bubbleLp = summaryBubbleLayoutParams ?: return
        val bar = barView ?: return
        val barLp = barLayoutParams ?: return

        val barW = bar.width.takeIf { it > 0 } ?: dp(30)
        val bubbleW = bubble.width.takeIf { it > 0 } ?: run {
            bubble.measure(
                View.MeasureSpec.makeMeasureSpec(screenWidthPx(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenHeightPx(), View.MeasureSpec.AT_MOST)
            )
            bubble.measuredWidth.coerceAtLeast(dp(120))
        }

        val margin = dp(8)
        bubbleLp.x = if (dockOnRight) {
            (barLp.x - bubbleW - margin).coerceAtLeast(0)
        } else {
            (barLp.x + barW + margin).coerceAtMost((screenWidthPx() - bubbleW).coerceAtLeast(0))
        }
        bubbleLp.y = barLp.y

        (bubble.background as? GradientDrawable)?.let { applyBubbleBackground(it, dockOnRight) }

        try {
            wm.updateViewLayout(bubble, bubbleLp)
        } catch (_: Exception) {
        }
    }

    private fun updateText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) {
            statusTextView?.text = ""
            stopStatusAutoScroll()
            return
        }

        val mapped = OverlayStatusTextMapper.map(null, t) ?: t
        setStatusTextAndMaybeScroll(mapped)
    }

    private fun setStatusTextAndMaybeScroll(mapped: String) {
        val tv = statusTextView ?: return
        val raw = mapped.trim()
        if (raw.isEmpty()) {
            tv.text = ""
            stopStatusAutoScroll()
            return
        }
        stopStatusAutoScroll()

        tv.text = raw.toCharArray().joinToString("\n") { it.toString() }
        tv.scrollTo(0, 0)

        val window = (tv.maxLines.takeIf { it > 0 } ?: 10)
        if (raw.length <= window) return

        tv.post { startStatusAutoScrollInternal() }
    }

    private fun startStatusAutoScrollInternal() {
        val tv = statusTextView ?: return
        val layout = tv.layout ?: return
        val maxScrollY = (layout.height - tv.height).coerceAtLeast(0)
        if (maxScrollY <= 0) return

        stopStatusAutoScroll()

        val speedPxPerSec = dp(18).toFloat().coerceAtLeast(1f)
        val durationMs = ((maxScrollY / speedPxPerSec) * 1000f).toLong().coerceAtLeast(1200L)

        val anim = ValueAnimator.ofInt(0, maxScrollY).apply {
            duration = durationMs
            startDelay = 450L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val y = it.animatedValue as Int
                tv.scrollTo(0, y)
            }
        }
        statusScrollAnimator = anim
        anim.start()
    }

    private fun stopStatusAutoScroll() {
        statusScrollAnimator?.cancel()
        statusScrollAnimator = null
    }

    private fun getStatusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) resources.getDimensionPixelSize(resId) else dp(24)
        return if (h <= 0) dp(24) else h
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun adbWrapperPath(): String {
        return File(filesDir, "bin/adb").absolutePath
    }

    private fun startHoldToTalkInBackground() {
        if (isTaskRunningState) return
        if (isRecording) return

        val granted = PermissionChecker.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED
        if (!granted) {
            updateText(VoicePromptText.NO_PERMISSION_STATUS)
            ChatEventBus.postText(MessageRole.ACTION, VoicePromptText.NO_PERMISSION_STATUS)
            setIdle(this, "等待指令")
            return
        }

        holdDownAtMs = System.currentTimeMillis()
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
            isRecording = true
            recordingLoop = true

            ar.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufSize)
                try {
                    RandomAccessFile(outFile, "rw").use { raf ->
                        raf.seek(44)
                        while (recordingLoop) {
                            val read = ar.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                raf.write(buffer, 0, read)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                name = "OverlayAudioRecordThread"
                start()
            }

            updateText(VoicePromptText.RECORDING)
            ChatEventBus.postText(MessageRole.ACTION, VoicePromptText.RECORDING)
        } catch (t: Throwable) {
            stopRecordingInternal(cancel = true)
            updateText(VoicePromptText.RECORDING_FAILED_COMPACT)
            ChatEventBus.postText(MessageRole.ACTION, VoicePromptText.RECORDING_FAILED_STATUS_PREFIX + (t.message ?: ""))
            setIdle(this, "等待指令")
        }
    }

    private fun stopHoldToTalkInBackground() {
        if (!isRecording) return
        val audio = recordingFile
        stopRecordingInternal(cancel = false)

        val durMs = (System.currentTimeMillis() - holdDownAtMs).coerceAtLeast(0L)
        if (durMs < 2_000L) {
            try {
                audio?.delete()
            } catch (_: Exception) {
            }
            updateText(VoicePromptText.TOO_SHORT_STATUS)
            setIdle(this, "等待指令")
            return
        }

        if (audio == null || !audio.exists() || audio.length() <= 0L) {
            updateText(VoicePromptText.INVALID_FILE)
            ChatEventBus.postText(MessageRole.ACTION, VoicePromptText.INVALID_FILE)
            setIdle(this, "没听清，请重试")
            return
        }

        if (!isRecognizing.compareAndSet(false, true)) {
            setIdle(this, "等待指令")
            return
        }

        updateText(VoicePromptText.TRANSCRIBING)
        ChatEventBus.postText(MessageRole.ACTION, VoicePromptText.TRANSCRIBING)

        workerScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    try {
                        if (!Python.isStarted()) {
                            Python.start(AndroidPlatform(this@FloatingStatusService))
                        }
                        val py = Python.getInstance()
                        PythonConfigBridge.injectModelServiceConfig(this@FloatingStatusService, py)
                        val mod = py.getModule("autoglm.bridge")
                        mod.callAttr("speech_to_text", audio.absolutePath).toString()
                    } catch (t: Throwable) {
                        "识别失败: ${t.message}"
                    }
                }.trim()

                val trimmed = text.trim()
                val onlyHash = trimmed.isNotEmpty() && trimmed.all { it == '#' }
                if (trimmed.isEmpty() || onlyHash || trimmed.startsWith("识别失败")) {
                    ChatEventBus.postText(MessageRole.ACTION, if (trimmed.isEmpty() || onlyHash) "没听清，请重试" else trimmed)
                    setIdle(this@FloatingStatusService, "没听清，请重试")
                    mainHandler.postDelayed({
                        setIdle(this@FloatingStatusService, "等待指令")
                    }, 3000L)
                    return@launch
                }

                ChatEventBus.postText(MessageRole.USER, trimmed)

                val ok = try {
                    ensureConnectMethodReadyForTask()
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    val mode = getCurrentConnectMode()
                    if (mode == ConfigManager.ADB_MODE_SHIZUKU) {
                        ChatEventBus.postText(MessageRole.ACTION, "Shizuku 未就绪/未授权：请回到校准页完成 Shizuku 授权。")
                        setIdle(this@FloatingStatusService, "Shizuku未授权")
                    } else {
                        ChatEventBus.postText(MessageRole.ACTION, "ADB 未连接：请先完成无线调试配对并连接设备。")
                        setIdle(this@FloatingStatusService, "ADB未连接")
                    }

                    try {
                        stop(this@FloatingStatusService)
                    } catch (_: Exception) {
                    }
                    try {
                        val msg = if (mode == ConfigManager.ADB_MODE_SHIZUKU) {
                            "Shizuku 不可用：请先完成 Shizuku 授权。是否现在回到校准页？"
                        } else {
                            "无线调试 ADB 不可用：请先完成配对并连接设备。是否现在回到校准页？"
                        }
                        startActivity(
                            Intent(this@FloatingStatusService, ChatActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra(ChatActivity.EXTRA_CONFIRM_REDIRECT_MESSAGE, msg)
                            }
                        )
                    } catch (_: Exception) {
                    }
                    return@launch
                }

                startPythonTaskInBackground(trimmed)
            } finally {
                isRecognizing.set(false)
            }
        }
    }

    private fun ensureAdbReadyForTask(): Boolean {
        val lastEndpoint = try {
            ConfigManager(this).getLastAdbEndpoint().trim()
        } catch (_: Exception) {
            ""
        }
        if (lastEndpoint.isEmpty()) return false

        val adbExecPath = adbWrapperPath()
        try {
            if (!LocalAdb.isAdbServerRunning()) {
                LocalAdb.startAdbServer(this, adbExecPath)
            }
        } catch (_: Exception) {
        }

        return try {
            LocalAdb.runCommand(
                this,
                LocalAdb.buildAdbCommand(this, adbExecPath, listOf("connect", lastEndpoint)),
                timeoutMs = 6_000L
            )
            val devicesResult = LocalAdb.runCommand(
                this,
                LocalAdb.buildAdbCommand(this, adbExecPath, listOf("devices")),
                timeoutMs = 6_000L
            )
            devicesResult.output.lines().any { it.contains(lastEndpoint) && it.contains("device") }
        } catch (_: Exception) {
            false
        }
    }

    private fun getCurrentConnectMode(): String {
        return try {
            ConfigManager(this).getAdbConnectMode()
        } catch (_: Exception) {
            ConfigManager.ADB_MODE_WIRELESS_DEBUG
        }
    }

    private fun ensureConnectMethodReadyForTask(): Boolean {
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
                try {
                    Thread.sleep(200L)
                } catch (_: Exception) {
                }
            }
            return false
        }
        repeat(2) {
            val ok = ensureAdbReadyForTask()
            if (ok) return true
            try {
                Thread.sleep(200L)
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun startPythonTaskInBackground(task: String) {
        val adbExecPath = adbWrapperPath()

        val started = TaskControl.tryStartPythonTask(null, Runnable {
            if (Thread.currentThread().isInterrupted) return@Runnable
            TaskControl.bindPythonThread(Thread.currentThread())

            var assistantTextUpdated = false
            var lastActionStreamKind: String? = null
            var lastAiStreamKind: String? = null

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
                onAction = { msg ->
                    val classified = TaskStreamClassifier.classifyAction(msg, lastActionStreamKind)
                    val kind = classified.kind
                    val text = classified.text
                    if (text.isEmpty()) return@AndroidAgentCallback

                    val shouldMerge = (isTaskRunningState || mergeStreamAfterStop) && kind == lastActionStreamKind && kind != "OPERATION"
                    if (!(shouldMerge && ChatEventBus.appendToLastText(MessageRole.ACTION, text))) {
                        ChatEventBus.postText(MessageRole.ACTION, text)
                    }
                    lastActionStreamKind = classified.nextLastKind

                    val display = OverlayStatusTextMapper.map(kind, text) ?: text
                    update(this@FloatingStatusService, display)
                },
                onScreenshotBase64 = { b64 ->
                    val bytes = try {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    } catch (_: Exception) {
                        null
                    }
                    if (bytes != null) {
                        ChatEventBus.postImage(bytes)
                    }
                },
                onAssistant = { msg ->
                    assistantTextUpdated = true
                    val classified = TaskStreamClassifier.classifyAssistant(msg)
                    val kind = classified.kind
                    val text = classified.text
                    if (text.isEmpty()) return@AndroidAgentCallback

                    val shouldMerge = (isTaskRunningState || mergeStreamAfterStop) && kind == lastAiStreamKind
                    if (!(shouldMerge && ChatEventBus.appendToLastText(MessageRole.AI, text))) {
                        ChatEventBus.postText(MessageRole.AI, text)
                    }
                    lastAiStreamKind = classified.nextLastKind
                },
                onError = { msg ->
                    ChatEventBus.postText(MessageRole.ACTION, "错误：$msg")
                    if (isAdbBrokenMsg(msg)) {
                        try {
                            TaskControl.forceStopPython()
                        } catch (_: Exception) {
                        }
                        setIdle(this@FloatingStatusService, "ADB异常")
                    }
                },
                onDone = { msg ->
                    assistantTextUpdated = true
                    lastAiStreamKind = "SUMMARY"
                    mergeStreamAfterStop = false
                    ChatEventBus.postText(MessageRole.AI, msg)
                    setIdle(this@FloatingStatusService, "等待指令")
                    try {
                        showSummaryBubble(this@FloatingStatusService, msg)
                    } catch (_: Exception) {
                    }
                },
            )

            isTaskRunningState = true
            mergeStreamAfterStop = false
            start(this@FloatingStatusService, "正在执行任务...")

            var finalText: String? = null
            try {
                if (Thread.currentThread().isInterrupted) return@Runnable
                if (!TaskControl.shouldContinue()) return@Runnable

                try {
                    val execEnv = ConfigManager(this@FloatingStatusService).getExecutionEnvironment()
                    if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
                        val okDisplayId = VirtualDisplayController.prepareForTask(this@FloatingStatusService, adbExecPath)
                        if (okDisplayId == null) {
                            callback.on_error("虚拟隔离控制启动失败：请在开发者选项中启用‘模拟辅助显示’，或切回主屏幕控制。")
                            return@Runnable
                        }
                    }
                } catch (_: Exception) {
                }

                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this@FloatingStatusService))
                }
                val py = Python.getInstance()
                PythonConfigBridge.injectModelServiceConfig(this@FloatingStatusService, py)
                val mod = py.getModule("phone_agent.android_bridge")
                val connectMode = try {
                    ConfigManager(this@FloatingStatusService).getAdbConnectMode()
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
                isTaskRunningState = false
                mergeStreamAfterStop = false
                TaskControl.onTaskFinished()
                if (!assistantTextUpdated && !finalText.isNullOrBlank()) {
                    ChatEventBus.postText(MessageRole.AI, finalText.orEmpty())
                }

                if (!finalText.isNullOrBlank()) {
                    if (isAdbBrokenMsg(finalText.orEmpty())) {
                        setIdle(this@FloatingStatusService, "ADB异常")
                    } else {
                        setIdle(this@FloatingStatusService, "等待指令")
                    }
                }
            }
        })

        if (!started) {
            ChatEventBus.postText(MessageRole.ACTION, "当前已有任务在执行，请先停止或等待完成。")
            setIdle(this, "任务中")
            return
        }

        ChatEventBus.postText(MessageRole.AI, "正在为您执行...")
    }

    private fun stopRecordingInternal(cancel: Boolean) {
        isRecording = false
        recordingLoop = false

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
            t?.join(600)
        } catch (_: Exception) {
        }

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

        val file = recordingFile
        if (!cancel && file != null) {
            try {
                finalizeWavFile(file, sampleRate = 16000, channels = 1)
            } catch (_: Exception) {
            }
        }

        if (cancel) {
            try {
                file?.delete()
            } catch (_: Exception) {
            }
        }

        recordingFile = null
    }

    private fun finalizeWavFile(file: File, sampleRate: Int, channels: Int) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { raf ->
            val totalAudioLen = (raf.length() - 44).coerceAtLeast(0)
            val totalDataLen = totalAudioLen + 36
            raf.seek(0)
            writeWavHeader(
                raf,
                totalAudioLen = totalAudioLen,
                totalDataLen = totalDataLen,
                sampleRate = sampleRate,
                channels = channels,
                byteRate = sampleRate * channels * 16 / 8
            )
        }
    }

    private fun writeWavHeader(
        raf: RandomAccessFile,
        totalAudioLen: Long,
        totalDataLen: Long,
        sampleRate: Int,
        channels: Int,
        byteRate: Int,
    ) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        val blockAlign = (channels * 16 / 8).toShort()
        header[32] = (blockAlign.toInt() and 0xff).toByte()
        header[33] = ((blockAlign.toInt() shr 8) and 0xff).toByte()
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        raf.write(header, 0, 44)
    }

    class AndroidAgentCallback(
        private val onAction: (String) -> Unit,
        private val onScreenshotBase64: (String) -> Unit,
        private val onAssistant: (String) -> Unit,
        private val onError: (String) -> Unit,
        private val onDone: (String) -> Unit,
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
    }

    private fun startMicFlowEffect() {
        val d = micFlowDrawable ?: return
        if (micFlowAnimator?.isRunning == true) return
        d.setFlowEnabled(true)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                val v = it.animatedValue as Float
                d.setFlowProgress(v)
            }
        }
        micFlowAnimator = animator
        animator.start()
    }

    private fun stopMicFlowEffect() {
        micFlowAnimator?.cancel()
        micFlowAnimator = null
        micFlowDrawable?.setFlowEnabled(false)
    }

    private class FlowBorderDrawable(
        private val fillColor: Int,
        private val strokeWidthPx: Float,
        private val cornerRadiusPx: Float
    ) : android.graphics.drawable.Drawable() {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }

        private val rect = RectF()
        private val shaderMatrix = Matrix()

        private var flowEnabled: Boolean = false
        private var flowProgress: Float = 0f

        private var cachedW: Int = -1
        private var cachedH: Int = -1
        private var cachedShader: Shader? = null

        fun setFlowEnabled(enabled: Boolean) {
            flowEnabled = enabled
            invalidateSelf()
        }

        fun setFlowProgress(p: Float) {
            flowProgress = p
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return

            rect.set(
                b.left.toFloat() + strokeWidthPx / 2f,
                b.top.toFloat() + strokeWidthPx / 2f,
                b.right.toFloat() - strokeWidthPx / 2f,
                b.bottom.toFloat() - strokeWidthPx / 2f
            )

            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, fillPaint)

            if (flowEnabled) {
                val w = b.width().coerceAtLeast(1)
                val h = b.height().coerceAtLeast(1)
                if (w != cachedW || h != cachedH || cachedShader == null) {
                    cachedW = w
                    cachedH = h
                    cachedShader = LinearGradient(
                        0f,
                        0f,
                        w.toFloat(),
                        h.toFloat(),
                        intArrayOf(
                            Color.parseColor("#00FFFFFF"),
                            Color.parseColor("#CC00E5FF"),
                            Color.parseColor("#CCFF2BD6"),
                            Color.parseColor("#CCFFE066"),
                            Color.parseColor("#00FFFFFF")
                        ),
                        floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }

                val shader = cachedShader
                if (shader != null) {
                    val shift = (cachedW + cachedH).toFloat() * flowProgress
                    shaderMatrix.reset()
                    shaderMatrix.setTranslate(shift, -shift)
                    shader.setLocalMatrix(shaderMatrix)
                    strokePaint.shader = shader
                    canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, strokePaint)
                }
            } else {
                strokePaint.shader = null
            }
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
            strokePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            fillPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "autoglm_voice_assistant"
        private const val NOTIFICATION_ID = 2001

        private const val ACTION_START = "com.example.autoglm.action.FLOATING_STATUS_START"
        private const val ACTION_UPDATE = "com.example.autoglm.action.FLOATING_STATUS_UPDATE"
        private const val ACTION_STOP = "com.example.autoglm.action.FLOATING_STATUS_STOP"

        private const val ACTION_SET_IDLE = "com.example.autoglm.action.FLOATING_STATUS_SET_IDLE"

        private const val ACTION_SHOW_SUMMARY = "com.example.autoglm.action.FLOATING_STATUS_SHOW_SUMMARY"

        const val ACTION_STOP_CLICKED = "com.example.autoglm.action.FLOATING_STATUS_STOP_CLICKED"

        const val ACTION_MIC_CLICKED = "com.example.autoglm.action.FLOATING_STATUS_MIC_CLICKED"

        const val ACTION_MIC_DOWN = "com.example.autoglm.action.FLOATING_STATUS_MIC_DOWN"

        const val ACTION_MIC_UP = "com.example.autoglm.action.FLOATING_STATUS_MIC_UP"

        const val ACTION_START_TASK = "com.example.autoglm.action.FLOATING_STATUS_START_TASK"

        private const val EXTRA_TEXT = "extra_text"

        private const val EXTRA_SUMMARY_TEXT = "extra_summary_text"

        const val EXTRA_TASK_TEXT = "extra_task_text"

        fun canDrawOverlays(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun buildOverlayPermissionIntent(context: Context): Intent {
            return Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun start(context: Context, text: String) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context, text: String) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun setIdle(context: Context, text: String = "等待指令") {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_SET_IDLE
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun showSummaryBubble(context: Context, message: String) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_SHOW_SUMMARY
                putExtra(EXTRA_SUMMARY_TEXT, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
