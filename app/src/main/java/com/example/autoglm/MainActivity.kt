package com.example.autoglm

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.rememberLauncherForActivityResult
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.autoglm.ui.NeonLiquidBackground
import com.example.autoglm.ui.theme.AutoglmTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.random.Random

private const val TAG = "AutoglmAdb"

private const val SHIZUKU_REQUEST_CODE = 10001

private const val PREFS_ENTRY = "entry_logic"
private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"

private const val EXTRA_BOOT_STAGE = "extra_boot_stage"
private const val BOOT_STAGE_LOADING = "loading"

private enum class BootStage {
    ONBOARDING,
    CALIBRATION,
    LOADING,
}

@Composable
private fun FullscreenVignette(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height

            val vignette = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF000000).copy(alpha = 0.45f),
                    Color(0xFF000000).copy(alpha = 0.70f),
                ),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.78f,
            )

            onDrawBehind {
                drawRect(Color(0xFF0A0E14).copy(alpha = 0.55f))
                drawRect(vignette)
            }
        }
    ) {
        content()
    }
}

private fun readIsFirstLaunch(context: Context): Boolean {
    return try {
        context.getSharedPreferences(PREFS_ENTRY, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_FIRST_LAUNCH, true)
    } catch (_: Exception) {
        true
    }
}

private fun writeIsFirstLaunch(context: Context, isFirst: Boolean) {
    try {
        context.getSharedPreferences(PREFS_ENTRY, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_FIRST_LAUNCH, isFirst)
            .apply()
    } catch (_: Exception) {
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "glass")
    val glow by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow"
    )
    Box(
        modifier = modifier
            .drawWithCache {
                val stroke = Stroke(width = 1.dp.toPx())
                val r = 18.dp.toPx()
                val borderBrush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF00FFCC).copy(alpha = 0.08f * glow),
                        Color(0xFF2EC7FF).copy(alpha = 0.12f * glow),
                        Color(0xFF8B5CFF).copy(alpha = 0.10f * glow),
                        Color(0xFF00FFCC).copy(alpha = 0.08f * glow),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
                onDrawBehind {
                    drawRoundRect(
                        color = Color(0xFF0A0E14).copy(alpha = 0.42f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                    )
                    drawRoundRect(
                        brush = borderBrush,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        style = stroke,
                        blendMode = BlendMode.Screen,
                    )
                }
            },
    ) {
        content()
    }
}

@Composable
private fun VisualStatusLine(
    label: String,
    ready: Boolean,
    readyText: String,
    pendingText: String,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "status_line")
    val breathe by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe"
    )
    val flicker by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flicker"
    )

    val statusText = if (ready) "[${readyText}] ✓" else "[${pendingText}]"
    val statusColor = if (ready) Color(0xFF00FFCC).copy(alpha = flicker) else Color(0xFF9AA3AE).copy(alpha = breathe)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = statusText,
            color = statusColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GradientGlowButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.5.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val phaseSeed = remember { Random.nextFloat() }
    val durationMs = remember { 4400 + Random.nextInt(0, 2200) }
    val infinite = rememberInfiniteTransition(label = "glow")
    val flowRaw by infinite.animateFloat(
        initialValue = phaseSeed,
        targetValue = phaseSeed + 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "flow"
    )
    val flow = flowRaw - phaseSeed

    val bg = if (enabled) Color(0xFF0A0E14).copy(alpha = 0.35f) else Color(0xFF0A0E14).copy(alpha = 0.18f)
    val fg = if (enabled) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.45f)

    Box(
        modifier = modifier
            .drawWithCache {
                val r = cornerRadius.toPx()
                val stroke = Stroke(width = borderWidth.toPx())
                val colors = listOf(
                    Color(0xFF00FFCC).copy(alpha = 0.08f),
                    Color(0xFF00FFCC).copy(alpha = 0.55f),
                    Color(0xFF2EC7FF).copy(alpha = 0.35f),
                    Color(0xFF8B5CFF).copy(alpha = 0.30f),
                    Color(0xFFFF3D8D).copy(alpha = 0.28f),
                    Color(0xFF00FFCC).copy(alpha = 0.08f),
                )
                onDrawBehind {
                    drawRoundRect(
                        color = bg,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                    )

                    val period = (size.width + size.height) * 0.9f
                    val shift = period * flow
                    val moving = Brush.linearGradient(
                        colors = colors,
                        start = androidx.compose.ui.geometry.Offset(-period + shift, -period + shift),
                        end = androidx.compose.ui.geometry.Offset(shift, shift),
                        tileMode = TileMode.Mirror,
                    )
                    drawRoundRect(
                        brush = moving,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                        style = stroke,
                        blendMode = BlendMode.Screen,
                    )
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 0.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.MaterialTheme(
            typography = androidx.compose.material3.MaterialTheme.typography
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides fg,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun BootSequenceRoot(
    modifier: Modifier = Modifier,
    initialStage: BootStage = BootStage.ONBOARDING,
) {
    var started by rememberSaveable { mutableStateOf(false) }
    var syncIndex by rememberSaveable { mutableStateOf(0) }
    var stage by rememberSaveable { mutableStateOf(initialStage) }

    val context = LocalContext.current

    LaunchedEffect(initialStage) {
        stage = initialStage
    }

    val features = remember {
        listOf(
            "社交通讯",
            "电商购物",
            "美食外卖",
            "出行旅游",
        )
    }

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1050L)
            syncIndex = (syncIndex + 1) % features.size
        }
    }

    val hueShift = if (!started) 0f else (syncIndex.toFloat() * 7f)

    Box(modifier = modifier) {
        NeonLiquidBackground(
            modifier = Modifier.fillMaxSize(),
            hueShiftDegrees = hueShift,
        )

        when (stage) {
            BootStage.ONBOARDING -> {
                WelcomeScreen(
                    modifier = Modifier.fillMaxSize(),
                    started = started,
                    syncingText = if (started) "正在同步：${features[syncIndex]}..." else "系统初始化中... 欢迎接入智能中枢。",
                    onClick = {
                        if (!started) {
                            started = true
                        } else {
                            writeIsFirstLaunch(context.applicationContext, false)
                            stage = BootStage.CALIBRATION
                        }
                    },
                )
            }

            BootStage.CALIBRATION -> {
                CalibrationScreen(
                    modifier = Modifier.fillMaxSize(),
                    onAdbConnected = {
                        stage = BootStage.LOADING
                    },
                )
            }

            BootStage.LOADING -> {
                val ctx = LocalContext.current
                ForceLoadingScreen(
                    modifier = Modifier.fillMaxSize(),
                    onFinished = {
                        try {
                            ctx.startActivity(
                                Intent(ctx, ChatActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                }
                            )
                        } catch (_: Exception) {
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    modifier: Modifier = Modifier,
    started: Boolean,
    syncingText: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "系统初始化中...",
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Crossfade(targetState = syncingText, label = "welcome_text") { t ->
                Text(
                    text = t,
                    color = Color.White.copy(alpha = if (started) 0.92f else 0.78f),
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = if (started) "点击进入校准..." else "点击开始同步...",
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun CalibrationScreen(
    modifier: Modifier = Modifier,
    onAdbConnected: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val config = remember { ConfigManager(context.applicationContext) }
    var adbMode by rememberSaveable {
        mutableStateOf(
            try {
                config.getAdbConnectMode()
            } catch (_: Exception) {
                ConfigManager.ADB_MODE_WIRELESS_DEBUG
            }
        )
    }

    var shizukuBinderReady by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var shizukuLastMessage by remember { mutableStateOf("等待 Shizuku") }
    var shizukuAutoInjected by remember { mutableStateOf(false) }

    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasBatteryWhitelist by remember { mutableStateOf(false) }

    var autoState by remember { mutableStateOf(AdbAutoConnectManager.State.INITIALIZING) }
    var autoMessage by remember { mutableStateOf("") }

    var apiAvailable by remember { mutableStateOf<Boolean?>(null) }
    var apiTesting by rememberSaveable { mutableStateOf(false) }
    var apiLastText by rememberSaveable { mutableStateOf("") }
    var apiLastSummary by rememberSaveable { mutableStateOf("") }
    var showApiRaw by rememberSaveable { mutableStateOf(false) }

    val apiOk = apiAvailable == true
    val connectMethodOk = if (adbMode == ConfigManager.ADB_MODE_SHIZUKU) {
        shizukuGranted
    } else {
        autoState == AdbAutoConnectManager.State.CONNECTED
    }
    val allReady = hasNotificationPermission && apiOk && connectMethodOk

    fun summarizeApiTest(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("成功")) return "API 连接成功"
        val lower = t.lowercase()

        if (lower.contains("base url") && (lower.contains("为空") || lower.contains("empty"))) {
            return "Base URL 未配置"
        }
        if (lower.contains("model name") && (lower.contains("为空") || lower.contains("empty"))) {
            return "模型名未配置"
        }
        if (
            lower.contains("401") ||
            lower.contains("unauthorized") ||
            lower.contains("invalid_api_key") ||
            lower.contains("invalid api key") ||
            (lower.contains("api key") && (lower.contains("invalid") || lower.contains("incorrect")))
        ) {
            return "鉴权失败：API Key 可能不正确"
        }
        if (lower.contains("403") || lower.contains("forbidden")) {
            return "鉴权失败：无权限访问（403）"
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("timeoutexception")) {
            return "网络超时：请检查网络/代理/服务可达性"
        }
        if (
            lower.contains("unknownhost") ||
            lower.contains("name or service not known") ||
            lower.contains("nodename nor servname") ||
            lower.contains("dns")
        ) {
            return "域名解析失败（DNS）：请检查网络或 Base URL"
        }
        if (
            lower.contains("connection") ||
            lower.contains("failed to establish") ||
            lower.contains("connectexception") ||
            lower.contains("refused")
        ) {
            return "连接失败：服务不可达或被拒绝"
        }

        return "API 测试失败：请检查 Base URL / 模型名 / API Key / 网络"
    }

    suspend fun runApiAvailabilityTest() {
        if (apiTesting) return
        apiTesting = true
        val resultText = try {
            withContext(Dispatchers.IO) {
                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(context.applicationContext))
                    }
                    val py = Python.getInstance()
                    PythonConfigBridge.injectModelServiceConfig(context.applicationContext, py)
                    val mod = py.getModule("phone_agent.model.client")
                    mod.callAttr("test_api_connection").toString()
                } catch (t: Throwable) {
                    "失败：${t::class.java.simpleName}: ${t.message}"
                }
            }
        } catch (t: Throwable) {
            "失败：${t::class.java.simpleName}: ${t.message}"
        }
        val raw = resultText.trim()
        val ok = raw.startsWith("成功")
        apiAvailable = ok
        apiLastText = raw
        apiLastSummary = summarizeApiTest(raw)
        apiTesting = false
    }

    fun refreshNotificationPermission() {
        hasNotificationPermission = if (Build.VERSION.SDK_INT < 33) {
            true
        } else {
            try {
                PermissionChecker.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PermissionChecker.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
    }

    fun refreshOverlayPermission() {
        hasOverlayPermission = try {
            Settings.canDrawOverlays(context.applicationContext)
        } catch (_: Exception) {
            false
        }
    }

    fun refreshBatteryWhitelist() {
        hasBatteryWhitelist = try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    val requestNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            refreshNotificationPermission()
        }
    )

    val autoConnectManager = remember {
        AdbAutoConnectManager(context.applicationContext)
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                autoConnectManager.stop()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshNotificationPermission()
                refreshOverlayPermission()
                refreshBatteryWhitelist()

                if (adbMode == ConfigManager.ADB_MODE_SHIZUKU) {
                    shizukuBinderReady = try {
                        Shizuku.pingBinder()
                    } catch (_: Throwable) {
                        false
                    }
                    shizukuGranted = try {
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        refreshNotificationPermission()
        refreshOverlayPermission()
        refreshBatteryWhitelist()
        try {
            runApiAvailabilityTest()
        } catch (_: Throwable) {
        }
    }

    LaunchedEffect(adbMode, shizukuGranted) {
        try {
            autoConnectManager.stop()
        } catch (_: Exception) {
        }

        fun startAutoConnect() {
            autoConnectManager.start { result ->
                autoState = result.state
                autoMessage = result.message
                if (result.state == AdbAutoConnectManager.State.CONNECTED) {
                    try {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            putExtra(EXTRA_BOOT_STAGE, BOOT_STAGE_LOADING)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                    onAdbConnected()
                }
            }
        }

        if (adbMode == ConfigManager.ADB_MODE_SHIZUKU) {
            // 按需求：Shizuku 模式不走无线调试的自动连接逻辑
            autoState = AdbAutoConnectManager.State.INITIALIZING
            autoMessage = if (shizukuGranted) {
                "Shizuku 模式：已授权"
            } else {
                "Shizuku 模式：等待授权"
            }
        } else {
            // 无线调试模式：保持原逻辑自动连接历史 ADB
            startAutoConnect()
        }
    }

    LaunchedEffect(allReady) {
        if (!allReady) return@LaunchedEffect
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(EXTRA_BOOT_STAGE, BOOT_STAGE_LOADING)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
        onAdbConnected()
    }

    Box(modifier = modifier) {
        FullscreenVignette(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.Center),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "机体权限校准",
                        color = Color.White.copy(alpha = 0.92f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "执行链路就绪检测与权限注入",
                        color = Color.White.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    VisualStatusLine(
                        label = "悬浮窗权限",
                        ready = hasOverlayPermission,
                        readyText = "ACTIVE",
                        pendingText = "PENDING",
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    VisualStatusLine(
                        label = "电池策略",
                        ready = hasBatteryWhitelist,
                        readyText = "STABLE",
                        pendingText = "RESTRICTED",
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "连接模式",
                        color = Color.White.copy(alpha = 0.72f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    GlassPanel(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            VisualStatusLine(
                                label = "当前模式",
                                ready = true,
                                readyText = if (adbMode == ConfigManager.ADB_MODE_SHIZUKU) "SHIZUKU" else "WIRELESS",
                                pendingText = "",
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            GradientGlowButton(
                                onClick = {
                                    adbMode = ConfigManager.ADB_MODE_WIRELESS_DEBUG
                                    try {
                                        config.setAdbConnectMode(adbMode)
                                    } catch (_: Exception) {
                                    }
                                    shizukuLastMessage = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = adbMode != ConfigManager.ADB_MODE_WIRELESS_DEBUG,
                            ) {
                                Text("无线调试模式（自动连接历史 ADB）")
                            }

                            if (adbMode == ConfigManager.ADB_MODE_WIRELESS_DEBUG) {
                                Spacer(modifier = Modifier.height(12.dp))
                                GradientGlowButton(
                                    onClick = {
                                        try {
                                            PairingService.startPairingNotification(context.applicationContext, "")
                                        } catch (_: Exception) {
                                        }
                                        startWirelessDebuggingSettings(context)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("申请无线调试配对")
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            GradientGlowButton(
                                onClick = {
                                    adbMode = ConfigManager.ADB_MODE_SHIZUKU
                                    try {
                                        config.setAdbConnectMode(adbMode)
                                    } catch (_: Exception) {
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = adbMode != ConfigManager.ADB_MODE_SHIZUKU,
                            ) {
                                Text("Shizuku 模式（授权后连接）")
                            }

                            if (adbMode == ConfigManager.ADB_MODE_SHIZUKU) {
                                Spacer(modifier = Modifier.height(14.dp))
                                VisualStatusLine(
                                    label = "Shizuku Binder",
                                    ready = shizukuBinderReady,
                                    readyText = "READY",
                                    pendingText = "OFFLINE",
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                VisualStatusLine(
                                    label = "Shizuku 授权",
                                    ready = shizukuGranted,
                                    readyText = "GRANTED",
                                    pendingText = "NEEDED",
                                )

                                if (!shizukuGranted) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    GradientGlowButton(
                                        onClick = {
                                            try {
                                                if (!Shizuku.pingBinder()) {
                                                    shizukuLastMessage = "未检测到 Shizuku 服务：请先安装/启用 Shizuku 并启动服务"
                                                    return@GradientGlowButton
                                                }
                                                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                                            } catch (t: Throwable) {
                                                shizukuLastMessage = "请求 Shizuku 授权失败：${t.message}"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = shizukuBinderReady,
                                    ) {
                                        Text(if (shizukuBinderReady) "申请 Shizuku 授权" else "等待 Shizuku 就绪")
                                    }
                                }

                                if (shizukuLastMessage.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = shizukuLastMessage,
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = if (hasNotificationPermission) "通知权限已就绪" else "需要通知权限：用于配对码/状态通知",
                        color = Color.White.copy(alpha = 0.55f),
                        fontFamily = FontFamily.Monospace,
                    )

                    GradientGlowButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !hasNotificationPermission
                    ) {
                        Text(if (hasNotificationPermission) "通知权限已就绪" else "申请通知权限")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    VisualStatusLine(
                        label = if (adbMode == ConfigManager.ADB_MODE_SHIZUKU) "Shizuku 授权" else "无线调试 ADB",
                        ready = connectMethodOk,
                        readyText = "READY",
                        pendingText = "WAITING",
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    VisualStatusLine(
                        label = "API 自检",
                        ready = apiOk,
                        readyText = "PASS",
                        pendingText = "UNKNOWN",
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    GradientGlowButton(
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(context, SettingsActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            } catch (_: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("API 设置")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    GradientGlowButton(
                        onClick = {
                            if (apiTesting) return@GradientGlowButton
                            scope.launch {
                                runApiAvailabilityTest()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !apiTesting,
                    ) {
                        Text(if (apiTesting) "测试中..." else "测试 API 连接")
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    val logMaxHeight = (configuration.screenHeightDp * 0.28f).dp
                    val logScroll = rememberScrollState()
                    val logText = buildString {
                        if (autoMessage.isNotBlank()) {
                            append("[ADB] ")
                            append(autoMessage)
                            append('\n')
                        }
                        if (apiAvailable == false) {
                            if (apiLastSummary.isNotBlank()) {
                                append("[API] ")
                                append(apiLastSummary)
                                append('\n')
                            }
                            if (showApiRaw && apiLastText.isNotBlank()) {
                                append("\n[详情]\n")
                                append(apiLastText)
                            }
                        }
                    }.trim()

                    if (logText.isNotBlank()) {
                        GlassPanel(modifier = Modifier.fillMaxWidth()) {
                            SelectionContainer {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 84.dp, max = logMaxHeight)
                                        .verticalScroll(logScroll)
                                        .padding(12.dp),
                                ) {
                                    if (apiAvailable == false && apiLastText.isNotBlank()) {
                                        GradientGlowButton(
                                            onClick = {
                                                showApiRaw = !showApiRaw
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(if (showApiRaw) "隐藏 API 详情" else "显示 API 详情")
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }
                                    Text(
                                        text = logText,
                                        color = Color.White.copy(alpha = 0.55f),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationRow(label: String, isDone: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White.copy(alpha = 0.7f))
        Text(
            text = if (isDone) "STABLE" else "WAITING...",
            color = if (isDone) Color(0xFF00FFCC) else Color.Gray,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ForceLoadingScreen(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
) {
    var progress by rememberSaveable { mutableStateOf(0) }
    var visible by rememberSaveable { mutableStateOf(true) }

    val scan = rememberInfiniteTransition(label = "scan")
    val scanY by scan.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanY"
    )

    LaunchedEffect(Unit) {
        val steps = listOf(12, 47, 78, 100)
        val stepDelay = 650L
        for (p in steps) {
            kotlinx.coroutines.delay(stepDelay)
            progress = p
        }
        val waited = stepDelay * steps.size
        val remain = (3_000L - waited).coerceAtLeast(0L)
        if (remain > 0) kotlinx.coroutines.delay(remain)
        visible = false
        kotlinx.coroutines.delay(260L)
        onFinished()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(140)) + scaleIn(initialScale = 1.01f, animationSpec = tween(140)),
        exit = fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.98f, animationSpec = tween(220)),
    ) {
        Box(modifier = modifier) {
            val w = 1f
            val y = scanY
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val width = size.width
                        val height = size.height
                        val lineY = height * y
                        val lineBrush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF00FFCC).copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                            start = Offset(0f, lineY - 60f),
                            end = Offset(0f, lineY + 60f),
                        )
                        onDrawBehind {
                            drawRect(Color.Black.copy(alpha = 0.35f))
                            drawRect(lineBrush)
                        }
                    }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "权限注入中... $progress%",
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "强制抢占中枢控制权",
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var bootStageExtraState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bootStageExtraState.value = intent?.getStringExtra(EXTRA_BOOT_STAGE)

        try {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false
        } catch (_: Exception) {
        }

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val py = Python.getInstance()
        try {
            PythonConfigBridge.injectModelServiceConfig(this, py)
        } catch (t: Throwable) {
            Log.e(TAG, "注入模型服务配置到 Python 环境变量失败（已忽略）", t)
        }
        try {
            py.getModule("autoglm.main")
        } catch (t: Throwable) {
            Log.e(TAG, "Python 模块 autoglm.main 加载失败（已忽略）", t)
        }

        try {
            ensureLocalAdbReady()
        } catch (e: Exception) {
            Log.e(TAG, "启动时初始化本地 ADB 失败（已忽略，应用将继续启动）", e)
        }

        setContent {
            AutoglmTheme {
                val initial = if (bootStageExtraState.value == BOOT_STAGE_LOADING) {
                    BootStage.LOADING
                } else {
                    if (readIsFirstLaunch(applicationContext)) BootStage.ONBOARDING else BootStage.CALIBRATION
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0E14))
                ) {
                    BootSequenceRoot(modifier = Modifier.fillMaxSize(), initialStage = initial)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        try {
            setIntent(intent)
        } catch (_: Exception) {
        }
        bootStageExtraState.value = intent.getStringExtra(EXTRA_BOOT_STAGE)
    }

    private fun ensureLocalAdbReady() {
        try {
            val binDir = File(filesDir, "bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
            }

            try {
                val cache = cacheDir
                val logs = cache.listFiles { _, name -> name.startsWith("adb") && name.endsWith(".log") } ?: emptyArray()
                for (f in logs) {
                    try {
                        f.delete()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }

            val libDir = File(filesDir, "lib")
            if (!libDir.exists()) {
                libDir.mkdirs()
            }

            val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()
            Log.i(TAG, "设备 ABI=$abi")

            val adbFile = File(binDir, "adb")
            try {
                Log.i(TAG, "正在从 assets 强制覆盖更新 adb -> ${adbFile.absolutePath}")
                assets.open("adb").use { input ->
                    adbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val head = adbFile.safeHeadText(4096)
                if (head.contains("--library-path") || head.contains("-L")) {
                    Log.e(TAG, "严重：释放后的 adb 脚本仍包含 -L/--library-path，可能导致 linker expected absolute path: \"-L\"。head=\n$head")
                }
            } catch (e: Exception) {
                Log.e(TAG, "从 assets 覆盖释放 adb 失败（已忽略）", e)
            }

            if (!adbFile.isLikelyElfBinary()) {
                val head = adbFile.safeHeadText()
                Log.w(
                    TAG,
                    "当前 assets/adb 不是 ELF（二进制），看起来是脚本包装器。" +
                        "\n路径=${adbFile.absolutePath}, size=${adbFile.length()}" +
                        "\n文件头预览：\n$head"
                )
            }

            // 释放真正的 adb 二进制（目前你提供了 arm64 版本）
            if (abi.contains("arm64")) {
                ensureAdbRuntimeLibsReady(abi, libDir)

                val adbBin = File(binDir, "adb.bin")
                val assetName = "adb.bin-arm64"
                val assetIsPie = assets.isLikelyPieAsset(assetName)
                val localIsPie = adbBin.exists() && adbBin.isLikelyElfBinary() && adbBin.isLikelyPieExecutable()

                val assetLength: Long? = try {
                    assets.openFd(assetName).length
                } catch (_: Exception) {
                    null
                }

                val shouldOverwrite = !adbBin.exists() || adbBin.length() == 0L || !localIsPie || (assetLength != null && adbBin.length() != assetLength)
                if (shouldOverwrite) {
                    Log.w(
                        TAG,
                        "将覆盖更新本地 adb.bin：localSize=${adbBin.length()} localIsPie=$localIsPie assetSize=${assetLength ?: -1} assetIsPie=$assetIsPie"
                    )
                    Log.i(TAG, "正在从 assets 释放 $assetName -> ${adbBin.absolutePath}")
                    assets.open(assetName).use { input ->
                        adbBin.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    Log.i(TAG, "检测到已存在 adb.bin：${adbBin.absolutePath} (size=${adbBin.length()}, pie=$localIsPie)")
                }

                if (adbBin.isLikelyElfBinary()) {
                    val isPie = adbBin.isLikelyPieExecutable()
                    if (!isPie) {
                        Log.e(
                            TAG,
                            "adb.bin 不是 PIE 可执行文件（ET_EXEC/非 PIE），Android linker 可能拒绝启动并报：unexpected e_type: 2。" +
                                "\n请替换为 PIE 版本的 adb（二进制需以 -fPIE -pie 构建或来自 Termux/android-tools）。"
                        )
                    }
                    val ok = adbBin.setExecutable(true, true)
                    Log.i(TAG, "adb.bin setExecutable 结果：$ok")
                    if (!ok) {
                        try {
                            Log.i(TAG, "尝试通过 chmod 设置 adb.bin 可执行权限")
                            ProcessBuilder("chmod", "700", adbBin.absolutePath)
                                .redirectErrorStream(true)
                                .start()
                                .waitFor()
                        } catch (_: Exception) {
                            // 忽略：在部分机型上可能无法通过该方式设置可执行权限
                        }
                    }
                } else {
                    val head = adbBin.safeHeadText()
                    Log.e(TAG, "adb.bin-arm64 释放后不是 ELF，无法使用。head=\n$head")
                }
            } else {
                Log.w(TAG, "当前设备 ABI 非 arm64，暂未提供对应 adb.bin 资源，无法执行本地 adb。")
            }

            // 对脚本包装器也尝试设置可执行（不保证所有 ROM 允许直接 exec 脚本）
            val executableOk = adbFile.setExecutable(true, true)
            Log.i(TAG, "adb(脚本) setExecutable 结果：$executableOk")
        } catch (e: Exception) {
            Log.e(TAG, "ensureLocalAdbReady 执行异常（已忽略，应用将继续启动）", e)
        }
    }
}

@Composable
fun WirelessDebugPairingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    var statusText by rememberSaveable { mutableStateOf("") }
    var isRunning by rememberSaveable { mutableStateOf(false) }

    var autoState by remember { mutableStateOf(AdbAutoConnectManager.State.INITIALIZING) }
    var autoMessage by remember { mutableStateOf("") }

    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasBatteryWhitelist by remember { mutableStateOf(false) }

    var apiAvailable by remember { mutableStateOf<Boolean?>(null) }
    var apiTesting by rememberSaveable { mutableStateOf(false) }

    suspend fun runApiAvailabilityTest(appendToStatusText: Boolean) {
        if (apiTesting) return
        apiTesting = true

        val resultText = try {
            withContext(Dispatchers.IO) {
                try {
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(context))
                    }
                    val py = Python.getInstance()
                    PythonConfigBridge.injectModelServiceConfig(context, py)
                    val mod = py.getModule("phone_agent.model.client")
                    mod.callAttr("test_api_connection").toString()
                } catch (t: Throwable) {
                    "失败：${t::class.java.simpleName}: ${t.message}"
                }
            }
        } catch (t: Throwable) {
            "失败：${t::class.java.simpleName}: ${t.message}"
        }

        val ok = resultText.trim().startsWith("成功")
        apiAvailable = ok
        apiTesting = false

        if (!ok) {
            Log.w(TAG, "API 可用性测试失败：$resultText")
        }
        if (appendToStatusText) {
            statusText += "[API 测试结果]\n" + resultText + "\n"
        }
    }

    fun refreshPermissionStates() {
        hasNotificationPermission = if (Build.VERSION.SDK_INT < 33) {
            true
        } else {
            try {
                PermissionChecker.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PermissionChecker.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }

        hasOverlayPermission = try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }

        hasBatteryWhitelist = try {
            val pm = context.getSystemService(PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    val lastEndpoint = remember {
        try {
            ConfigManager(context).getLastAdbEndpoint().trim()
        } catch (_: Exception) {
            ""
        }
    }

    val autoConnectManager = remember {
        AdbAutoConnectManager(context.applicationContext)
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                autoConnectManager.stop()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val requestNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {
            refreshPermissionStates()
        }
    )

    LaunchedEffect(Unit) {
        refreshPermissionStates()

        try {
            runApiAvailabilityTest(appendToStatusText = false)
        } catch (t: Throwable) {
            Log.w(TAG, "启动时自动 API 可用性测试异常（已忽略）：${t.message}", t)
        }

        autoConnectManager.start { result ->
            autoState = result.state
            autoMessage = result.message
            isRunning = result.state == AdbAutoConnectManager.State.INITIALIZING ||
                result.state == AdbAutoConnectManager.State.CONNECTING ||
                result.state == AdbAutoConnectManager.State.SCANNING

            if (result.state == AdbAutoConnectManager.State.CONNECTED) {
                refreshPermissionStates()
                val ready = hasNotificationPermission && hasOverlayPermission && hasBatteryWhitelist
                if (ready) {
                    val act = context as? MainActivity
                    if (act != null) {
                        act.startActivity(
                            Intent(act, ChatActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                        act.finish()
                    } else {
                        context.startActivity(
                            Intent(context, ChatActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                    }
                }
            }
        }
    }

    var showManualScreen by rememberSaveable { mutableStateOf(false) }

    // NSD(mDNS) 扫描器实例：用于自动发现局域网内的 _adb-tls-connect._tcp 服务
    // 说明：无线调试“连接端口”是随机的，系统会通过 mDNS 广播服务；我们监听该服务并解析出 host/port。
    var portScanner by remember { mutableStateOf<AdbPortScanner?>(null) }
    var isScanning by rememberSaveable { mutableStateOf(false) }

    if (showManualScreen) {
        ManualWirelessDebugScreen(
            modifier = modifier,
            statusText = statusText,
            onStatusChange = { statusText = it },
            isRunning = isRunning,
            onRunningChange = { isRunning = it },
            portScanner = portScanner,
            onPortScannerChange = { portScanner = it },
            isScanning = isScanning,
            onScanningChange = { isScanning = it },
            onBack = { showManualScreen = false },
        )
        return
    }

    Column(modifier = modifier.padding(16.dp).verticalScroll(scrollState)) {
        Text(text = "无线调试")

        if (autoMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = autoMessage)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        requestNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        refreshPermissionStates()
                    }
                } catch (_: Exception) {
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning
        ) {
            Text("通知权限")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            PermissionStatusLine(title = "通知权限", ok = hasNotificationPermission)
            Spacer(modifier = Modifier.height(6.dp))
            PermissionStatusLine(title = "悬浮权限", ok = hasOverlayPermission)
            Spacer(modifier = Modifier.height(6.dp))
            PermissionStatusLine(title = "省电策略", ok = hasBatteryWhitelist)
            Spacer(modifier = Modifier.height(6.dp))
            PermissionStatusLine(title = "API 测试", ok = apiAvailable == true)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                try {
                    Log.i(TAG, "点击通知栏配对按钮")
                    PairingService.startPairingNotification(context, pairPort = "")
                    Log.i(TAG, "已调用 startPairingNotification")
                    statusText = "已启动通知栏配对：请下拉通知栏输入配对码并提交。\n"

                    startWirelessDebuggingSettings(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "启动通知栏配对失败：${t.message}", t)
                    statusText = "启动通知栏配对失败：${t.message}\n"
                    try {
                        Toast.makeText(context, "启动失败：${t.message}", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning && autoState == AdbAutoConnectManager.State.FAILED
        ) {
            Text("启动通知栏配对（输入配对码）")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val intent = Intent(context, SettingsActivity::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning
        ) {
            Text("API 设置")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (apiTesting) return@Button
                val activity = context as? MainActivity
                activity?.lifecycleScope?.launch {
                    statusText += "开始测试 API 连接...\n"
                    runApiAvailabilityTest(appendToStatusText = true)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !apiTesting
        ) {
            Text(if (apiTesting) "测试中..." else "测试 API 连接")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "使用指引：\n" +
                "1. 打开 系统设置 -> 开发者选项 -> 开启【无线调试】。\n" +
                "2. 在【无线调试】里点【使用配对码配对设备】获取“配对码”。\n" +
                "3. 点击下方按钮启动通知栏配对（类似 Shizuku 体验）：在通知栏输入配对码即可自动配对并连接。\n" +
                "4. 如果自动流程失败，可进入备用方案2进行手动端口输入。"
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                showManualScreen = true
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning && autoState == AdbAutoConnectManager.State.FAILED
        ) {
            Text("备用方案2：手动输入端口")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (statusText.isNotEmpty()) {
            SelectionContainer {
                Text(
                    text = statusText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(scrollState)
                )
            }
        }
    }
}

private fun File.safeHeadText(maxBytes: Int = 256): String {
    return try {
        if (!exists()) return "<file_not_found>"
        FileInputStream(this).use { fis ->
            val buf = ByteArray(maxBytes)
            val read = fis.read(buf)
            if (read <= 0) return "<empty>"
            String(buf, 0, read, Charsets.UTF_8).replace("\u0000", "")
        }
    } catch (e: Exception) {
        "<read_error:${e.message}>"
    }
}

private fun File.isLikelyElfBinary(): Boolean {
    return try {
        if (!exists() || length() < 4) return false
        FileInputStream(this).use { fis ->
            val magic = ByteArray(4)
            val read = fis.read(magic)
            read == 4 && magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }
}

private fun File.isLikelyPieExecutable(): Boolean {
    return try {
        if (!isLikelyElfBinary() || length() < 18) return false
        FileInputStream(this).use { fis ->
            val header = ByteArray(18)
            val read = fis.read(header)
            if (read < 18) return false
            val eType = (header[16].toInt() and 0xFF) or ((header[17].toInt() and 0xFF) shl 8)
            eType == 3
        }
    } catch (_: Exception) {
        false
    }
}

private fun AssetManager.isLikelyPieAsset(assetName: String): Boolean {
    return try {
        open(assetName).use { input ->
            val header = ByteArray(18)
            val read = input.read(header)
            if (read < 18) return false
            if (!(header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte())) {
                return false
            }
            val eType = (header[16].toInt() and 0xFF) or ((header[17].toInt() and 0xFF) shl 8)
            eType == 3
        }
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun PermissionStatusLine(title: String, ok: Boolean) {
    val symbol = if (ok) "✓" else "✘"
    val color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)
    Text(text = "$title $symbol", color = color)
}

private fun MainActivity.ensureAdbRuntimeLibsReady(abi: String, outLibDir: File) {
    if (!abi.contains("arm64")) return

    val assetPrefix = "adb-libs/arm64-v8a/"
    val entries = try {
        assets.list(assetPrefix.trimEnd('/'))?.toList().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    if (entries.isEmpty()) {
        return
    }

    for (name in entries) {
        val assetName = assetPrefix + name
        val outFile = File(outLibDir, name)

        val assetLen: Long? = try {
            assets.openFd(assetName).length
        } catch (_: Exception) {
            null
        }

        val shouldCopy = !outFile.exists() || outFile.length() == 0L || (assetLen != null && outFile.length() != assetLen)
        if (!shouldCopy) {
            continue
        }

        try {
            assets.open(assetName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun ManualWirelessDebugScreen(
    modifier: Modifier,
    statusText: String,
    onStatusChange: (String) -> Unit,
    isRunning: Boolean,
    onRunningChange: (Boolean) -> Unit,
    portScanner: AdbPortScanner?,
    onPortScannerChange: (AdbPortScanner?) -> Unit,
    isScanning: Boolean,
    onScanningChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val scope = rememberCoroutineScope()

    var pairingEndpoint by rememberSaveable { mutableStateOf("") }
    var pairingCode by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.padding(16.dp).verticalScroll(scrollState)) {
        Text(text = "备用方案2：手动输入端口")
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onBack() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning
        ) {
            Text("返回")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("一键跳转开发者设置")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "手动配对")
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = pairingEndpoint,
            onValueChange = { pairingEndpoint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对端点(IP:Port)") },
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对码") },
            visualTransformation = PasswordVisualTransformation()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (isRunning) return@Button

                val ep = pairingEndpoint.trim()
                val code = pairingCode.trim()
                if (ep.isEmpty() || code.isEmpty()) {
                    onStatusChange("请输入配对端点(IP:Port)和配对码。")
                    return@Button
                }

                onRunningChange(true)
                onStatusChange("开始配对...\n")

                scope.launch {
                    val ctx = context.applicationContext
                    val adbExecPath = LocalAdb.resolveAdbExecPath(ctx)
                    val console = StringBuilder()
                    fun appendLine(line: String) {
                        synchronized(console) {
                            console.append(line).append('\n')
                            if (console.length > 6000) {
                                console.delete(0, console.length - 4000)
                            }
                        }
                        val text = console.toString().trim()
                        scope.launch(Dispatchers.Main) {
                            onStatusChange(statusText + text + "\n")
                        }
                    }

                    val result = withContext(Dispatchers.IO) {
                        appendLine(">>> adb pair $ep ********")
                        LocalAdb.runCommand(
                            ctx,
                            LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("pair", ep, code)),
                            timeoutMs = 10_000L
                        )
                    }

                    withContext(Dispatchers.Main) {
                        onRunningChange(false)
                        onStatusChange(statusText + "[adb pair 输出]\n${result.output}\n")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning
        ) {
            Text(if (isRunning) "配对中..." else "开始配对")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "手动连接")
        Spacer(modifier = Modifier.height(12.dp))

        var connectEndpoint by rememberSaveable { mutableStateOf("") }

        DisposableEffect(Unit) {
            onDispose {
                try {
                    portScanner?.stop()
                } catch (_: Exception) {
                }
                onPortScannerChange(null)
                onScanningChange(false)
            }
        }

        fun connectNow(endpoint: String) {
            if (isRunning) return
            val ep = endpoint.trim()
            if (ep.isEmpty()) {
                onStatusChange("请输入连接端点(IP:Port)。")
                return
            }

            onRunningChange(true)
            onStatusChange("正在连接 $ep ...\n")

            scope.launch {
                val ctx = context.applicationContext
                val adbExecPath = LocalAdb.resolveAdbExecPath(ctx)
                val console = StringBuilder()
                fun appendLine(line: String) {
                    synchronized(console) {
                        console.append(line).append('\n')
                        if (console.length > 6000) {
                            console.delete(0, console.length - 4000)
                        }
                    }
                    val text = console.toString().trim()
                    scope.launch(Dispatchers.Main) {
                        onStatusChange(statusText + text + "\n")
                    }
                }

                val connectResult = withContext(Dispatchers.IO) {
                    appendLine(">>> adb connect $ep")
                    LocalAdb.runCommand(
                        ctx,
                        LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("connect", ep)),
                        timeoutMs = 10_000L
                    )
                }

                val devicesResult = withContext(Dispatchers.IO) {
                    appendLine(">>> adb devices")
                    LocalAdb.runCommand(
                        ctx,
                        LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("devices")),
                        timeoutMs = 10_000L
                    )
                }

                val ok = devicesResult.output.lines().any { it.contains(ep) && it.contains("device") }
                if (ok) {
                    try {
                        ConfigManager(ctx).setLastAdbEndpoint(ep)
                    } catch (_: Exception) {
                    }
                }

                withContext(Dispatchers.Main) {
                    onRunningChange(false)
                    val extra = if (ok) "\n连接确认：成功（已保存记录）。\n" else "\n连接确认：未确认，请检查 adb devices 输出。\n"
                    onStatusChange(statusText + "[adb connect 输出]\n${connectResult.output}\n" + "[adb devices]\n${devicesResult.output}\n" + extra)
                }
            }
        }

        Button(
            onClick = {
                if (isRunning) return@Button
                if (isScanning) {
                    onStatusChange(statusText + "已在扫描中...\n")
                    return@Button
                }

                onScanningChange(true)
                onStatusChange(statusText + "开始自动扫描 _adb-tls-connect._tcp ...\n")

                val activity = context as? MainActivity
                if (activity == null) {
                    onScanningChange(false)
                    onStatusChange(statusText + "当前不是 Activity 上下文，无法扫描。\n")
                    return@Button
                }

                val scanner = AdbPortScanner(
                    context = activity,
                    onEndpoint = { ep ->
                        activity.runOnUiThread {
                            connectEndpoint = "${ep.hostAddress}:${ep.port}"
                            onStatusChange(statusText + "扫描到端点：${ep.hostAddress}:${ep.port}，准备自动连接...\n")
                        }
                        try {
                            portScanner?.stop()
                        } catch (_: Exception) {
                        }
                        onPortScannerChange(null)
                        onScanningChange(false)
                        connectNow("${ep.hostAddress}:${ep.port}")
                    },
                    onError = { msg, _ ->
                        activity.runOnUiThread {
                            onStatusChange(statusText + "自动扫描失败：$msg\n")
                            onScanningChange(false)
                        }
                    }
                )

                onPortScannerChange(scanner)
                scanner.start()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning
        ) {
            Text(if (isScanning) "自动扫描中..." else "自动扫描端口并连接")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = connectEndpoint,
            onValueChange = { connectEndpoint = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("连接端点(IP:Port)") },
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { connectNow(connectEndpoint) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning
        ) {
            Text(if (isRunning) "连接中..." else "连接设备")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (statusText.isNotEmpty()) {
            SelectionContainer {
                Text(
                    text = statusText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(scrollState)
                )
            }
        }
    }
}
