package com.example.autoglm

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python

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
