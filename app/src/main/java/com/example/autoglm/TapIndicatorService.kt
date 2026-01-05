package com.example.autoglm

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.hardware.display.DisplayManager

/**
 * 点击指示器悬浮窗服务。
 *
 * **用途**
 * - 在指定坐标显示一个短暂的圆形指示器（通常用于调试/向用户反馈“已注入点击/已接收到点击”）。
 * - 支持在虚拟隔离模式下把指示器绘制到指定 `displayId` 的 WindowManager 上。
 *
 * **引用路径（常见）**
 * - `FloatingStatusService`：点击预览层时显示指示器。
 *
 * **使用注意事项**
 * - 主屏模式下存在坐标补偿（状态栏/悬浮窗 UI 的历史偏移逻辑）；虚拟隔离模式下则不做偏移，
 *   确保圆心严格对齐注入坐标。
 * - 依赖 `SYSTEM_ALERT_WINDOW`：未授权时添加窗口可能失败。
 */
/**
 * 点击指示器悬浮窗服务
 * 在屏幕上指定坐标显示一个圆形指示器，1秒后渐变消失
 */
class TapIndicatorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    // 指示器圆形半径（dp）
    private val indicatorRadiusDp = 20
    // 指示器颜色（半透明蓝色）
    private val indicatorColor = Color.parseColor("#80448AFF")
    // 边框颜色
    private val strokeColor = Color.parseColor("#FF448AFF")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val x = intent?.getFloatExtra(EXTRA_X, -1f) ?: -1f
        val y = intent?.getFloatExtra(EXTRA_Y, -1f) ?: -1f
        val displayId = intent?.getIntExtra(EXTRA_DISPLAY_ID, 0) ?: 0

        if (x >= 0 && y >= 0) {
            showIndicator(x, y, displayId)
        }

        return START_NOT_STICKY
    }

    private fun mainDisplayContext(): Context = runCatching {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d0 = dm.getDisplay(0) ?: return@runCatching this as Context
        createDisplayContext(d0)
    }.getOrElse {
        this
    }

    private fun displayContext(displayId: Int): Context {
        if (displayId <= 0) return mainDisplayContext()
        return runCatching {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.getDisplay(displayId) ?: return@runCatching mainDisplayContext()
            createDisplayContext(d)
        }.getOrElse {
            mainDisplayContext()
        }
    }

    private fun windowManagerForDisplay(displayId: Int): WindowManager {
        return try {
            val ctx = displayContext(displayId)
            ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } catch (_: Exception) {
            windowManager
        }
    }

    private fun dp(ctx: Context, v: Int): Int {
        val d = try {
            ctx.resources.displayMetrics.density
        } catch (_: Exception) {
            resources.displayMetrics.density
        }
        return (v * d + 0.5f).toInt()
    }

    private fun statusBarHeightPx(): Int {
        return try {
            val r = mainDisplayContext().resources
            val resId = r.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) r.getDimensionPixelSize(resId) else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun showIndicator(x: Float, y: Float, displayId: Int) {
        mainHandler.post {
            try {
                val ctx = displayContext(displayId)
                val wm = windowManagerForDisplay(displayId)
                val radiusPx = dp(ctx, indicatorRadiusDp)
                val sizePx = radiusPx * 2

                val (adjX, adjY) = if (displayId <= 0) {
                    // 主屏模式：保持原有坐标补偿逻辑不变。
                    val sb = statusBarHeightPx()
                    val ax = (x - dp(ctx, 3)).coerceAtLeast(0f)
                    val ay = (y + dp(ctx, 38) - sb).coerceAtLeast(0f)
                    ax to ay
                } else {
                    // 虚拟隔离模式：不做任何偏移，确保圆心严格对齐注入坐标。
                    x.coerceAtLeast(0f) to y.coerceAtLeast(0f)
                }

                // 创建圆形指示器 View
                val indicatorView = CircleIndicatorView(ctx, indicatorColor, strokeColor, radiusPx.toFloat())
                indicatorView.alpha = 1f

                val params = WindowManager.LayoutParams().apply {
                    width = sizePx
                    height = sizePx
                    // 将中心点对准点击坐标
                    this.x = (adjX - radiusPx).toInt().coerceAtLeast(0)
                    this.y = (adjY - radiusPx).toInt().coerceAtLeast(0)
                    type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    format = PixelFormat.TRANSLUCENT
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                }

                wm.addView(indicatorView, params)

                // 启动渐变消失动画（1秒后开始，持续500ms消失）
                mainHandler.postDelayed({
                    fadeOutAndRemove(wm, indicatorView)
                }, 500L)

            } catch (e: Exception) {
                // 悬浮窗权限可能未授予，静默失败
            }
        }
    }

    private fun fadeOutAndRemove(wm: WindowManager, view: View) {
        try {
            view.animate().cancel()
        } catch (_: Exception) {
        }

        view.animate()
            .alpha(0f)
            .setDuration(500L)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        wm.removeViewImmediate(view)
                    } catch (_: Exception) {
                        try {
                            wm.removeView(view)
                        } catch (_: Exception) {
                        }
                    }
                }
            })
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * 自定义圆形指示器 View
     */
    private class CircleIndicatorView(
        context: Context,
        private val fillColor: Int,
        private val strokeColor: Int,
        private val radius: Float
    ) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = radius - 2f  // 留出边框空间
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, strokePaint)
        }
    }

    companion object {
        private const val EXTRA_X = "extra_x"
        private const val EXTRA_Y = "extra_y"
        private const val EXTRA_DISPLAY_ID = "extra_display_id"

        /**
         * 在指定坐标显示点击指示器
         * @param context 上下文
         * @param x 点击的 X 坐标（屏幕像素）
         * @param y 点击的 Y 坐标（屏幕像素）
         */
        fun showAt(context: Context, x: Float, y: Float) {
            val intent = Intent(context, TapIndicatorService::class.java).apply {
                putExtra(EXTRA_X, x)
                putExtra(EXTRA_Y, y)
            }
            context.startService(intent)
        }

        fun showAtOnDisplay(context: Context, x: Float, y: Float, displayId: Int) {
            val intent = Intent(context, TapIndicatorService::class.java).apply {
                putExtra(EXTRA_X, x)
                putExtra(EXTRA_Y, y)
                putExtra(EXTRA_DISPLAY_ID, displayId)
            }
            context.startService(intent)
        }
    }
}
