package com.example.autoglm

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * App 运行时配置管理器（加密存储）。
 *
 * **用途**
 * - 统一管理：
 *   - 模型服务配置（`baseUrl/apiKey/modelName`）
 *   - ADB 最近连接信息（endpoint + 时间戳）
 *   - 执行环境（主屏/虚拟隔离）与连接模式（无线调试/Shizuku）
 *   - 虚拟屏参数（DPI、分辨率预设）
 * - 使用 `EncryptedSharedPreferences` 保护敏感信息（尤其是 `apiKey`）。
 *
 * **典型用法**
 * - Kotlin 侧读取：`val cfg = ConfigManager(context).getConfig()`
 * - Kotlin 侧写入：`ConfigManager(context).setConfig(...)`
 * - Python 侧会通过 [PythonConfigBridge] 将这些配置注入到 `os.environ`。
 *
 * **引用路径（常见）**
 * - `SettingsActivity`：编辑并保存模型/虚拟屏参数。
 * - `PythonConfigBridge.injectModelServiceConfig`：将配置注入 Python 环境变量。
 * - `AdbAutoConnectManager` / `PairingService`：读取/写入 `LAST_ADB_ENDPOINT`。
 * - `VirtualDisplayController`：读取执行环境、虚拟屏 DPI/分辨率。
 *
 * **使用注意事项**
 * - `EncryptedSharedPreferences` 首次初始化可能较慢：避免在主线程频繁创建实例；建议复用同一个 `ConfigManager`。
 * - `apiKey` 为空是允许的（例如仅做 UI 演示/本地测试），调用方需要自己做校验与友好提示。
 */
class ConfigManager(context: Context) {
    data class ModelServiceConfig(
        val baseUrl: String,
        val apiKey: String,
        val modelName: String,
    )

    private val appContext = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getConfig(): ModelServiceConfig {
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifEmpty { DEFAULT_BASE_URL }
        val apiKey = prefs.getString(KEY_API_KEY, DEFAULT_API_KEY).orEmpty()
        val modelName = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL_NAME).orEmpty().ifEmpty { DEFAULT_MODEL_NAME }
        return ModelServiceConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelName = modelName,
        )
    }

    fun setConfig(config: ModelServiceConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL_NAME, config.modelName.trim())
            .apply()
    }

    fun setBaseUrl(value: String) {
        prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()
    }

    fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API_KEY, value).apply()
    }

    fun setModelName(value: String) {
        prefs.edit().putString(KEY_MODEL_NAME, value.trim()).apply()
    }

    fun getLastAdbEndpoint(): String {
        return prefs.getString(KEY_LAST_ADB_ENDPOINT, "").orEmpty()
    }

    fun getLastAdbConnectedAtMs(): Long {
        return prefs.getLong(KEY_LAST_ADB_CONNECTED_AT_MS, 0L)
    }

    fun setLastAdbEndpoint(endpoint: String) {
        val ep = endpoint.trim()
        prefs.edit()
            .putString(KEY_LAST_ADB_ENDPOINT, ep)
            .putLong(KEY_LAST_ADB_CONNECTED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun clearLastAdbEndpoint() {
        prefs.edit()
            .remove(KEY_LAST_ADB_ENDPOINT)
            .remove(KEY_LAST_ADB_CONNECTED_AT_MS)
            .apply()
    }

    fun getExecutionEnvironment(): String {
        return prefs.getString(KEY_EXECUTION_ENVIRONMENT, EXEC_ENV_MAIN).orEmpty().ifEmpty { EXEC_ENV_MAIN }
    }

    fun setExecutionEnvironment(value: String) {
        prefs.edit().putString(KEY_EXECUTION_ENVIRONMENT, value.trim()).apply()
    }

    fun getAdbConnectMode(): String {
        return prefs.getString(KEY_ADB_CONNECT_MODE, ADB_MODE_SHIZUKU)
            .orEmpty()
            .ifEmpty { ADB_MODE_SHIZUKU }
    }

    fun setAdbConnectMode(value: String) {
        prefs.edit().putString(KEY_ADB_CONNECT_MODE, value.trim()).apply()
    }

    fun getVirtualDisplayDpi(): Int {
        return prefs.getInt(KEY_VIRTUAL_DISPLAY_DPI, DEFAULT_VIRTUAL_DISPLAY_DPI)
            .takeIf { it in 72..640 }
            ?: DEFAULT_VIRTUAL_DISPLAY_DPI
    }

    fun setVirtualDisplayDpi(value: Int) {
        val v = value.takeIf { it in 72..640 } ?: DEFAULT_VIRTUAL_DISPLAY_DPI
        prefs.edit().putInt(KEY_VIRTUAL_DISPLAY_DPI, v).apply()
    }

    fun getVirtualDisplayResolutionPreset(): String {
        return prefs.getString(KEY_VIRTUAL_DISPLAY_RESOLUTION, DEFAULT_VIRTUAL_DISPLAY_RESOLUTION)
            .orEmpty()
            .ifEmpty { DEFAULT_VIRTUAL_DISPLAY_RESOLUTION }
            .takeIf { it in VIRTUAL_DISPLAY_RESOLUTION_PRESETS }
            ?: DEFAULT_VIRTUAL_DISPLAY_RESOLUTION
    }

    fun setVirtualDisplayResolutionPreset(value: String) {
        val v = value.takeIf { it in VIRTUAL_DISPLAY_RESOLUTION_PRESETS } ?: DEFAULT_VIRTUAL_DISPLAY_RESOLUTION
        prefs.edit().putString(KEY_VIRTUAL_DISPLAY_RESOLUTION, v).apply()
    }

    fun getVirtualDisplaySize(isLandscape: Boolean): Pair<Int, Int> {
        val preset = getVirtualDisplayResolutionPreset()
        val (w, h) = presetToSize(preset)
        return if (isLandscape) (h to w) else (w to h)
    }

    companion object {
        private const val PREFS_NAME = "autoglm_secure_config"

        private const val KEY_BASE_URL = "BASE_URL"
        private const val KEY_API_KEY = "API_KEY"
        private const val KEY_MODEL_NAME = "MODEL_NAME"

        private const val KEY_LAST_ADB_ENDPOINT = "LAST_ADB_ENDPOINT"
        private const val KEY_LAST_ADB_CONNECTED_AT_MS = "LAST_ADB_CONNECTED_AT_MS"

        private const val KEY_EXECUTION_ENVIRONMENT = "EXECUTION_ENVIRONMENT"

        private const val KEY_ADB_CONNECT_MODE = "ADB_CONNECT_MODE"

        private const val KEY_VIRTUAL_DISPLAY_DPI = "VIRTUAL_DISPLAY_DPI"

        private const val KEY_VIRTUAL_DISPLAY_RESOLUTION = "VIRTUAL_DISPLAY_RESOLUTION"

        const val EXEC_ENV_MAIN = "MAIN_SCREEN"
        const val EXEC_ENV_VIRTUAL = "VIRTUAL_ISOLATED"

        const val ADB_MODE_WIRELESS_DEBUG = "WIRELESS_DEBUG"
        const val ADB_MODE_SHIZUKU = "SHIZUKU"

        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_API_KEY = ""
        const val DEFAULT_MODEL_NAME = "autoglm-phone"

        const val DEFAULT_VIRTUAL_DISPLAY_DPI = 480

        const val RES_480P = "480P"
        const val RES_720P = "720P"
        const val RES_1080P = "1080P"

        val VIRTUAL_DISPLAY_RESOLUTION_PRESETS = setOf(RES_480P, RES_720P, RES_1080P)
        const val DEFAULT_VIRTUAL_DISPLAY_RESOLUTION = RES_1080P

        private fun align16(value: Int): Int {
            val v = value.coerceAtLeast(1)
            val down = (v / 16) * 16
            val up = ((v + 15) / 16) * 16
            return if (v - down <= up - v) down.coerceAtLeast(16) else up
        }

        private fun presetToSize(preset: String): Pair<Int, Int> {
            return when (preset) {
                RES_720P -> align16(720) to align16(1280)
                RES_1080P -> align16(1088) to align16(1920)
                else -> align16(480) to align16(848)
            }
        }
    }
}
