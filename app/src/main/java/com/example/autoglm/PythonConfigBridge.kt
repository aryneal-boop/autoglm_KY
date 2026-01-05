package com.example.autoglm

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python

/**
 * Kotlin -> Python 配置注入桥接。
 *
 * **用途**
 * - 将 Android 侧的运行时配置（见 [ConfigManager]）写入 Chaquopy Python 的 `os.environ`，
 *   让 Python 侧 `phone_agent/autoglm` 在不直接依赖 Android 代码的情况下读取配置。
 *
 * **注入的关键环境变量（节选）**
 * - `PHONE_AGENT_BASE_URL` / `PHONE_AGENT_API_KEY` / `PHONE_AGENT_MODEL`
 * - `PHONE_AGENT_EXECUTION_ENV`：主屏/虚拟隔离模式（见 [ConfigManager.EXEC_ENV_MAIN]/[ConfigManager.EXEC_ENV_VIRTUAL]）
 * - `PHONE_AGENT_CONNECT_MODE` / `AUTOGM_CONNECT_MODE`：无线调试/Shizuku（见 [ConfigManager.ADB_MODE_WIRELESS_DEBUG]/[ConfigManager.ADB_MODE_SHIZUKU]）
 * - `PHONE_AGENT_DISPLAY_ID`：虚拟屏 displayId（由 [VirtualDisplayController] 提供）
 * - `ZHIPU_API_KEY`：语音转写 Key（当前复用模型 Key）
 *
 * **典型用法**
 * - `val py = Python.getInstance(); PythonConfigBridge.injectModelServiceConfig(context, py)`
 *
 * **引用路径（常见）**
 * - `MainActivity`：应用启动阶段初始化 Python 环境并注入配置。
 * - `FloatingStatusService`：执行任务前注入配置，确保“设置页修改立即生效”。
 *
 * **使用注意事项**
 * - 该方法假定 Python 已启动（`Python.isStarted()`），否则可能抛异常；调用方通常需要先启动 Python。
 * - 注入是覆盖式写入：设置页修改后应再次调用以刷新 `os.environ`。
 */
object PythonConfigBridge {
    fun injectModelServiceConfig(context: Context, py: Python = Python.getInstance()) {
        val config = ConfigManager(context).getConfig()
        val cm = ConfigManager(context)

        val osModule = py.getModule("os")
        val environ = osModule["environ"] ?: return

        setEnv(environ, "PHONE_AGENT_BASE_URL", config.baseUrl)
        setEnv(environ, "PHONE_AGENT_MODEL", config.modelName)
        setEnv(environ, "PHONE_AGENT_API_KEY", config.apiKey)

        val execEnv = try {
            cm.getExecutionEnvironment()
        } catch (_: Exception) {
            ConfigManager.EXEC_ENV_MAIN
        }
        setEnv(environ, "PHONE_AGENT_EXECUTION_ENV", execEnv)

        val adbMode = try {
            cm.getAdbConnectMode()
        } catch (_: Exception) {
            ConfigManager.ADB_MODE_WIRELESS_DEBUG
        }
        setEnv(environ, "AUTOGM_CONNECT_MODE", adbMode)
        setEnv(environ, "PHONE_AGENT_CONNECT_MODE", adbMode)

        val displayId = try {
            VirtualDisplayController.getDisplayId()
        } catch (_: Exception) {
            null
        }
        setEnv(environ, "PHONE_AGENT_DISPLAY_ID", displayId?.toString().orEmpty())

        // 复用同一份 Key 作为智谱语音转写 Key（Python 侧优先读取 ZHIPU_API_KEY）。
        setEnv(environ, "ZHIPU_API_KEY", config.apiKey)
    }

    private fun setEnv(environ: PyObject, key: String, value: String) {
        environ.callAttr("__setitem__", key, value)
    }
}
