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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
import com.example.autoglm.input.VirtualAsyncInputInjector
import com.example.autoglm.input.InputHelper
import com.example.autoglm.ui.GlowButton
import com.example.autoglm.vdiso.ImeFocusDeadlockController
import com.example.autoglm.UserInterventionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * 悬浮窗状态条 + 工具箱前台服务。
 *
 * **用途**
 * - 以前台服务形式常驻，提供系统级悬浮窗入口：
 *   - 显示任务状态（空闲/执行中/错误等）
 *   - 提供“开始任务/停止任务/按住说话”等快捷交互
 * - 语音交互：录音（AudioRecord）并调用 Python 侧语音转写，将文本回传给 `ChatActivity` 执行。
 * - 工具箱模式：
 *   - 主屏模式下的截图预览
 *   - 虚拟隔离模式下绑定 VirtualDisplay 预览 Surface（见 [VirtualDisplayController]/`vdiso.ShizukuVirtualDisplayEngine`）
 *   - 触摸层：将用户在预览上的点击/滑动注入到目标 display（见 `input.VirtualAsyncInputInjector`）
 * - IME 焦点死锁防护：创建 [com.example.autoglm.vdiso.ImeFocusDeadlockController]，在需要时提示/遮罩。
 * - 与 `ChatActivity` 通过广播协议交互（ACTION_*），保证即使 App 不在前台也可触发关键操作。
 *
 * **引用路径**
 * - `app/src/main/AndroidManifest.xml` -> `<service android:name=".FloatingStatusService" ...>`
 *
 * **使用注意事项**
 * - 悬浮窗依赖 `SYSTEM_ALERT_WINDOW`：未授权时应提示用户授权。
 * - 录音需要 `RECORD_AUDIO`：未授权时需走权限申请。
 * - 注意资源释放：WindowManager view、AudioRecord、协程 scope 等需要在 `onDestroy` 做善后。
 */
class FloatingStatusService : Service() {

    private val TAG = "FloatingStatusService"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val configManager by lazy { ConfigManager(applicationContext) }

    private var toolboxModeSuffix: String = ""
    private var touchLayerPerfLastLogMs: Long = 0L
    private var touchLayerLastCostMs: Long = 0L
    private var touchLayerMaxCostMs: Long = 0L
    private var touchLayerLagCount: Long = 0L
    private var touchLayerEventCount: Long = 0L
    private var touchLayerLastAction: Int = -1
    private var touchLayerLastSeq: Long = -1L

    private var touchLayerThread: HandlerThread? = null
    private var touchLayerHandler: Handler? = null
    private var touchLayerLastMovePostMs: Long = 0L
    private var touchLayerLastIndicatorUiMs: Long = 0L
    private var touchLayerGestureDownTime: Long = 0L

    private var touchLayerShellDownViewX: Float = 0f
    private var touchLayerShellDownViewY: Float = 0f
    private var touchLayerShellDownTx: Int = 0
    private var touchLayerShellDownTy: Int = 0
    private var touchLayerShellLastTx: Int = 0
    private var touchLayerShellLastTy: Int = 0
    private var touchLayerShellMoved: Boolean = false

    private var windowManager: WindowManager? = null
    private var barView: View? = null
    private var statusTextView: TextView? = null
    private var stopButtonView: View? = null
    private var barBackgroundDrawable: GradientDrawable? = null

    private var toolboxPanelView: View? = null
    private var toolboxPreviewView: ImageView? = null
    private var toolboxPreviewTextureView: TextureView? = null
    private var toolboxPreviewWrapView: FrameLayout? = null
    private var toolboxPreviewTouchLayerView: View? = null
    private var toolboxPreviewLocalIndicatorView: View? = null
    private var toolboxModeTextView: TextView? = null
    private var toolboxCloseButtonView: View? = null
    private var toolboxToMainButtonView: View? = null
    private var toolboxHandleView: View? = null
    private var toolboxExpanded: Boolean = false
    private var toolboxPreviewBitmap: Bitmap? = null
    private var toolboxPreviewTicker: Runnable? = null

    private var toolboxInterventionButtonView: View? = null
    private var toolboxInterventionRejectButtonView: View? = null

    @Volatile
    private var interventionRequestId: Long = 0L

    @Volatile
    private var interventionRequestType: String = ""

    @Volatile
    private var interventionRequestMessage: String = ""

    private val mainScreenPreviewInFlight = AtomicBoolean(false)

    private val virtualScreenPreviewInFlight = AtomicBoolean(false)

    private val ensureVirtualDisplayInFlight = AtomicBoolean(false)

    @Volatile
    private var lastVirtualFrameTimeMs: Long = 0L

    @Volatile
    private var lastEnsureVirtualFocusAtMs: Long = 0L

    private val toolboxPreviewGeneration = AtomicInteger(0)

    @Volatile
    private var lastToolboxIsVirtualMode: Boolean? = null

    private var statusScrollAnimator: ValueAnimator? = null

    private var barLayoutParams: WindowManager.LayoutParams? = null
    private var toolboxLayoutParams: WindowManager.LayoutParams? = null

    private var tempFocusableEnabled: Boolean = false

    @Volatile
    private var focusDeadlockLocked: Boolean = false

    private var focusDeadlockBlockerView: View? = null
    private var focusDeadlockBlockerLayoutParams: WindowManager.LayoutParams? = null

    private var focusDeadlockTipView: TextView? = null
    private var focusDeadlockTipLayoutParams: WindowManager.LayoutParams? = null

    private var imeFocusDeadlockController: ImeFocusDeadlockController? = null
    private var focusDeadlockTipAutoHideRunnable: Runnable? = null

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

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

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

    @Volatile
    private var lastDiagLogAtMs: Long = 0L

    private fun diag(event: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val last = lastDiagLogAtMs
        if (last > 0L && now - last < 700L) return
        lastDiagLogAtMs = now

        val md = runCatching { mainDisplayMetrics() }.getOrNull()
        val rd = runCatching { resources.displayMetrics }.getOrNull()

        val barH = barView?.height ?: -1
        val panelH = toolboxPanelView?.height ?: -1
        val tvH = statusTextView?.height ?: -1

        val blp = barLayoutParams
        val tlp = toolboxLayoutParams

        Log.i(
            TAG,
            "diag[$event] mainDensity=${md?.density} resDensity=${rd?.density} barH=$barH panelH=$panelH tvH=$tvH " +
                "barLp=${blp?.width}x${blp?.height}@(${blp?.x},${blp?.y}) " +
                "toolLp=${tlp?.width}x${tlp?.height}@(${tlp?.x},${tlp?.y})"
        )
    }

    private fun mainDisplayContext(): Context = runCatching {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val d0 = dm.getDisplay(0) ?: return@runCatching this as Context
        createDisplayContext(d0)
    }.getOrElse {
        this
    }

    private fun mainDisplayMetrics() = runCatching {
        mainDisplayContext().resources.displayMetrics
    }.getOrElse {
        resources.displayMetrics
    }

    private fun screenWidthPx(): Int = mainDisplayMetrics().widthPixels
    private fun screenHeightPx(): Int = mainDisplayMetrics().heightPixels

    private fun handleWidthPx(): Int = dp(30)

    private fun toolboxWidthPx(): Int = dp(260)

    private fun virtualPreviewContentSizeFixed(): Pair<Int, Int> {
        val latest = runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.getLatestContentSize() }.getOrNull()
        val lw = latest?.first?.takeIf { it > 0 }
        val lh = latest?.second?.takeIf { it > 0 }
        if (lw != null && lh != null) return lw to lh

        return runCatching {
            ConfigManager(this).getVirtualDisplaySize(isLandscape = false)
        }.getOrElse {
            480 to 848
        }
    }

    private fun toolboxVirtualPreviewHeightPxFixed(): Int {
        val (cw, ch) = virtualPreviewContentSizeFixed()
        val w = (toolboxPreviewWrapView?.width ?: 0).takeIf { it > 0 }
            ?: toolboxWidthPx().coerceAtLeast(1)
        // Keep a reasonable minimum so the preview is usable and buttons won't be squeezed.
        return ((w.toFloat() * ch.toFloat() / cw.toFloat()).toInt()).coerceAtLeast(dp(180))
    }

    private fun toolboxWindowHeightPx(): Int {
        val maxH = (screenHeightPx() - dp(80)).coerceAtLeast(dp(240))
        val previewH = if (isVirtualIsolatedMode()) {
            toolboxVirtualPreviewHeightPxFixed()
        } else {
            toolboxVirtualPreviewHeightPxFixed()
        }
        // Fixed reserved area for buttons so they won't be squeezed by preview height.
        val desired = previewH + dp(10) + dp(10) + dp(10) + dp(88)
        return desired.coerceIn(dp(240), maxH)
    }

    private fun updateMainPreviewAspectRatioBestEffort() {
        if (isVirtualIsolatedMode()) return
        val wrap = toolboxPreviewWrapView ?: return
        mainHandler.post {
            val targetH = toolboxVirtualPreviewHeightPxFixed()
            val lp = (wrap.layoutParams as? LinearLayout.LayoutParams) ?: return@post
            if (lp.height != targetH) {
                lp.height = targetH
                wrap.layoutParams = lp
            }
        }
    }

    private fun snapToolboxToEdge(lp: WindowManager.LayoutParams) {
        val handleW = handleWidthPx()
        val panelW = toolboxWidthPx()
        val totalW = handleW.coerceAtLeast(1)

        val sw = screenWidthPx().coerceAtLeast(1)
        val centerX = lp.x + handleW / 2
        val dockRight = centerX >= sw / 2
        dockOnRight = dockRight

        lp.x = if (dockRight) (sw - totalW).coerceAtLeast(0) else 0
        applyDockBackground(dockRight)

        val tlp = toolboxLayoutParams
        if (tlp != null) {
            tlp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            tlp.y = lp.y
            tlp.x = if (dockRight) {
                (lp.x - panelW).coerceAtLeast(0)
            } else {
                (lp.x + handleW).coerceAtMost((sw - panelW).coerceAtLeast(0))
            }
        }

        val panel = toolboxPanelView
        val handle = toolboxHandleView

        if (panel != null) {
            val plp = (panel.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT)
            plp.width = panelW
            plp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            panel.layoutParams = plp
        }

        if (handle != null) {
            val hlp = (handle.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(handleW, FrameLayout.LayoutParams.WRAP_CONTENT)
            hlp.width = handleW
            hlp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            handle.layoutParams = hlp
        }

        if (panel is LinearLayout) {
            val base = dp(0)
            val reserve = dp(0)
            panel.setPadding(
                if (dockRight) base else reserve,
                base,
                if (dockRight) reserve else base,
                base
            )
        }
        if (panel != null && !toolboxExpanded) {
            panel.translationX = if (dockOnRight) panelW.toFloat() else (-panelW).toFloat()
        }
    }

    private fun enqueueVirtualTouchCmd(
        displayId: Int,
        downTime: Long,
        action: Int,
        x: Int,
        y: Int,
        ensureFocus: Boolean,
    ) {
        if (!isVirtualIsolatedMode()) return
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return

        InputHelper.enqueueTouch(
            displayId = displayId,
            downTime = downTime,
            action = action,
            x = x,
            y = y,
            ensureFocus = ensureFocus,
        )
    }

    private fun ensureTouchLayerThreadStarted() {
        if (touchLayerThread != null && touchLayerHandler != null) return
        val t = HandlerThread("TouchLayerThread").also { it.start() }
        touchLayerThread = t
        touchLayerHandler = Handler(t.looper) { msg ->
            val obj = msg.obj
            val sample = obj as? TouchLayerSample
            if (sample != null) {
                runCatching { handleTouchLayerSample(sample) }
            }
            true
        }
    }

    private data class TouchLayerSample(
        val action: Int,
        val viewX: Float,
        val viewY: Float,
        val viewW: Int,
        val viewH: Int,
        val eventTime: Long,
    )

    private fun handleTouchLayerSample(sample: TouchLayerSample) {
        if (!isVirtualIsolatedMode()) return

        val did = VirtualDisplayController.getDisplayId() ?: 0
        if (did <= 0) return

        // 点击悬浮工具箱里的虚拟屏预览时，优先把焦点切到虚拟屏幕。
        // 部分 ROM 上如果虚拟屏没有获得焦点，后续 input -d 注入可能不会生效。
        if (sample.action == MotionEvent.ACTION_DOWN) {
            val now = android.os.SystemClock.uptimeMillis()
            val last = lastEnsureVirtualFocusAtMs
            if (last <= 0L || now - last >= 400L) {
                lastEnsureVirtualFocusAtMs = now
                runCatching { VirtualDisplayController.ensureFocusedDisplayBestEffort() }
            }
        }

        val cwCh = runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.getLatestContentSize() }.getOrNull()
        val cw = cwCh?.first?.takeIf { it > 0 } ?: 720
        val ch = cwCh?.second?.takeIf { it > 0 } ?: 1280

        val vw = sample.viewW
        val vh = sample.viewH
        if (vw <= 0 || vh <= 0) return

        val (tx, ty) = mapViewPointToContentLinear(
            viewX = sample.viewX,
            viewY = sample.viewY,
            vw = vw,
            vh = vh,
            cw = cw,
            ch = ch,
        )

        val nowMs = android.os.SystemClock.uptimeMillis()
        val longPressThresholdMs = 450L
        val moveThresholdViewPx = dp(8).toFloat()

        when (sample.action) {
            MotionEvent.ACTION_DOWN -> {
                touchLayerGestureDownTime = android.os.SystemClock.uptimeMillis()
                touchLayerShellDownViewX = sample.viewX
                touchLayerShellDownViewY = sample.viewY
                touchLayerShellDownTx = tx
                touchLayerShellDownTy = ty
                touchLayerShellLastTx = tx
                touchLayerShellLastTy = ty
                touchLayerShellMoved = false
                mainHandler.post {
                    showLocalPreviewIndicatorAt(sample.viewX, sample.viewY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(sample.viewX - touchLayerShellDownViewX)
                val dy = kotlin.math.abs(sample.viewY - touchLayerShellDownViewY)
                if (!touchLayerShellMoved && (dx >= moveThresholdViewPx || dy >= moveThresholdViewPx)) {
                    touchLayerShellMoved = true
                }
                touchLayerShellLastTx = tx
                touchLayerShellLastTy = ty
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dur = (nowMs - touchLayerGestureDownTime).coerceAtLeast(0L)
                val x0 = touchLayerShellDownTx
                val y0 = touchLayerShellDownTy
                val x1 = touchLayerShellLastTx
                val y1 = touchLayerShellLastTy

                if (sample.action == MotionEvent.ACTION_CANCEL) {
                    // no-op
                } else if (touchLayerShellMoved) {
                    execShellSwipeBestEffort(did, x0, y0, x1, y1, dur)
                } else if (dur >= longPressThresholdMs) {
                    execShellLongPressBestEffort(did, x0, y0, dur)
                } else {
                    execShellTapBestEffort(did, x0, y0)
                }

                mainHandler.post {
                    hideLocalPreviewIndicator()
                }

                touchLayerShellMoved = false
            }
        }
    }

    private fun execInputCommandBestEffort(displayId: Int, cmdWithoutDisplay: String, cmdWithDisplay: String) {
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return
        if (displayId <= 0) {
            runCatching { ShizukuBridge.execResult(cmdWithoutDisplay) }
            return
        }

        val r = runCatching { ShizukuBridge.execResult(cmdWithDisplay) }.getOrNull()
        if (r != null && r.exitCode == 0) return
        runCatching { ShizukuBridge.execResult(cmdWithoutDisplay) }
    }

    private fun execShellTapBestEffort(displayId: Int, x: Int, y: Int) {
        execInputCommandBestEffort(
            displayId = displayId,
            cmdWithoutDisplay = "input tap $x $y",
            cmdWithDisplay = "input -d $displayId tap $x $y",
        )
    }

    private fun execShellSwipeBestEffort(displayId: Int, x0: Int, y0: Int, x1: Int, y1: Int, durationMs: Long) {
        val d = durationMs.coerceIn(60L, 1200L)
        execInputCommandBestEffort(
            displayId = displayId,
            cmdWithoutDisplay = "input swipe $x0 $y0 $x1 $y1 $d",
            cmdWithDisplay = "input -d $displayId swipe $x0 $y0 $x1 $y1 $d",
        )
    }

    private fun execShellLongPressBestEffort(displayId: Int, x: Int, y: Int, durationMs: Long) {
        val d = durationMs.coerceIn(450L, 1500L)
        execInputCommandBestEffort(
            displayId = displayId,
            cmdWithoutDisplay = "input swipe $x $y $x $y $d",
            cmdWithDisplay = "input -d $displayId swipe $x $y $x $y $d",
        )
    }

    private fun logTouchLayerOnce(msg: String, force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - touchLayerPerfLastLogMs < 350L) return
        touchLayerPerfLastLogMs = now
        Log.w("TouchLayer", msg)
    }

    private fun updateTouchLayerPerfSuffix(action: Int, seq: Long, costMs: Long, lagged: Boolean) {
        touchLayerLastCostMs = costMs
        touchLayerMaxCostMs = maxOf(touchLayerMaxCostMs, costMs)
        touchLayerEventCount++
        if (lagged) touchLayerLagCount++
        touchLayerLastAction = action
        touchLayerLastSeq = seq
    }

    private fun injectVirtualTapFromToolboxPreviewBestEffort(viewX: Float, viewY: Float) {
        if (!isVirtualIsolatedMode()) return
        val did = VirtualDisplayController.getDisplayId() ?: return
        if (did <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return

        val wrap = toolboxPreviewWrapView ?: return
        val vw = wrap.width
        val vh = wrap.height
        if (vw <= 0 || vh <= 0) return

        val size = runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.getLatestContentSize() }.getOrNull()
        val cw = size?.first?.takeIf { it > 0 } ?: 720
        val ch = size?.second?.takeIf { it > 0 } ?: 1280

        val (tx, ty) = mapViewPointToContentLinear(
            viewX = viewX,
            viewY = viewY,
            vw = vw,
            vh = vh,
            cw = cw,
            ch = ch,
        )

        runCatching { TapIndicatorService.showAtOnDisplay(this, tx.toFloat(), ty.toFloat(), did) }

        val downTime = android.os.SystemClock.uptimeMillis()
        enqueueVirtualTouchCmd(did, downTime, MotionEvent.ACTION_DOWN, tx, ty, true)
        enqueueVirtualTouchCmd(did, downTime, MotionEvent.ACTION_UP, tx, ty, false)
    }

    private data class VirtualMappedPoint(
        val displayId: Int,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    private fun mapToolboxPreviewPointToVirtualDisplayBestEffort(viewX: Float, viewY: Float): VirtualMappedPoint? {
        if (!isVirtualIsolatedMode()) return null
        val did = VirtualDisplayController.getDisplayId() ?: return null
        if (did <= 0) return null

        val wrap = toolboxPreviewWrapView ?: return null
        val vw = wrap.width
        val vh = wrap.height
        if (vw <= 0 || vh <= 0) return null

        val size = runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.getLatestContentSize() }.getOrNull()
        val cw = size?.first?.takeIf { it > 0 } ?: 720
        val ch = size?.second?.takeIf { it > 0 } ?: 1280

        val (tx, ty) = mapViewPointToContentCenterCrop(
            viewX = viewX,
            viewY = viewY,
            vw = vw,
            vh = vh,
            cw = cw,
            ch = ch,
        )

        return VirtualMappedPoint(displayId = did, x = tx, y = ty, w = cw, h = ch)
    }

    private fun showLocalPreviewIndicatorAt(viewX: Float, viewY: Float) {
        val v = toolboxPreviewLocalIndicatorView ?: return
        val wrap = toolboxPreviewWrapView ?: return
        if (v.parent == null || wrap.width <= 0 || wrap.height <= 0) return

        val r = (v.layoutParams?.width ?: dp(14)).toFloat() / 2f
        val cx = viewX.coerceIn(0f, wrap.width.toFloat())
        val cy = viewY.coerceIn(0f, wrap.height.toFloat())
        v.translationX = cx - r
        v.translationY = cy - r
        if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
    }

    private fun hideLocalPreviewIndicator() {
        toolboxPreviewLocalIndicatorView?.visibility = View.GONE
    }

    private val virtualAsyncInputInjector by lazy { VirtualAsyncInputInjector() }

    private fun injectVirtualSingleTouchBestEffort(
        displayId: Int,
        downTime: Long,
        action: Int,
        x: Int,
        y: Int,
        ensureFocus: Boolean,
    ) {
        if (!isVirtualIsolatedMode()) return
        if (displayId <= 0) return
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return

        if (ensureFocus) {
            val now = android.os.SystemClock.uptimeMillis()
            val last = lastEnsureVirtualFocusAtMs
            if (last <= 0L || now - last >= 400L) {
                lastEnsureVirtualFocusAtMs = now
                runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.ensureFocusedDisplay(displayId) }
            }
        }

        runCatching {
            virtualAsyncInputInjector.injectSingleTouchAsync(
                displayId = displayId,
                downTime = downTime,
                x = x.toFloat(),
                y = y.toFloat(),
                action = action,
            )
        }
    }

    private fun setToolboxExpanded(expanded: Boolean, animate: Boolean) {
        diag("setToolboxExpanded:$expanded:enter")
        if (expanded && !isVirtualIsolatedMode()) {
            openChatActivityBestEffort()
            return
        }
        toolboxExpanded = expanded
        val panel = toolboxPanelView ?: return
        val w = toolboxWidthPx().toFloat()
        val hiddenTx = if (dockOnRight) w else -w
        val shownTx = 0f
        panel.animate().cancel()
        val wm = windowManager
        val bar = barView
        val lp = barLayoutParams
        val toolboxLp = toolboxLayoutParams

        val toolbox = toolboxPanelView

        if (wm != null && bar != null && lp != null) {
            snapToolboxToEdge(lp)
            try {
                wm.updateViewLayout(bar, lp)
            } catch (_: Exception) {
            }
        }

        if (wm != null && toolbox != null && toolboxLp != null) {
            snapToolboxToEdge(lp ?: toolboxLp)
            clampToolboxPosition(toolboxLp)
            try {
                wm.updateViewLayout(toolbox, toolboxLp)
            } catch (_: Exception) {
            }
        }

        if (animate) {
            if (expanded) {
                // Ensure we always slide in from the correct hidden position.
                toolbox?.visibility = View.VISIBLE
                panel.translationX = hiddenTx
                panel.animate()
                    .translationX(shownTx)
                    .setDuration(220L)
                    .start()
            } else {
                panel.animate()
                    .translationX(hiddenTx)
                    .setDuration(220L)
                    .withEndAction {
                        toolbox?.visibility = View.GONE
                        panel.translationX = hiddenTx
                    }
                    .start()
            }
        } else {
            panel.translationX = if (expanded) shownTx else hiddenTx
            toolbox?.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        // Defer follow-up work so the expand/collapse animation can start immediately.
        mainHandler.post {
            updateToolboxModeLabel()
            if (expanded) {
                val tlp = toolboxLayoutParams
                val toolbox = toolboxPanelView
                val wm2 = windowManager
                if (wm2 != null && toolbox != null && tlp != null) {
                    toolbox.post {
                        try {
                            clampToolboxPosition(tlp)
                            wm2.updateViewLayout(toolbox, tlp)
                        } catch (_: Exception) {
                        }
                        diag("setToolboxExpanded:$expanded:postClamp")
                    }
                }
                if (isVirtualIsolatedMode()) {
                    // 虚拟隔离模式：预览必须完全走 VirtualDisplay -> SurfaceView(surface) 的直出链路，
                    // 禁止 ticker/bitmap/canvas 的软件绘制，否则展开时容易触发 GC/掉帧。
                    stopToolboxPreviewTicker()
                    ensureVirtualDisplayAndBindVirtualPreviewIfPossible()

                    // 首次展开时 TextureView 往往还没完成 layout / surfaceTexture 未 ready，
                    // 这里做一次 post 的兜底绑定，确保第一次展开也能尽快出画面。
                    toolboxPreviewTextureView?.post {
                        if (toolboxExpanded && isVirtualIsolatedMode()) {
                            updateVirtualPreviewAspectRatioBestEffort()
                            applyVirtualPreviewTransformBestEffort()
                            ensureVirtualDisplayAndBindVirtualPreviewIfPossible()
                        }
                    }
                } else {
                    // 主屏幕控制模式：不展开工具箱（点击竖条跳转聊天界面）。
                    stopToolboxPreviewTicker()
                }
            } else {
                // 收起工具箱：停止所有预览刷新。
                stopToolboxPreviewTicker()
                if (isVirtualIsolatedMode()) {
                    // 虚拟隔离模式收起：解绑预览 surface，让 GL 分发器停止往悬浮窗输出。
                    unbindVirtualPreviewSurfaceBestEffort()
                }
            }
            diag("setToolboxExpanded:$expanded:after")
        }
    }

    private fun bindVirtualPreviewToSurfaceIfPossible() {
        // 将 VirtualDisplay 的输出 surface 切换为悬浮窗 TextureView 的 surface。
        // 这是“完全不走 bitmap 刷新”的关键入口。
        if (!isVirtualIsolatedMode()) return
        val tv = toolboxPreviewTextureView ?: return
        if (!tv.isAvailable) return
        val st = tv.surfaceTexture ?: return
        val surface = Surface(st)
        if (!surface.isValid) {
            runCatching { surface.release() }
            return
        }
        applyVirtualPreviewTransformBestEffort()
        workerScope.launch {
            runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.setOutputSurface(surface) }
            // 注意：这里不立即 release surface，让 VirtualDisplay 持有它。
        }
    }

    private fun ensureVirtualDisplayAndBindVirtualPreviewIfPossible() {
        if (!isVirtualIsolatedMode()) return

        val did = VirtualDisplayController.getDisplayId()
        val alreadyStarted = did != null && runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.isStarted() }.getOrElse { false }
        if (alreadyStarted) {
            bindVirtualPreviewToSurfaceIfPossible()
            return
        }

        if (!ensureVirtualDisplayInFlight.compareAndSet(false, true)) return
        workerScope.launch {
            try {
                val created = runCatching { VirtualDisplayController.prepareForTask(this@FloatingStatusService, "") }.getOrNull()
                mainHandler.post {
                    if (toolboxExpanded && isVirtualIsolatedMode() && created != null) {
                        updateVirtualPreviewAspectRatioBestEffort()
                        applyVirtualPreviewTransformBestEffort()
                        bindVirtualPreviewToSurfaceIfPossible()
                    }
                }
            } finally {
                ensureVirtualDisplayInFlight.set(false)
            }
        }
    }

    private fun unbindVirtualPreviewSurfaceBestEffort() {
        // 恢复 VirtualDisplay 输出到 ImageReader.surface。
        // 注意：这是 best-effort，避免 surfaceDestroyed 后仍绑定无效 surface。
        workerScope.launch {
            runCatching { com.example.autoglm.vdiso.ShizukuVirtualDisplayEngine.restoreOutputSurfaceToImageReader() }
            // 输出已切回 ImageReader，这里尝试释放 TextureView surface（避免泄漏）。
            runCatching {
                val st = toolboxPreviewTextureView?.surfaceTexture
                if (st != null) {
                    val s = Surface(st)
                    try { s.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun currentExecutionEnvironment(): String {
        return try {
            ConfigManager(this).getExecutionEnvironment()
        } catch (_: Exception) {
            ConfigManager.EXEC_ENV_MAIN
        }
    }

    private fun isVirtualIsolatedMode(): Boolean {
        return currentExecutionEnvironment() == ConfigManager.EXEC_ENV_VIRTUAL
    }

    private fun updateToolboxModeLabel() {
        val tv = toolboxModeTextView ?: return
        val isVirtual = isVirtualIsolatedMode()
        val base = if (isVirtual) "虚拟隔离模式" else "主屏幕模式"
        tv.text = if (toolboxModeSuffix.isNotEmpty()) "$base | $toolboxModeSuffix" else base

        val sv = toolboxPreviewTextureView
        val iv = toolboxPreviewView
        if (isVirtual) {
            sv?.visibility = View.VISIBLE
            iv?.visibility = View.GONE
        } else {
            sv?.visibility = View.GONE
            iv?.visibility = View.VISIBLE
        }

        toolboxPreviewTouchLayerView?.apply {
            visibility = if (isVirtual) View.VISIBLE else View.GONE
            isClickable = isVirtual
            isEnabled = isVirtual
        }

        val last = lastToolboxIsVirtualMode
        if (last == null || last != isVirtual) {
            // 模式变化时递增 generation：用于作废“旧模式”下已经发起但尚未完成的异步刷新任务。
            // 目的：保证虚拟/主屏两条刷新链路互斥，避免串画面和额外负载。
            lastToolboxIsVirtualMode = isVirtual
            toolboxPreviewGeneration.incrementAndGet()
            lastVirtualFrameTimeMs = 0L
            lastEnsureVirtualFocusAtMs = 0L

            if (isVirtual) {
                // 切到虚拟隔离：
                // 1) 停止主屏截图刷新
                // 2) 清理 ImageView 旧 bitmap（避免内存占用与误显示）
                // 3) 若工具箱已展开，立即绑定 VirtualDisplay 输出到 SurfaceView
                mainScreenPreviewInFlight.set(false)
                updateVirtualPreviewAspectRatioBestEffort()
                if (toolboxExpanded) {
                    stopToolboxPreviewTicker()
                    bindVirtualPreviewToSurfaceIfPossible()
                }
                mainHandler.post {
                    val prev = toolboxPreviewBitmap
                    toolboxPreviewBitmap = null
                    try {
                        iv?.setImageBitmap(null)
                    } catch (_: Exception) {
                    }
                    try { prev?.recycle() } catch (_: Exception) {}
                }
            } else {
                // 切到主屏控制：
                // 1) 恢复 VirtualDisplay 输出到 ImageReader
                // 2) 若工具箱已展开，立即收起（主屏模式不允许展开）
                virtualScreenPreviewInFlight.set(false)
                if (toolboxExpanded) {
                    stopToolboxPreviewTicker()
                    setToolboxExpanded(false, animate = false)
                }
            }
        }
    }

    private fun openChatActivityBestEffort() {
        try {
            val i = Intent().apply {
                component = ComponentName(this@FloatingStatusService, ChatActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun updateVirtualPreviewAspectRatioBestEffort() {
        // 虚拟隔离模式：预览容器高度固定为“创建虚拟屏分辨率”的比例，不再动态调整。
        if (!isVirtualIsolatedMode()) return
        val wrap = toolboxPreviewWrapView ?: return
        mainHandler.post {
            val targetH = toolboxVirtualPreviewHeightPxFixed()
            val lp = (wrap.layoutParams as? LinearLayout.LayoutParams) ?: return@post
            if (lp.height != targetH) {
                lp.height = targetH
                wrap.layoutParams = lp
            }
        }
    }

    private fun applyVirtualPreviewTransformBestEffort() {
        // TextureView 默认会把 Surface 内容按 View 尺寸拉伸。
        // 预览容器会按虚拟屏内容比例布局，因此这里保持 identity，避免裁切/拉伸。
        if (!isVirtualIsolatedMode()) return
        val tv = toolboxPreviewTextureView ?: return
        val vw = tv.width
        val vh = tv.height
        if (vw <= 0 || vh <= 0) {
            tv.post { applyVirtualPreviewTransformBestEffort() }
            return
        }

        tv.setTransform(Matrix())
    }

    private fun updateVirtualPreviewBufferSizeBestEffort() {
        if (!isVirtualIsolatedMode()) return
        val tv = toolboxPreviewTextureView ?: return
        if (!tv.isAvailable) return
        val st = tv.surfaceTexture ?: return

        val (cw, ch) = virtualPreviewContentSizeFixed()
        if (cw <= 0 || ch <= 0) return
        runCatching { st.setDefaultBufferSize(cw, ch) }
    }

    private data class CropMapping(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private fun computeCenterCropMapping(vw: Int, vh: Int, cw: Int, ch: Int): CropMapping {
        val vwf = vw.coerceAtLeast(1).toFloat()
        val vhf = vh.coerceAtLeast(1).toFloat()
        val cwf = cw.coerceAtLeast(1).toFloat()
        val chf = ch.coerceAtLeast(1).toFloat()

        val sx = vwf / cwf
        val sy = vhf / chf
        val scale = maxOf(sx, sy)
        val scaledW = cwf * scale
        val scaledH = chf * scale
        val dx = (vwf - scaledW) / 2f
        val dy = (vhf - scaledH) / 2f
        return CropMapping(scale = scale, offsetX = dx, offsetY = dy)
    }

    private fun mapViewPointToContentCenterCrop(
        viewX: Float,
        viewY: Float,
        vw: Int,
        vh: Int,
        cw: Int,
        ch: Int,
    ): Pair<Int, Int> {
        val m = computeCenterCropMapping(vw, vh, cw, ch)
        val x = ((viewX - m.offsetX) / m.scale)
        val y = ((viewY - m.offsetY) / m.scale)
        val tx = x.toInt().coerceIn(0, cw - 1)
        val ty = y.toInt().coerceIn(0, ch - 1)
        return tx to ty
    }

    private fun mapViewPointToContentLinear(
        viewX: Float,
        viewY: Float,
        vw: Int,
        vh: Int,
        cw: Int,
        ch: Int,
    ): Pair<Int, Int> {
        val nx = (viewX / vw.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val ny = (viewY / vh.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val tx = (nx * cw.toFloat()).toInt().coerceIn(0, cw - 1)
        val ty = (ny * ch.toFloat()).toInt().coerceIn(0, ch - 1)
        return tx to ty
    }

    private fun startToolboxPreviewTicker() {
        val iv = toolboxPreviewView
        val sv = toolboxPreviewTextureView
        if (iv == null && sv == null) return
        stopToolboxPreviewTicker()

        val r = object : Runnable {
            override fun run() {
                if (!toolboxExpanded) {
                    stopToolboxPreviewTicker()
                    return
                }

                // Mode might change while expanded.
                updateToolboxModeLabel()

                try {
                    if (!isVirtualIsolatedMode()) {
                        updateToolboxPreviewOnce()
                    }
                } catch (_: Exception) {
                }

                // Real-time refresh while expanded.
                val delay = if (isVirtualIsolatedMode()) 120L else 650L
                mainHandler.postDelayed(this, delay)
            }
        }
        toolboxPreviewTicker = r
        mainHandler.post(r)
    }

    private fun stopToolboxPreviewTicker() {
        toolboxPreviewTicker?.let { mainHandler.removeCallbacks(it) }
        toolboxPreviewTicker = null
    }

    private fun updateToolboxPreviewOnce() {
        // 单次预览刷新：
        // - 虚拟隔离模式：不走 bitmap 绘制（仅用于聚焦等 best-effort 操作）
        // - 主屏幕控制模式：走 screencap -> decode -> ImageView
        val gen = toolboxPreviewGeneration.get()
        val isVirtual = isVirtualIsolatedMode()

        if (isVirtual) {
            val now = android.os.SystemClock.uptimeMillis()
            val last = lastEnsureVirtualFocusAtMs
            if (last <= 0L || now - last >= 1500L) {
                lastEnsureVirtualFocusAtMs = now
                workerScope.launch {
                    runCatching { VirtualDisplayController.ensureFocusedDisplayBestEffort() }
                }
            }
            return
        }

        // MAIN_SCREEN: capture display 0 via ADB. This can be slow; run off main thread.
        val iv = toolboxPreviewView ?: return
        if (!mainScreenPreviewInFlight.compareAndSet(false, true)) return
        workerScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    if (getCurrentConnectMode() == ConfigManager.ADB_MODE_SHIZUKU) {
                        val b = runCatching { ShizukuBridge.execBytes("screencap -p") }.getOrNull() ?: ByteArray(0)
                        if (b.size >= 8) b else ByteArray(0)
                    } else {
                        val b64 = AdbBridge.screencapPngBase64AllowBlack(displayId = 0)
                        if (b64.isBlank()) return@withContext ByteArray(0)
                        try {
                            Base64.decode(b64, Base64.DEFAULT)
                        } catch (_: Exception) {
                            ByteArray(0)
                        }
                    }
                }

                if (bytes.isEmpty()) return@launch

                val bmp = try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {
                    null
                } ?: return@launch

                if (gen != toolboxPreviewGeneration.get() || isVirtualIsolatedMode()) {
                    try { bmp.recycle() } catch (_: Exception) {}
                    return@launch
                }

                mainHandler.post {
                    if (gen != toolboxPreviewGeneration.get() || isVirtualIsolatedMode()) {
                        try { bmp.recycle() } catch (_: Exception) {}
                        return@post
                    }
                    val prev = toolboxPreviewBitmap
                    toolboxPreviewBitmap = bmp
                    iv.setImageBitmap(bmp)
                    try { prev?.recycle() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                mainScreenPreviewInFlight.set(false)
            }
        }
    }

    private fun getTopComponentOnDisplayBestEffort(displayId: Int): String {
        if (displayId < 0) return ""
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return ""
        val dump = runCatching { ShizukuBridge.execText("dumpsys activity activities") }.getOrNull().orEmpty()
        if (dump.isBlank()) return ""

        val resumedLines = dump.lineSequence()
            .map { it.trim() }
            .filter { it.contains("ResumedActivity") || it.contains("mResumedActivity") }
            .toList()

        val onDisplay = resumedLines.firstOrNull {
            it.contains("displayId=$displayId") || it.contains("mDisplayId=$displayId")
        }
        if (onDisplay != null) {
            Log.i(TAG, "getTopComponentOnDisplay: matched line=$onDisplay")
            return extractComponentFromDumpsysLine(onDisplay)
        }

        // Some ROMs (e.g. MIUI) don't include displayId on the same line as ResumedActivity.
        // Fallback: locate the display section and search resumed activity within that section.
        val dumpLines = dump.lineSequence().toList()

        fun isDisplayHeader(line: String): Boolean {
            val s = line.trim()
            if (s.isEmpty()) return false
            // Try common formats.
            if (s.contains("Display #$displayId")) return true
            if (s.contains("displayId=$displayId")) return true
            if (s.contains("mDisplayId=$displayId")) return true
            if (s.contains("display $displayId")) return true
            return false
        }

        var inTargetDisplay = false
        for (raw in dumpLines) {
            val s = raw.trim()
            if (isDisplayHeader(s)) {
                inTargetDisplay = true
                continue
            }
            if (inTargetDisplay) {
                // Stop when next display section begins.
                if (s.startsWith("Display #") && !s.contains("Display #$displayId")) {
                    inTargetDisplay = false
                    continue
                }
                if (s.contains("ResumedActivity") || s.contains("mResumedActivity")) {
                    Log.i(TAG, "getTopComponentOnDisplay: matched in display block line=$s")
                    val comp = extractComponentFromDumpsysLine(s)
                    if (comp.isNotBlank()) return comp
                }
            }
        }

        Log.w(
            TAG,
            "getTopComponentOnDisplay: no resumed activity found for displayId=$displayId. resumedLines.size=${resumedLines.size} resumedLines=${resumedLines.joinToString(" | ").take(900)}"
        )
        return ""
    }

    private fun extractComponentFromDumpsysLine(line: String): String {
        val s = line.trim()
        if (s.isEmpty()) return ""
        val re = Regex("([a-zA-Z0-9_]+\\.[a-zA-Z0-9_\\.]+/[a-zA-Z0-9_\\.\\$]+)")
        val m = re.find(s) ?: return ""
        return m.groupValues.getOrNull(1).orEmpty()
    }

    private fun extractPackageFromComponent(component: String): String {
        val c = component.trim()
        if (c.isEmpty()) return ""
        val idx = c.indexOf('/')
        if (idx <= 0) return ""
        return c.substring(0, idx).trim()
    }

    private fun resolveLaunchComponentViaShizukuBestEffort(pkg: String): String {
        val p = pkg.trim()
        if (p.isEmpty()) return ""
        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return ""

        val candidates = listOf(
            "cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $p",
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $p",
            "cmd package resolve-activity --brief --user 0 $p",
            "cmd package resolve-activity --brief $p",
        )

        fun extractComponentFromCmdOutput(out: String): String {
            val s = out.trim()
            if (s.isEmpty()) return ""
            // --brief usually returns something like: com.pkg/.MainActivity
            // or a few lines containing the component.
            val line = s.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.contains('/') && !it.startsWith("warning", ignoreCase = true) }
                .orEmpty()
            if (line.isEmpty()) return ""
            val token = line.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
            if (!token.contains('/')) return ""
            return token
        }

        for (cmd in candidates) {
            val r = runCatching { ShizukuBridge.execResult(cmd) }.getOrNull() ?: continue
            val out = r.stdoutText()
            val comp = extractComponentFromCmdOutput(out)
            if (r.exitCode == 0 && comp.isNotBlank()) {
                Log.i(TAG, "resolveLaunchComponentViaShizuku: pkg=$p cmd=$cmd -> $comp")
                return comp
            }
            Log.w(TAG, "resolveLaunchComponentViaShizuku failed: exitCode=${r.exitCode} cmd=$cmd stderr=${r.stderrText().trim().take(200)} stdout=${out.trim().take(200)}")
        }
        return ""
    }

    private fun resolveLaunchComponentBestEffort(pkg: String): String {
        val p = pkg.trim()
        if (p.isEmpty()) return ""

        val launchCn = try {
            packageManager.getLaunchIntentForPackage(p)?.component
        } catch (_: Exception) {
            null
        } ?: run {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    setPackage(p)
                }
                val ri = packageManager.queryIntentActivities(i, 0).firstOrNull()
                ri?.activityInfo?.let { android.content.ComponentName(it.packageName, it.name) }
            } catch (_: Exception) {
                null
            }
        }

        return if (launchCn != null) {
            "${launchCn.packageName}/${launchCn.className}"
        } else {
            // Android 11+ package visibility might block PackageManager from seeing 3rd-party launchers.
            resolveLaunchComponentViaShizukuBestEffort(p)
        }
    }

    private fun switchMainTopAppToVirtualDisplayIfWelcomeBestEffort() {
        val did = VirtualDisplayController.getDisplayId()
        if (did == null) {
            Log.w(TAG, "castToVirtual failed: displayId is null")
            runCatching { ChatEventBus.postText(MessageRole.AI, "投屏到虚拟屏失败：未获取到虚拟屏 displayId") }
            return
        }

        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) {
            Log.w(TAG, "castToVirtual failed: shizuku not ready (ping=${ShizukuBridge.pingBinder()} perm=${ShizukuBridge.hasPermission()})")
            runCatching { ChatEventBus.postText(MessageRole.AI, "投屏到虚拟屏失败：Shizuku 未连接或未授权") }
            return
        }

        val virtualTop = getTopComponentOnDisplayBestEffort(did)
        val welcomeComponent = "$packageName/.WelcomeActivity"
        if (virtualTop != welcomeComponent) {
            Log.i(TAG, "castToVirtual skip: virtualTop=$virtualTop (not welcome)")
            return
        }

        val mainTop = getTopComponentOnDisplayBestEffort(0)
        if (mainTop.isBlank()) {
            Log.w(TAG, "castToVirtual failed: main top component blank")
            runCatching { ChatEventBus.postText(MessageRole.AI, "投屏到虚拟屏失败：未解析到主屏顶层 Activity") }
            return
        }

        val mainPkg = extractPackageFromComponent(mainTop)
        if (mainPkg.isBlank()) {
            runCatching { ChatEventBus.postText(MessageRole.AI, "投屏到虚拟屏失败：主屏顶层组件解析不到包名：$mainTop") }
            return
        }

        // Never cast our own app (especially chat UI) to the virtual display.
        if (mainPkg == packageName) {
            runCatching {
                ChatEventBus.postText(
                    MessageRole.AI,
                    "检测到主屏当前为本应用（$mainTop），已跳过投屏到虚拟屏。"
                )
            }
            return
        }

        val launchComponent = resolveLaunchComponentBestEffort(mainPkg)
        if (launchComponent.isBlank()) {
            runCatching { ChatEventBus.postText(MessageRole.AI, "投屏到虚拟屏失败：无法获取启动入口：$mainPkg") }
            return
        }

        val flags = 0x10200000
        val candidates = listOf(
            "cmd activity start-activity --user 0 --display $did --windowingMode 1 --activity-reorder-to-front -n $launchComponent -f $flags",
            "cmd activity start-activity --user 0 --display $did --windowingMode 1 -n $launchComponent -f $flags",
            "am start --user 0 --display $did --activity-reorder-to-front -n $launchComponent -f $flags",
            "am start --user 0 --display $did -n $launchComponent -f $flags",
        )

        var success = false
        val errors = StringBuilder()
        for (c in candidates) {
            val r = runCatching { ShizukuBridge.execResult(c) }.getOrNull()
            if (r != null) {
                Log.i(
                    TAG,
                    "castToVirtual exec: exitCode=${r.exitCode} cmd=$c stderr=${r.stderrText().trim().take(200)} stdout=${r.stdoutText().trim().take(200)}"
                )
            }
            if (r != null && r.exitCode == 0) {
                success = true
                break
            }
            val err = r?.stderrText().orEmpty().trim()
            val out = r?.stdoutText().orEmpty().trim()
            if (err.isNotEmpty() || out.isNotEmpty()) {
                errors.append("\n").append(c).append("\n").append(err.ifEmpty { out }.take(220))
            }
        }

        if (success) {
            runCatching { ChatEventBus.postText(MessageRole.AI, "已将主屏应用投屏到虚拟屏：$mainPkg -> $launchComponent") }
        } else {
            runCatching {
                ChatEventBus.postText(
                    MessageRole.AI,
                    "投屏到虚拟屏失败：$mainPkg -> $launchComponent" + if (errors.isNotEmpty()) ("\n" + errors.toString().trim()) else ""
                )
            }
        }
    }

    private fun switchToMainOrCastFromMainDependingOnVirtualTopBestEffort() {
        val did = VirtualDisplayController.getDisplayId()
        if (did == null) {
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换失败：未获取到虚拟屏 displayId") }
            return
        }
        val virtualTop = getTopComponentOnDisplayBestEffort(did)
        val welcomeComponent = "$packageName/.WelcomeActivity"
        if (virtualTop == welcomeComponent) {
            switchMainTopAppToVirtualDisplayIfWelcomeBestEffort()
        } else {
            switchVirtualTopAppToMainDisplayBestEffort()
        }
    }

    private fun switchVirtualTopAppToMainDisplayBestEffort() {
        val did = VirtualDisplayController.getDisplayId()
        if (did == null) {
            Log.w(TAG, "switchToMain failed: displayId is null")
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换到主屏失败：未获取到虚拟屏 displayId") }
            return
        }

        if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) {
            Log.w(TAG, "switchToMain failed: shizuku not ready (ping=${ShizukuBridge.pingBinder()} perm=${ShizukuBridge.hasPermission()})")
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换到主屏失败：Shizuku 未连接或未授权") }
            return
        }

        val component = getTopComponentOnDisplayBestEffort(did)
        if (component.isBlank()) {
            Log.w(TAG, "switchToMain failed: top component blank for displayId=$did")
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换到主屏失败：未解析到虚拟屏顶层 Activity") }
            return
        }

        Log.i(TAG, "switchToMain: displayId=$did topComponent=$component")

        val pkg = extractPackageFromComponent(component)
        if (pkg.isBlank()) {
            Log.w(TAG, "switchToMain failed: pkg blank from component=$component")
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换到主屏失败：顶层组件解析不到包名：$component") }
            return
        }

        if (pkg == packageName) {
            Log.i(TAG, "switchToMain skip: top app is self pkg=$pkg component=$component")
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换到主屏：虚拟屏当前顶层已是本App（$component），无需切换") }
            return
        }

        Log.i(TAG, "switchToMain: resolved pkg=$pkg")

        val launchCn = try {
            packageManager.getLaunchIntentForPackage(pkg)?.component
        } catch (_: Exception) {
            null
        } ?: run {
            try {
                val i = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                }
                val ri = packageManager.queryIntentActivities(i, 0).firstOrNull()
                ri?.activityInfo?.let { android.content.ComponentName(it.packageName, it.name) }
            } catch (_: Exception) {
                null
            }
        }

        val launchComponent = if (launchCn != null) {
            "${launchCn.packageName}/${launchCn.className}"
        } else {
            // Android 11+ package visibility might block PackageManager from seeing 3rd-party launchers.
            resolveLaunchComponentViaShizukuBestEffort(pkg)
        }

        if (launchComponent.isBlank()) {
            Log.w(TAG, "switchToMain failed: launch component null for pkg=$pkg")
            runCatching { ChatEventBus.postText(MessageRole.AI, "切换到主屏失败：无法获取启动入口：$pkg") }
            return
        }

        Log.i(TAG, "switchToMain: launchComponent=$launchComponent")

        val flags = 0x10200000
        val candidates = listOf(
            "cmd activity start-activity --user 0 --display 0 --activity-reorder-to-front -n $launchComponent -f $flags",
            "cmd activity start-activity --user 0 --display 0 -n $launchComponent -f $flags",
            "am start --user 0 --display 0 --activity-reorder-to-front -n $launchComponent -f $flags",
            "am start --user 0 --display 0 -n $launchComponent -f $flags",
        )

        val errors = StringBuilder()
        var success = false
        for (c in candidates) {
            val r = runCatching { ShizukuBridge.execResult(c) }.getOrNull()
            if (r != null) {
                val err = r.stderrText().trim()
                val out = r.stdoutText().trim()
                Log.i(
                    TAG,
                    "switchToMain exec: exitCode=${r.exitCode} cmd=$c stderr=${err.take(300)} stdout=${out.take(300)}"
                )
            } else {
                Log.w(TAG, "switchToMain exec: result is null cmd=$c")
            }
            if (r != null && r.exitCode == 0) {
                success = true
                break
            }
            val err = r?.stderrText().orEmpty().trim()
            val out = r?.stdoutText().orEmpty().trim()
            if (err.isNotEmpty() || out.isNotEmpty()) {
                errors.append("\n").append(c).append("\n").append(err.ifEmpty { out }.take(280))
            }
        }

        if (success) {
            runCatching { ChatEventBus.postText(MessageRole.AI, "已尝试将 $pkg 切换到主屏：$launchComponent") }
            // After switching the app back to main display, show welcome UI on the virtual display again.
            runCatching { VirtualDisplayController.showWelcomeOnActiveDisplayBestEffort(this@FloatingStatusService) }
        } else {
            runCatching {
                ChatEventBus.postText(
                    MessageRole.AI,
                    "切换到主屏失败：$pkg -> $launchComponent" + if (errors.isNotEmpty()) ("\n" + errors.toString().trim()) else ""
                )
            }
        }
    }

    private fun clampOverlayPosition(lp: WindowManager.LayoutParams, barWidthPx: Int, barHeightPx: Int) {
        val sw = screenWidthPx().coerceAtLeast(1)
        val sh = screenHeightPx().coerceAtLeast(1)
        val maxX = (sw - barWidthPx).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)

        // gravity = CENTER_VERTICAL: y 是相对于屏幕中线的偏移
        val maxY = ((sh - barHeightPx) / 2).coerceAtLeast(0)
        lp.y = lp.y.coerceIn(-maxY, maxY)
    }

    private fun clampToolboxPosition(lp: WindowManager.LayoutParams) {
        val toolboxW = toolboxWidthPx()
        val h = lp.height.takeIf { it > 0 } ?: toolboxWindowHeightPx()
        clampOverlayPosition(lp, toolboxW, h)
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

        runCatching {
            startForegroundInternal(contentText = "语音助手运行中")
        }.onFailure {
            runCatching {
                val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("AutoGLM")
                    .setContentText("语音助手运行中")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .build()
                startForeground(NOTIFICATION_ID, notification)
            }
        }

        imeFocusDeadlockController = ImeFocusDeadlockController(workerScope).apply {
            callback = object : ImeFocusDeadlockController.Callback {
                override fun onLockStateChanged(locked: Boolean, displayId: Int, detail: String) {
                    Log.i(TAG, "focus-deadlock: locked=$locked did=$displayId detail=${detail.take(220)}")
                    mainHandler.post {
                        applyFocusDeadlockUiState(locked, displayId)
                    }
                }
            }
            start()
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
        diag("onConfigurationChanged:before")
        val barW = bar.width.takeIf { it > 0 } ?: (if (toolboxExpanded) (handleWidthPx() + toolboxWidthPx()) else handleWidthPx())
        val barH = bar.height.takeIf { it > 0 } ?: dp(120)
        clampOverlayPosition(lp, barW, barH)
        snapToolboxToEdge(lp)
        try {
            wm.updateViewLayout(bar, lp)
        } catch (_: Exception) {
        }

        val toolbox = toolboxPanelView
        val tlp = toolboxLayoutParams
        if (toolbox != null && tlp != null) {
            tlp.height = toolboxWindowHeightPx()
            clampToolboxPosition(tlp)
            try {
                wm.updateViewLayout(toolbox, tlp)
            } catch (_: Exception) {
            }
        }

        updateSummaryBubblePositionIfNeeded()
        diag("onConfigurationChanged:after")
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
                val hasText = try { intent.hasExtra(EXTRA_TEXT) } catch (_: Exception) { false }
                val text = if (hasText) intent.getStringExtra(EXTRA_TEXT).orEmpty() else ""

                val hasIntervention = try { intent.hasExtra(EXTRA_INTERVENTION_REQUEST_ID) } catch (_: Exception) { false }
                val reqId = if (hasIntervention) intent.getLongExtra(EXTRA_INTERVENTION_REQUEST_ID, 0L) else 0L
                val reqType = if (hasIntervention) intent.getStringExtra(EXTRA_INTERVENTION_REQUEST_TYPE).orEmpty() else ""
                val reqMsg = if (hasIntervention) intent.getStringExtra(EXTRA_INTERVENTION_REQUEST_MESSAGE).orEmpty() else ""

                mainHandler.post {
                    ensureViewCreatedIfNeeded()
                    applyLayoutParamsIfPossible()

                    if (hasIntervention) {
                        // 空 type/message 视为清理。
                        if (reqType.isBlank() && reqMsg.isBlank()) {
                            interventionRequestId = 0L
                            interventionRequestType = ""
                            interventionRequestMessage = ""
                        } else {
                            interventionRequestId = reqId
                            interventionRequestType = reqType
                            interventionRequestMessage = reqMsg
                        }
                        applyInterventionUiState()
                    }

                    if (hasText) {
                        updateText(text)
                    }
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
            imeFocusDeadlockController?.stop()
        } catch (_: Exception) {
        }
        imeFocusDeadlockController = null

        stopRecordingInternal(cancel = true)

        try {
            touchLayerHandler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        touchLayerHandler = null
        try {
            touchLayerThread?.quitSafely()
        } catch (_: Exception) {
        }
        touchLayerThread = null

        try {
            workerScope.cancel()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun applyFocusDeadlockUiState(locked: Boolean, displayId: Int) {
        focusDeadlockLocked = locked

        val blocker = focusDeadlockBlockerView
        if (blocker != null) {
            blocker.visibility = if (locked) View.VISIBLE else View.GONE
            blocker.isEnabled = locked
            blocker.isClickable = locked
        }

        val tip = focusDeadlockTipView
        if (tip != null) {
            focusDeadlockTipAutoHideRunnable?.let { mainHandler.removeCallbacks(it) }
            focusDeadlockTipAutoHideRunnable = null

            if (locked) {
                tip.text = "焦点锁定中：虚拟屏幕正在使用输入法"
                tip.visibility = View.VISIBLE
            } else {
                tip.text = "焦点锁定已解除"
                tip.visibility = View.VISIBLE
                val r = Runnable {
                    focusDeadlockTipView?.visibility = View.GONE
                }
                focusDeadlockTipAutoHideRunnable = r
                mainHandler.postDelayed(r, 1200L)
            }
        }

        val toMain = toolboxToMainButtonView
        if (toMain != null) {
            toMain.isEnabled = !locked
            toMain.alpha = if (locked) 0.45f else 1f
        }

        if (locked && displayId > 0) {
            runCatching {
                val lp = focusDeadlockTipLayoutParams
                val wm = windowManager
                val v = focusDeadlockTipView
                if (lp != null && wm != null && v != null && v.parent != null) {
                    wm.updateViewLayout(v, lp)
                }
            }
        }
    }

    private fun ensureViewCreatedIfNeeded() {
        if (barView != null) return

        val context = mainDisplayContext()
        val barWidth = handleWidthPx()

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#99000000"))
        }
        barBackgroundDrawable = bgDrawable
        applyDockBackground(dockOnRight)

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

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
        }

        val btn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = android.widget.ImageView.ScaleType.MATRIX
            micFlowDrawable = FlowBorderDrawable(
                fillColor = Color.parseColor("#D32F2F"),
                strokeWidthPx = dp(2).toFloat(),
                cornerRadiusPx = dp(12).toFloat(),
            )
            background = micFlowDrawable
            setPadding(0, 0, 0, 0)
            post {
                try {
                    val scale = 0.945f
                    val cx = width / 2f
                    val cy = height / 2f
                    imageMatrix = android.graphics.Matrix().apply {
                        setScale(scale, scale, cx, cy)
                    }
                } catch (_: Exception) {
                }
            }
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
                                TaskControl.forceStopPythonKeepVirtualDisplay()
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

                    else -> false
                }
            }
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = dp(10) }
        }

        container.addView(tv)
        container.addView(btn)

        val panelW = toolboxWidthPx()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(panelW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            setPadding(dp(0), dp(10), dp(0), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0B0F16"))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val panelContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val previewTexture = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 这里必须为非 opaque：VirtualDisplay 输出的 buffer 在做 transform(等比缩放)后，
            // 未覆盖区域需要能显示下方的“底板 View”，否则某些 ROM 会直接透到窗口背后。
            setOpaque(false)
            visibility = View.GONE
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    // Texture surface 可用时，绑定 VirtualDisplay 输出到该 surface，实现直出预览。
                    if (toolboxExpanded && isVirtualIsolatedMode()) {
                        updateVirtualPreviewAspectRatioBestEffort()
                        updateVirtualPreviewBufferSizeBestEffort()
                        applyVirtualPreviewTransformBestEffort()
                        bindVirtualPreviewToSurfaceIfPossible()
                    }
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    // 尺寸变化时更新 transform，保持等比显示。
                    if (toolboxExpanded && isVirtualIsolatedMode()) {
                        updateVirtualPreviewAspectRatioBestEffort()
                        updateVirtualPreviewBufferSizeBestEffort()
                        applyVirtualPreviewTransformBestEffort()
                        bindVirtualPreviewToSurfaceIfPossible()
                    }
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    // surface 销毁后立即恢复到 ImageReader，避免 VirtualDisplay 持有无效 surface。
                    if (isVirtualIsolatedMode()) {
                        unbindVirtualPreviewSurfaceBestEffort()
                    }
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                    // ignore
                }
            }
        }

        val previewImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setBackgroundColor(Color.parseColor("#22000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val modeText = TextView(context).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = ""
            maxLines = 1
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#66000000"))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(8)
                topMargin = dp(8)
            }
        }

        val previewWrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260)
            )
            setBackgroundColor(Color.BLACK)
            addView(View(context).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            addView(previewTexture)
            addView(previewImage)
            addView(modeText)
        }

        val previewLocalIndicator = View(context).apply {
            val s = dp(14)
            layoutParams = FrameLayout.LayoutParams(s, s)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC00E5FF"))
                setStroke(dp(2), Color.WHITE)
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        previewWrap.addView(previewLocalIndicator)

        val previewTouchLayer = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = if (isVirtualIsolatedMode()) View.VISIBLE else View.GONE

            setOnTouchListener { _, ev ->
                if (!isVirtualIsolatedMode()) return@setOnTouchListener false

                val t0 = android.os.SystemClock.uptimeMillis()
                val seq = try {
                    ev.eventTime
                } catch (_: Exception) {
                    -1L
                }

                val wrap = toolboxPreviewWrapView
                val vw = wrap?.width ?: 0
                val vh = wrap?.height ?: 0
                val action = ev.actionMasked
                val now = android.os.SystemClock.uptimeMillis()

                ensureTouchLayerThreadStarted()
                val h = touchLayerHandler
                if (h != null) {
                    val sample = TouchLayerSample(
                        action = action,
                        viewX = ev.x,
                        viewY = ev.y,
                        viewW = vw,
                        viewH = vh,
                        eventTime = seq,
                    )
                    val MSG_MOVE = 2
                    val MSG_OTHER = 1
                    val what = if (action == MotionEvent.ACTION_MOVE) MSG_MOVE else MSG_OTHER

                    if (what == MSG_MOVE) {
                        if (now - touchLayerLastMovePostMs >= 50L) {
                            touchLayerLastMovePostMs = now
                            h.removeMessages(MSG_MOVE)
                            h.obtainMessage(MSG_MOVE, sample).sendToTarget()
                        }
                    } else {
                        h.obtainMessage(MSG_OTHER, sample).sendToTarget()
                    }
                }

                val cost = android.os.SystemClock.uptimeMillis() - t0
                val lagged = cost >= 16L
                updateTouchLayerPerfSuffix(action, seq, cost, lagged)
                true
            }
        }
        previewWrap.addView(previewTouchLayer)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        fun makeLabeledToolButton(iconRes: Int, label: String, onClick: () -> Unit): View {
            val wrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val btnV = ImageButton(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            }
            val tvLabel = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#CCFFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            wrap.addView(btnV)
            wrap.addView(tvLabel)
            return wrap
        }

        val btnToApp = makeLabeledToolButton(
            android.R.drawable.ic_menu_myplaces,
            "切换本app"
        ) {
            try {
                runCatching {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(homeIntent)
                }

                mainHandler.postDelayed({
                    runCatching {
                        val i = Intent().apply {
                            component = ComponentName(context, ChatActivity::class.java)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(i)
                    }
                }, 120L)
            } catch (_: Exception) {
            }
        }

        val btnToMain = makeLabeledToolButton(
            android.R.drawable.ic_menu_view,
            "切换到主屏幕"
        ) {
            if (focusDeadlockLocked) {
                applyFocusDeadlockUiState(true, VirtualDisplayController.getDisplayId() ?: 0)
                return@makeLabeledToolButton
            }
            // Make expand animation feel immediate before heavy operations.
            if (!toolboxExpanded) {
                setToolboxExpanded(true, animate = true)
            }
            workerScope.launch {
                try {
                    switchToMainOrCastFromMainDependingOnVirtualTopBestEffort()
                } catch (_: Exception) {
                }
            }
        }

        val btnVirtualBack = makeLabeledToolButton(
            android.R.drawable.ic_media_previous,
            "虚拟返回"
        ) {
            if (!isVirtualIsolatedMode()) return@makeLabeledToolButton
            val did = VirtualDisplayController.getDisplayId() ?: return@makeLabeledToolButton
            if (did <= 0) return@makeLabeledToolButton
            if (!ShizukuBridge.pingBinder() || !ShizukuBridge.hasPermission()) return@makeLabeledToolButton
            workerScope.launch {
                runCatching {
                    val top = getTopComponentOnDisplayBestEffort(did).trim()
                    val welcomeComponent = "$packageName/.WelcomeActivity"

                    if (top == welcomeComponent) {
                        return@runCatching
                    }

                    if (top.isBlank()) {
                        VirtualDisplayController.showWelcomeOnActiveDisplayBestEffort(this@FloatingStatusService)
                        return@runCatching
                    }

                    val pkg = extractPackageFromComponent(top)
                    if (pkg.isBlank()) {
                        VirtualDisplayController.showWelcomeOnActiveDisplayBestEffort(this@FloatingStatusService)
                        return@runCatching
                    }

                    if (pkg != packageName) {
                        VirtualDisplayController.ensureFocusedDisplayBestEffort()
                        virtualAsyncInputInjector.injectKeyEventAsync(did, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
                        virtualAsyncInputInjector.injectKeyEventAsync(did, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
                        return@runCatching
                    }

                    VirtualDisplayController.ensureFocusedDisplayBestEffort()
                    virtualAsyncInputInjector.injectKeyEventAsync(did, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
                    virtualAsyncInputInjector.injectKeyEventAsync(did, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
                }
            }
        }

        val btnClose = makeLabeledToolButton(
            android.R.drawable.ic_menu_close_clear_cancel,
            "关闭"
        ) {
            setToolboxExpanded(false, animate = true)
        }

        val btnInterventionOk = makeLabeledToolButton(
            android.R.drawable.ic_menu_send,
            "同意/完成"
        ) {
            val id = interventionRequestId
            if (id <= 0L) return@makeLabeledToolButton
            UserInterventionGate.respond(id, true)
        }.apply {
            visibility = View.GONE
        }

        val btnInterventionNo = makeLabeledToolButton(
            android.R.drawable.ic_menu_close_clear_cancel,
            "拒绝"
        ) {
            val id = interventionRequestId
            if (id <= 0L) return@makeLabeledToolButton
            UserInterventionGate.respond(id, false)
        }.apply {
            visibility = View.GONE
        }

        row.addView(btnToApp)
        row.addView(btnToMain)
        row.addView(btnVirtualBack)
        row.addView(btnInterventionOk)
        row.addView(btnInterventionNo)
        row.addView(btnClose)

        panelContent.addView(previewWrap)
        panelContent.addView(row)
        panel.addView(panelContent)

        toolboxPanelView = panel
        toolboxPreviewView = previewImage
        toolboxPreviewTextureView = previewTexture
        toolboxPreviewWrapView = previewWrap
        toolboxPreviewTouchLayerView = previewTouchLayer
        toolboxPreviewLocalIndicatorView = previewLocalIndicator
        toolboxModeTextView = modeText
        toolboxCloseButtonView = btnClose
        toolboxToMainButtonView = btnToMain
        toolboxInterventionButtonView = btnInterventionOk
        toolboxInterventionRejectButtonView = btnInterventionNo

        toolboxHandleView = container

        root.addView(container, FrameLayout.LayoutParams(barWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        panel.translationX = if (dockOnRight) panelW.toFloat() else (-panelW).toFloat()
        panel.visibility = View.GONE
        toolboxExpanded = false

        container.setOnTouchListener { _, ev ->
            val lp = barLayoutParams
            val tlp = toolboxLayoutParams
            val wm = windowManager
            val bar = barView
            val toolbox = toolboxPanelView
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
                        if (!longPressActive) {
                            return@setOnTouchListener true
                        }
                        if (kotlin.math.abs(dx) < touchSlopPx && kotlin.math.abs(dy) < touchSlopPx) {
                            return@setOnTouchListener true
                        }
                        dragging = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    }

                    val barH = bar.height.takeIf { it > 0 } ?: dp(120)
                    lp.x = dragDownLpX + dx
                    lp.y = dragDownLpY + dy
                    clampOverlayPosition(lp, handleWidthPx(), barH)
                    snapToolboxToEdge(lp)
                    try {
                        wm.updateViewLayout(bar, lp)
                    } catch (_: Exception) {
                    }

                    if (toolboxExpanded && toolbox != null && tlp != null) {
                        try {
                            wm.updateViewLayout(toolbox, tlp)
                        } catch (_: Exception) {
                        }
                    }

                    updateSummaryBubblePositionIfNeeded()
                    return@setOnTouchListener true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    val wasLongPressActive = longPressActive

                    if (dragging || longPressActive) {
                        snapToolboxToEdge(lp)
                        try {
                            wm.updateViewLayout(bar, lp)
                        } catch (_: Exception) {
                        }
                        if (toolboxExpanded && toolbox != null && tlp != null) {
                            try {
                                wm.updateViewLayout(toolbox, tlp)
                            } catch (_: Exception) {
                            }
                        }
                        updateSummaryBubblePositionIfNeeded()
                    }

                    if (longPressActive) {
                        setDragHighlight(false)
                        longPressActive = false
                    }

                    if (!dragging && !wasLongPressActive) {
                        if (!isVirtualIsolatedMode()) {
                            openChatActivityBestEffort()
                            return@setOnTouchListener true
                        }
                        setToolboxExpanded(!toolboxExpanded, animate = true)
                    }
                    return@setOnTouchListener true
                }
            }
            false
        }

        val blockerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val blockerView = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.GONE

            var lastLogAt = 0L
            setOnTouchListener { _, ev ->
                if (!focusDeadlockLocked) return@setOnTouchListener false
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 250L) {
                    lastLogAt = now
                    val rx = try { ev.rawX } catch (_: Exception) { -1f }
                    val ry = try { ev.rawY } catch (_: Exception) { -1f }
                    Log.i(TAG, "focus-deadlock touch: a=${ev.actionMasked} raw=${rx},${ry}")
                }
                true
            }
        }

        val tipView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = dp(14).toFloat()
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val tipParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(30)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        focusDeadlockBlockerView = blockerView
        focusDeadlockBlockerLayoutParams = blockerParams
        focusDeadlockTipView = tipView
        focusDeadlockTipLayoutParams = tipParams

        val barParams = WindowManager.LayoutParams(
            handleWidthPx(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val toolboxParams = WindowManager.LayoutParams(
            toolboxWidthPx(),
            toolboxWindowHeightPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        toolboxLayoutParams = toolboxParams
        toolboxPanelView = panel

        windowManager?.addView(blockerView, blockerParams)
        windowManager?.addView(panel, toolboxParams)
        windowManager?.addView(root, barParams)
        windowManager?.addView(tipView, tipParams)

        root.translationX = if (dockOnRight) barWidth.toFloat() else (-barWidth).toFloat()

        snapToolboxToEdge(barParams)
        try {
            windowManager?.updateViewLayout(root, barParams)
            windowManager?.updateViewLayout(panel, toolboxParams)
        } catch (_: Exception) {
        }

        barView = root
        statusTextView = tv
        stopButtonView = btn
        barLayoutParams = barParams

        diag("ensureViewCreated")

        updateText("等待指令")

        applyFocusDeadlockUiState(focusDeadlockLocked, VirtualDisplayController.getDisplayId() ?: 0)

        // 确保 UI 能响应当前 pending 的接管/确认请求。
        applyInterventionUiState()
    }

    private fun applyInterventionUiState() {
        val okBtn = toolboxInterventionButtonView
        val noBtn = toolboxInterventionRejectButtonView
        if (okBtn == null || noBtn == null) return

        val id = interventionRequestId
        val type = interventionRequestType
        val hasPending = id > 0L

        if (!hasPending) {
            okBtn.visibility = View.GONE
            noBtn.visibility = View.GONE
            return
        }

        // TAKEOVER：只显示“完成”按钮；CONFIRMATION：显示“同意/拒绝”。
        val isTakeover = type.equals("TAKEOVER", ignoreCase = true)
        okBtn.visibility = View.VISIBLE
        noBtn.visibility = if (isTakeover) View.GONE else View.VISIBLE
    }

    private fun applyLayoutParamsIfPossible() {
        val wm = windowManager ?: return
        val bar = barView
        val lp = barLayoutParams
        val toolbox = toolboxPanelView
        val tlp = toolboxLayoutParams
        if (bar == null || lp == null) return

        diag("applyLayoutParams:before")

        val barW = bar.width.takeIf { it > 0 } ?: handleWidthPx()
        val barH = bar.height.takeIf { it > 0 } ?: dp(120)
        clampOverlayPosition(lp, barW, barH)
        snapToolboxToEdge(lp)
        try {
            wm.updateViewLayout(bar, lp)
        } catch (_: Exception) {
        }

        if (toolbox != null && tlp != null) {
            tlp.height = toolboxWindowHeightPx()
            clampToolboxPosition(tlp)
            try {
                wm.updateViewLayout(toolbox, tlp)
            } catch (_: Exception) {
            }
        }
        diag("applyLayoutParams:after")
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
        val w = handleWidthPx()
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
        val w = handleWidthPx()
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
            focusDeadlockTipView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            focusDeadlockBlockerView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            barView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        try {
            toolboxPanelView?.let { wm.removeView(it) }
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
        toolboxPanelView = null
        toolboxPreviewView = null
        toolboxCloseButtonView = null
        toolboxToMainButtonView = null
        barLayoutParams = null
        toolboxLayoutParams = null
        tempFocusableEnabled = false
        isShowing = false

        focusDeadlockTipAutoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        focusDeadlockTipAutoHideRunnable = null

        focusDeadlockBlockerView = null
        focusDeadlockBlockerLayoutParams = null
        focusDeadlockTipView = null
        focusDeadlockTipLayoutParams = null
        focusDeadlockLocked = false
    }

    private fun showSummaryBubbleInternal(message: String) {
        val msg = message.trim()
        if (msg.isEmpty()) return

        removeSummaryBubbleIfNeeded(animate = false)

        summaryBubbleRemoving = false

        val wm = windowManager ?: return
        val bar = barView ?: return
        val barLp = barLayoutParams ?: return

        val ctx = mainDisplayContext()

        val bubble = LinearLayout(ctx).apply {
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

        val closeBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = null
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                removeSummaryBubbleIfNeeded(animate = true)
            }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(closeBtn, LinearLayout.LayoutParams(dp(20), dp(20)))
        }

        val tv = TextView(ctx).apply {
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    private fun applyDockBackground(dockRight: Boolean) {
        val bg = barBackgroundDrawable ?: return
        val r = dp(14).toFloat()
        bg.cornerRadii = if (dockRight) {
            floatArrayOf(
                r, r,
                0f, 0f,
                0f, 0f,
                r, r,
            )
        } else {
            floatArrayOf(
                0f, 0f,
                r, r,
                r, r,
                0f, 0f,
            )
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
        val r = mainDisplayContext().resources
        val resId = r.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) r.getDimensionPixelSize(resId) else dp(24)
        return if (h <= 0) dp(24) else h
    }

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            mainDisplayMetrics()
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
                    ChatEventBus.postText(MessageRole.ACTION, msg)
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

            var aiStreamOpened = false
            var aiStreamFirstAtMs = 0L
            val aiStreamBootstrapBuf = StringBuilder()
            var aiStreamBootstrapFlushPosted = false
            val aiStreamBootstrapDelayMs = 140L
            val aiStreamBootstrapMinChars = 14

            val aiBootstrapFlushRunnable = Runnable {
                aiStreamBootstrapFlushPosted = false
                if (aiStreamOpened) return@Runnable
                if (aiStreamBootstrapBuf.isEmpty()) return@Runnable
                aiStreamOpened = true
                ChatEventBus.postText(MessageRole.AI, aiStreamBootstrapBuf.toString(), kind = lastAiStreamKind)
                aiStreamBootstrapBuf.setLength(0)
            }

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

                    val shouldMerge =
                        (isTaskRunningState || mergeStreamAfterStop) &&
                            !kind.startsWith("STEP") &&
                            !kind.startsWith("OP_") &&
                            kind != "OPERATION"
                    if (!(shouldMerge && ChatEventBus.appendToLastText(MessageRole.ACTION, kind, text))) {
                        ChatEventBus.postText(MessageRole.ACTION, text, kind = kind)
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
                    if (bytes == null || bytes.isEmpty()) return@AndroidAgentCallback
                    ChatEventBus.postImage(bytes)
                },
                onAssistant = { msg ->
                    assistantTextUpdated = true
                    val classified = TaskStreamClassifier.classifyAssistant(msg)
                    val kind = classified.kind
                    val text = classified.text
                    if (text.isEmpty()) return@AndroidAgentCallback

                    if (isTaskRunningState || mergeStreamAfterStop) {
                        if (!aiStreamOpened) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (aiStreamFirstAtMs <= 0L) aiStreamFirstAtMs = now
                            // 当 kind 发生变化时，先把已有缓冲作为独立气泡落地，避免不同阶段混到同一条。
                            try {
                                val prevKind = lastAiStreamKind
                                if (prevKind != null && prevKind != kind && aiStreamBootstrapBuf.isNotEmpty()) {
                                    aiStreamOpened = true
                                    ChatEventBus.postText(MessageRole.AI, aiStreamBootstrapBuf.toString(), kind = prevKind)
                                    aiStreamBootstrapBuf.setLength(0)
                                }
                            } catch (_: Exception) {
                            }
                            aiStreamBootstrapBuf.append(text)
                            lastAiStreamKind = kind

                            val shouldFlush =
                                aiStreamBootstrapBuf.length >= aiStreamBootstrapMinChars ||
                                    (now - aiStreamFirstAtMs) >= aiStreamBootstrapDelayMs ||
                                    text.contains("\n")

                            if (shouldFlush) {
                                aiStreamOpened = true
                                ChatEventBus.postText(MessageRole.AI, aiStreamBootstrapBuf.toString(), kind = kind)
                                aiStreamBootstrapBuf.setLength(0)
                            } else if (!aiStreamBootstrapFlushPosted) {
                                aiStreamBootstrapFlushPosted = true
                                mainHandler.postDelayed(aiBootstrapFlushRunnable, aiStreamBootstrapDelayMs)
                            }
                            return@AndroidAgentCallback
                        }

                        if (!ChatEventBus.appendToLastText(MessageRole.AI, kind, text)) {
                            // Fallback: create a new message only if append fails unexpectedly.
                            ChatEventBus.postText(MessageRole.AI, text, kind = kind)
                        }
                    } else {
                        ChatEventBus.postText(MessageRole.AI, text, kind = kind)
                    }
                },
                onError = { msg ->
                    val t = msg.trim()
                    if (t.isEmpty()) return@AndroidAgentCallback
                    ChatEventBus.postText(MessageRole.ACTION, t)
                    update(this@FloatingStatusService, t)
                },
                onDone = { msg ->
                    assistantTextUpdated = true
                    lastAiStreamKind = "SUMMARY"
                    mergeStreamAfterStop = false

                    // Flush any pending bootstrap buffer before final summary.
                    if (!aiStreamOpened && aiStreamBootstrapBuf.isNotEmpty()) {
                        aiStreamOpened = true
                        ChatEventBus.postText(MessageRole.AI, aiStreamBootstrapBuf.toString(), kind = lastAiStreamKind)
                        aiStreamBootstrapBuf.setLength(0)
                    }
                    ChatEventBus.postText(MessageRole.AI, msg, kind = lastAiStreamKind)
                    setIdle(this@FloatingStatusService, "等待指令")
                    try {
                        showSummaryBubble(this@FloatingStatusService, msg)
                    } catch (_: Exception) {
                    }
                },
                onTapIndicator = { x, y ->
                    mainHandler.post {
                        try {
                            val execEnv = ConfigManager(this@FloatingStatusService).getExecutionEnvironment()
                            if (execEnv == ConfigManager.EXEC_ENV_VIRTUAL) {
                                val did = VirtualDisplayController.getDisplayId() ?: 0
                                if (did > 0) {
                                    TapIndicatorService.showAtOnDisplay(this@FloatingStatusService, x, y, did)
                                } else {
                                    TapIndicatorService.showAt(this@FloatingStatusService, x, y)
                                }
                            } else {
                                TapIndicatorService.showAt(this@FloatingStatusService, x, y)
                            }
                        } catch (_: Exception) {
                            TapIndicatorService.showAt(this@FloatingStatusService, x, y)
                        }
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

        // 以用户消息作为“任务边界”：确保下一步任务的流式输出不会合并到上一轮旧气泡。
        // 同时这条记录也能让聊天界面展示“当前执行的指令”。
        ChatEventBus.postText(MessageRole.USER, task)
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

        abandonRecordingAudioFocus()
    }

    private fun requestRecordingAudioFocus(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val listener = audioFocusListener ?: AudioManager.OnAudioFocusChangeListener { }.also {
                    audioFocusListener = it
                }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                val listener = audioFocusListener ?: AudioManager.OnAudioFocusChangeListener { }.also {
                    audioFocusListener = it
                }
                @Suppress("DEPRECATION")
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
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
            } else {
                val listener = audioFocusListener
                if (listener != null) {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(listener)
                }
            }
        } catch (_: Exception) {
        } finally {
            audioFocusRequest = null
        }
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

        @Suppress("OVERRIDE_DEPRECATION")
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

        private const val EXTRA_INTERVENTION_REQUEST_ID = "extra_intervention_request_id"
        private const val EXTRA_INTERVENTION_REQUEST_TYPE = "extra_intervention_request_type"
        private const val EXTRA_INTERVENTION_REQUEST_MESSAGE = "extra_intervention_request_message"

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

        fun setInterventionState(context: Context, requestId: Long, type: String, message: String) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_INTERVENTION_REQUEST_ID, requestId)
                putExtra(EXTRA_INTERVENTION_REQUEST_TYPE, type)
                putExtra(EXTRA_INTERVENTION_REQUEST_MESSAGE, message)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun clearInterventionState(context: Context, requestId: Long) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FloatingStatusService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_INTERVENTION_REQUEST_ID, requestId)
                putExtra(EXTRA_INTERVENTION_REQUEST_TYPE, "")
                putExtra(EXTRA_INTERVENTION_REQUEST_MESSAGE, "")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
