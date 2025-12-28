package com.example.autoglm

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
        return prefs.getString(KEY_ADB_CONNECT_MODE, ADB_MODE_WIRELESS_DEBUG)
            .orEmpty()
            .ifEmpty { ADB_MODE_WIRELESS_DEBUG }
    }

    fun setAdbConnectMode(value: String) {
        prefs.edit().putString(KEY_ADB_CONNECT_MODE, value.trim()).apply()
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

        const val EXEC_ENV_MAIN = "MAIN_SCREEN"
        const val EXEC_ENV_VIRTUAL = "VIRTUAL_ISOLATED"

        const val ADB_MODE_WIRELESS_DEBUG = "WIRELESS_DEBUG"
        const val ADB_MODE_SHIZUKU = "SHIZUKU"

        const val DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_API_KEY = ""
        const val DEFAULT_MODEL_NAME = "autoglm-phone"
    }
}
