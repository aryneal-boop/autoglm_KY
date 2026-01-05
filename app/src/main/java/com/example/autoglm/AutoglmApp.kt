package com.example.autoglm

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 全局 Application 入口。
 *
 * **用途**
 * - 承担应用进程启动时的最早初始化：
 *   - Android P(28)+ 下通过 `HiddenApiBypass` 尝试放宽隐藏 API 限制（用于后续的反射/系统服务访问）。
 *   - 初始化全局状态（见 [AppState]）。
 *
 * **引用路径**
 * - `app/src/main/AndroidManifest.xml` -> `<application android:name=".AutoglmApp" ...>`
 *
 * **使用注意事项**
 * - 该初始化必须保持“尽力而为（best effort）”策略：任何失败都不应阻塞 App 启动。
 * - 不要在这里做耗时操作（避免冷启动卡顿）。
 */
class AutoglmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.addHiddenApiExemptions("L")
            }
        } catch (_: Throwable) {
        }
        AppState.init(this)
    }
}

/**
 * 全局状态与跨模块“静态入口”。
 *
 * **用途**
 * - 持有 `applicationContext`（避免各处传递 Context）。
 * - 初始化任务控制器 [TaskControl]。
 * - 安装/重置虚拟屏相关全局状态（见 [VirtualDisplayController]）。
 *
 * **典型用法**
 * - Kotlin 侧需要 Context 时：`AppState.getAppContext()`（仅在进程已启动后可用）。
 *
 * **引用路径**
 * - [AutoglmApp.onCreate] -> [AppState.init]
 * - 其他模块可能通过 `AppState.getAppContext()` 获取 Context（例如 [ShizukuBridge.resolvePackage]）。
 *
 * **使用注意事项**
 * - `getAppContext()` 可能返回 `null`（极端情况下，如进程尚未初始化完成），调用方必须做空值处理。
 */
object AppState {

    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        TaskControl.init(ctx)

        try {
            VirtualDisplayController.ensureProcessLifecycleObserverInstalled(ctx)
        } catch (_: Exception) {
        }

        try {
            VirtualDisplayController.hardResetOverlayAsync(ctx)
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun getAppContext(): Context? = appContext
}
