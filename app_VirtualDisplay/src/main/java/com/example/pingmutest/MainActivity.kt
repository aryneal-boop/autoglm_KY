package com.example.pingmutest

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pingmutest.display.DisplayInterceptor
import com.example.pingmutest.display.ShizukuVirtualDisplayCreator
import com.example.pingmutest.display.TouchForwarder
import com.example.pingmutest.display.VirtualDisplaySurfaceStreamer
import com.example.pingmutest.shizuku.ShizukuShell
import com.example.pingmutest.system.ActivityLaunchUtils
import com.example.pingmutest.ui.theme.PingmutestTheme
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot

private const val SHIZUKU_REQUEST_CODE = 10001

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensureShizukuVisibleAndPermissionRequested()
        setContent {
            PingmutestTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun ensureShizukuVisibleAndPermissionRequested() {
        val binderListener = Shizuku.OnBinderReceivedListener {
            tryRequestShizukuPermission("OnBinderReceived")
        }
        val binderDeadListener = Shizuku.OnBinderDeadListener {
            Log.w("MainActivity", "Shizuku binder dead")
        }
        val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.i(
                "MainActivity",
                "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult",
            )
        }

        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    private fun tryRequestShizukuPermission(source: String) {
        if (!Shizuku.pingBinder()) {
            Log.d("MainActivity", "Shizuku binder not ready ($source)")
            return
        }

        val granted = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Log.w("MainActivity", "checkSelfPermission failed ($source)", t)
            false
        }

        if (!granted) {
            Log.i("MainActivity", "Requesting Shizuku permission ($source)")
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } else {
            Log.d("MainActivity", "Shizuku permission already granted ($source)")
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun Greeting(name: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var displayId by remember { mutableStateOf<Int?>(null) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    var previewSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var previewSurfaceReady by remember { mutableStateOf(false) }
    var previewViewWidth by remember { mutableStateOf(0) }
    var previewViewHeight by remember { mutableStateOf(0) }

    var previewVisible by remember { mutableStateOf(false) }

    var virtualDisplayStartRequested by rememberSaveable { mutableStateOf(false) }

    val virtualWidth = 1080
    val virtualHeight = 1920

    data class LaunchableApp(
        val packageName: String,
        val label: String,
    )

    val apps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                val ai = ri.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName ?: return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
                LaunchableApp(packageName = pkg, label = label.ifBlank { pkg })
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    var appMenuExpanded by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<LaunchableApp?>(apps.firstOrNull()) }

    val interceptor = remember {
        DisplayInterceptor(
            context = context,
            callback = object : DisplayInterceptor.Callback {
                override fun onVirtualDisplayAdded(newDisplayId: Int) {
                    displayId = newDisplayId
                    lastMessage = "Captured virtual displayId=$newDisplayId"
                }
            },
        )
    }

    DisposableEffect(Unit) {
        interceptor.start()
        onDispose {
            interceptor.stop()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                runCatching { VirtualDisplaySurfaceStreamer.stop() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            previewSurfaceView = null
            previewSurfaceReady = false
        }
    }

    LaunchedEffect(Unit) {
        if (!virtualDisplayStartRequested) {
            virtualDisplayStartRequested = true
            lastMessage = "VirtualDisplay start requested"
        }
    }

    LaunchedEffect(Unit) {
        delay(500)
        previewVisible = true
    }

    LaunchedEffect(previewSurfaceReady, virtualDisplayStartRequested) {
        if (!virtualDisplayStartRequested) return@LaunchedEffect
        if (!previewSurfaceReady) return@LaunchedEffect

        while (virtualDisplayStartRequested && previewSurfaceReady && !VirtualDisplaySurfaceStreamer.isStarted()) {
            val sv = previewSurfaceView
            if (sv == null) {
                delay(300)
                continue
            }

            val args = ShizukuVirtualDisplayCreator.Args(
                name = "Pingmu-Virtual",
                width = virtualWidth,
                height = virtualHeight,
                dpi = 440,
                refreshRate = 0f,
                rotatesWithContent = false,
            )
            val r = VirtualDisplaySurfaceStreamer.start(args, sv.holder.surface)
            if (r.isSuccess) {
                displayId = r.getOrNull()
                lastMessage = "Created Shizuku VirtualDisplay (Surface) displayId=${displayId}"
                break
            } else {
                lastMessage = r.exceptionOrNull()?.message
                delay(1200)
            }
        }

        if (VirtualDisplaySurfaceStreamer.isStarted()) {
            displayId = VirtualDisplaySurfaceStreamer.getDisplayId()
        }
    }

    LaunchedEffect(displayId) {
        val id = displayId ?: return@LaunchedEffect
        while (displayId == id) {
            TouchForwarder.ensureFocusedDisplay(id)
            delay(250)
        }
    }

    LaunchedEffect(displayId) {
        Log.i("MainActivity", "displayId changed: ${displayId ?: "null"} started=${VirtualDisplaySurfaceStreamer.isStarted()}")
    }

    val buttonScrollState = rememberScrollState()

    val previewWeight = 0.7f
    val controlsWeight = 0.3f

    Box(modifier = modifier.fillMaxSize().systemBarsPadding()) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(previewWeight, fill = true)
                    .border(width = 1.dp, color = Color.Gray),
                contentAlignment = Alignment.Center,
            ) {
                if (previewVisible) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(virtualWidth.toFloat() / virtualHeight.toFloat()),
                        factory = { ctx ->
                            val surfaceView = SurfaceView(ctx)
                            val overlay = View(ctx).apply {
                                isFocusable = false
                                isFocusableInTouchMode = false
                                isClickable = true
                                isLongClickable = false
                            }

                            FrameLayout(ctx).apply {
                                fun updateFitCenterLayout(containerW: Int, containerH: Int) {
                                    if (containerW <= 0 || containerH <= 0) return
                                    val scale = minOf(
                                        containerW.toFloat() / virtualWidth.toFloat(),
                                        containerH.toFloat() / virtualHeight.toFloat(),
                                    )
                                    val contentW = (virtualWidth.toFloat() * scale).toInt().coerceAtLeast(1)
                                    val contentH = (virtualHeight.toFloat() * scale).toInt().coerceAtLeast(1)

                                    Log.i(
                                        "MainActivity",
                                        "updateFitCenterLayout: container=${containerW}x${containerH} content=${contentW}x${contentH}",
                                    )

                                    val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams
                                        ?: FrameLayout.LayoutParams(contentW, contentH)
                                    lp.width = contentW
                                    lp.height = contentH
                                    lp.gravity = Gravity.CENTER
                                    surfaceView.layoutParams = lp

                                    previewViewWidth = contentW
                                    previewViewHeight = contentH
                                }

                                addView(
                                    surfaceView,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                addView(
                                    overlay,
                                    FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                    ),
                                )

                                previewSurfaceView = surfaceView
                                tag = surfaceView

                                addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                    updateFitCenterLayout(right - left, bottom - top)
                                }

                                post {
                                    updateFitCenterLayout(width, height)
                                }

                                surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        runCatching {
                                            holder.setFixedSize(virtualWidth, virtualHeight)
                                        }
                                        previewSurfaceReady = false
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        updateFitCenterLayout(this@apply.width, this@apply.height)

                                        runCatching {
                                            holder.setFixedSize(virtualWidth, virtualHeight)
                                        }
                                        previewSurfaceReady = true
                                        if (VirtualDisplaySurfaceStreamer.isStarted()) {
                                            val up = VirtualDisplaySurfaceStreamer.updateSurface(holder.surface)
                                            if (up.isFailure) {
                                                val dr = VirtualDisplaySurfaceStreamer.destroy()
                                                if (dr.isSuccess) {
                                                    displayId = null
                                                    virtualDisplayStartRequested = true
                                                } else {
                                                    virtualDisplayStartRequested = false
                                                    lastMessage = dr.exceptionOrNull()?.message
                                                }
                                            }
                                        }
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        previewSurfaceReady = false
                                    }
                                })

                                var downX = 0f
                                var downY = 0f
                                var downTime = 0L

                                var swipeLastX = 0f
                                var swipeLastY = 0f

                                var pinchActive = false
                                var pinchStart1X = 0f
                                var pinchStart1Y = 0f
                                var pinchStart2X = 0f
                                var pinchStart2Y = 0f
                                var pinchLast1X = 0f
                                var pinchLast1Y = 0f
                                var pinchLast2X = 0f
                                var pinchLast2Y = 0f
                                var pinchStartTime = 0L

                                val swipeThresholdPx = 18f

                                overlay.setOnTouchListener { _, event ->
                                    val id = displayId
                                    val vw = previewViewWidth
                                    val vh = previewViewHeight
                                    if (id == null) return@setOnTouchListener true
                                    if (vw <= 0 || vh <= 0) return@setOnTouchListener true

                                    try {
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> {
                                                pinchActive = false
                                                downX = event.x
                                                downY = event.y
                                                downTime = event.eventTime
                                                swipeLastX = downX
                                                swipeLastY = downY
                                            }

                                            MotionEvent.ACTION_POINTER_DOWN -> {
                                                if (event.pointerCount >= 2) {
                                                    pinchActive = true
                                                    pinchStartTime = event.eventTime
                                                    pinchStart1X = event.getX(0)
                                                    pinchStart1Y = event.getY(0)
                                                    pinchStart2X = event.getX(1)
                                                    pinchStart2Y = event.getY(1)
                                                    pinchLast1X = pinchStart1X
                                                    pinchLast1Y = pinchStart1Y
                                                    pinchLast2X = pinchStart2X
                                                    pinchLast2Y = pinchStart2Y
                                                }
                                            }

                                            MotionEvent.ACTION_MOVE -> {
                                                if (pinchActive && event.pointerCount >= 2) {
                                                    pinchLast1X = event.getX(0)
                                                    pinchLast1Y = event.getY(0)
                                                    pinchLast2X = event.getX(1)
                                                    pinchLast2Y = event.getY(1)
                                                } else {
                                                    swipeLastX = event.x
                                                    swipeLastY = event.y
                                                }
                                            }

                                            MotionEvent.ACTION_POINTER_UP -> {
                                                if (pinchActive) {
                                                    val duration = (event.eventTime - pinchStartTime).coerceAtLeast(1)
                                                    val r = TouchForwarder.forwardPinchOnView(
                                                        context = context,
                                                        viewWidth = vw,
                                                        viewHeight = vh,
                                                        displayWidth = virtualWidth,
                                                        displayHeight = virtualHeight,
                                                        displayId = id,
                                                        startP1X = pinchStart1X,
                                                        startP1Y = pinchStart1Y,
                                                        startP2X = pinchStart2X,
                                                        startP2Y = pinchStart2Y,
                                                        endP1X = pinchLast1X,
                                                        endP1Y = pinchLast1Y,
                                                        endP2X = pinchLast2X,
                                                        endP2Y = pinchLast2Y,
                                                        durationMs = duration,
                                                    )
                                                    if (r.isFailure) lastMessage = r.exceptionOrNull()?.message
                                                    pinchActive = false
                                                }
                                            }

                                            MotionEvent.ACTION_UP -> {
                                                if (!pinchActive) {
                                                    val dx = swipeLastX - downX
                                                    val dy = swipeLastY - downY
                                                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                                    val duration = (event.eventTime - downTime).coerceAtLeast(1)

                                                    val r = if (dist >= swipeThresholdPx) {
                                                        TouchForwarder.forwardSwipeOnView(
                                                            context = context,
                                                            viewWidth = vw,
                                                            viewHeight = vh,
                                                            displayWidth = virtualWidth,
                                                            displayHeight = virtualHeight,
                                                            displayId = id,
                                                            startX = downX,
                                                            startY = downY,
                                                            endX = swipeLastX,
                                                            endY = swipeLastY,
                                                            durationMs = duration,
                                                        )
                                                    } else {
                                                        TouchForwarder.forwardTapOnView(
                                                            context = context,
                                                            viewWidth = vw,
                                                            viewHeight = vh,
                                                            displayWidth = virtualWidth,
                                                            displayHeight = virtualHeight,
                                                            displayId = id,
                                                            event = event,
                                                        )
                                                    }
                                                    if (r.isFailure) lastMessage = r.exceptionOrNull()?.message
                                                }
                                                pinchActive = false
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        lastMessage = t.message
                                    }
                                    true
                                }
                            }
                        },
                        update = { container ->
                            val view = container.tag as? SurfaceView
                            if (view != null) {
                                val w = view.width
                                val h = view.height
                                if (w > 0 && h > 0) {
                                    previewViewWidth = w
                                    previewViewHeight = h
                                }
                            }
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(virtualWidth.toFloat() / virtualHeight.toFloat()),
                    )
                }
            }

            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(controlsWeight, fill = true)
                    .verticalScroll(buttonScrollState)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExposedDropdownMenuBox(
                    expanded = appMenuExpanded,
                    onExpandedChange = { appMenuExpanded = !appMenuExpanded },
                ) {
                    TextField(
                        value = selectedApp?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择应用") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = appMenuExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )

                    DropdownMenu(
                        expanded = appMenuExpanded,
                        onDismissRequest = { appMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 200.dp),
                    ) {
                        for (app in apps) {
                            DropdownMenuItem(
                                text = { Text(app.label) },
                                onClick = {
                                    selectedApp = app
                                    appMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                fun requestShizukuPermission() {
                    if (!Shizuku.pingBinder()) {
                        lastMessage = "Shizuku 未就绪"
                        return
                    }
                    val granted = try {
                        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } catch (t: Throwable) {
                        lastMessage = t.message
                        false
                    }
                    if (!granted) {
                        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                        lastMessage = "正在请求 Shizuku 权限"
                    } else {
                        lastMessage = "Shizuku 权限已授予"
                    }
                }

                fun requestStartVirtualDisplay() {
                    if (!previewSurfaceReady) {
                        lastMessage = "预览区域未就绪"
                        return
                    }
                    if (virtualDisplayStartRequested) {
                        lastMessage = "虚拟屏幕已请求启动"
                        return
                    }
                    virtualDisplayStartRequested = true
                    lastMessage = "虚拟屏幕启动请求已发送"
                }

                fun destroyVirtualDisplay() {
                    val r = VirtualDisplaySurfaceStreamer.destroy()
                    if (r.isSuccess) {
                        displayId = null
                        virtualDisplayStartRequested = false
                        lastMessage = "已销毁虚拟屏幕"
                    } else {
                        lastMessage = r.exceptionOrNull()?.message
                    }
                }

                fun saveScreenshotToFile() {
                    val path = "/storage/emulated/0/虚拟屏幕画面.png"
                    val sv = previewSurfaceView
                    if (sv == null) {
                        lastMessage = "预览Surface未就绪"
                        return
                    }
                    if (!previewSurfaceReady) {
                        lastMessage = "预览区域未就绪"
                        return
                    }

                    coroutineScope.launch {
                        try {
                            val w = sv.width
                            val h = sv.height
                            if (w <= 0 || h <= 0) {
                                lastMessage = "预览尺寸无效：${w}x${h}"
                                return@launch
                            }

                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val handler = Handler(Looper.getMainLooper())
                            PixelCopy.request(
                                sv,
                                bmp,
                                listener@{ result ->
                                    if (result != PixelCopy.SUCCESS) {
                                        lastMessage = "截图失败：PixelCopy=$result"
                                        Log.w("MainActivity", "PixelCopy failed: code=$result")
                                        return@listener
                                    }
                                    try {
                                        val tmpDir = context.externalCacheDir ?: context.cacheDir
                                        val tmp = File(tmpDir, "virtual_display_preview.png")
                                        FileOutputStream(tmp).use { fos ->
                                            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                                                throw IllegalStateException("Bitmap compress failed")
                                            }
                                        }

                                        val shizukuGranted = runCatching {
                                            Shizuku.pingBinder() &&
                                                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        }.getOrDefault(false)

                                        if (!shizukuGranted) {
                                            throw IllegalStateException("Shizuku权限未授予，无法保存到根目录")
                                        }

                                        val cmd = "cp '${tmp.absolutePath}' '$path' && chmod 666 '$path'"
                                        val r = ShizukuShell.execForText(cmd)
                                        if (r.isFailure) {
                                            throw r.exceptionOrNull() ?: IllegalStateException("shizuku shell failed")
                                        }

                                        lastMessage = "已保存到根目录：$path"
                                        Log.i(
                                            "MainActivity",
                                            "preview screenshot saved to root via shizuku: $path size=${bmp.width}x${bmp.height}",
                                        )
                                        return@listener
                                    } catch (t: Throwable) {
                                        lastMessage = t.message
                                        Log.e("MainActivity", "save preview screenshot failed", t)
                                    }
                                },
                                handler,
                            )
                        } catch (t: Throwable) {
                            lastMessage = t.message
                            Log.e("MainActivity", "PixelCopy request failed", t)
                        }
                    }
                }

                fun forwardBack() {
                    val id = displayId
                    if (id == null) {
                        lastMessage = "displayId 为空"
                        return
                    }
                    val r = TouchForwarder.forwardBack(context = context, displayId = id)
                    if (r.isFailure) lastMessage = r.exceptionOrNull()?.message
                }

                fun launchOnVirtualDisplay() {
                    val id = displayId
                    if (id == null) {
                        lastMessage = "displayId 为空"
                        return
                    }
                    val app = selectedApp
                    if (app == null) {
                        lastMessage = "未选择应用"
                        return
                    }
                    val shizukuGranted = try {
                        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } catch (_: Throwable) {
                        false
                    }
                    if (!shizukuGranted) {
                        lastMessage = "请先授予 Shizuku 权限"
                        return
                    }
                    val r2 = ActivityLaunchUtils.launchPackageOnDisplayByShizukuFullscreen(
                        context = context,
                        packageName = app.packageName,
                        displayId = id,
                    )
                    lastMessage = if (r2.isSuccess) {
                        "已在虚拟屏幕启动：${app.label}"
                    } else {
                        r2.exceptionOrNull()?.message
                    }
                }

                val buttonWeight = Modifier.weight(1f)
                val rowPadding = Arrangement.spacedBy(10.dp)

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = rowPadding, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { requestShizukuPermission() }, modifier = buttonWeight) {
                            Text(text = "Shizuku 权限")
                        }
                        Button(onClick = { requestStartVirtualDisplay() }, modifier = buttonWeight) {
                            Text(text = "创建虚拟屏幕")
                        }
                    }
                    Row(horizontalArrangement = rowPadding, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { destroyVirtualDisplay() }, modifier = buttonWeight) {
                            Text(text = "销毁虚拟屏幕")
                        }
                        Button(onClick = { saveScreenshotToFile() }, modifier = buttonWeight) {
                            Text(text = "保存截图")
                        }
                    }
                    Row(horizontalArrangement = rowPadding, modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { launchOnVirtualDisplay() }, modifier = buttonWeight) {
                            Text(text = "启动应用")
                        }
                        Button(onClick = { forwardBack() }, modifier = buttonWeight) {
                            Text(text = "返回")
                        }
                    }
                }

                Text(text = lastMessage ?: "")
                Spacer(modifier = Modifier.height(4.dp))
        }
    }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PingmutestTheme {
        Greeting("Android")
    }
}