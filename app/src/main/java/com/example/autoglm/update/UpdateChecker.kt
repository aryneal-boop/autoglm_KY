package com.example.autoglm.update

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 简易更新检查器（拉取 JSON）。
 *
 * **用途**
 * - 从后端拉取更新描述 JSON，解析得到 [UpdateInfo]，用于在 `ChatActivity` 等入口提示用户更新。
 * - 通过 `shouldPrompt/markPrompted` 控制“同一版本只弹一次”。
 *
 * **典型用法**
 * - `val info = UpdateChecker.fetchLatestInfo()`
 * - `if (info != null && UpdateChecker.isUpdateNeeded(ctx, info) && UpdateChecker.shouldPrompt(ctx, info.versionCode)) { ... }`
 *
 * **引用路径（常见）**
 * - `ChatActivity`：启动后异步检查更新并决定是否提示。
 *
 * **使用注意事项**
 * - 网络请求在 IO 线程执行（协程），调用方需在协程环境下使用。
 * - 默认更新地址为 HTTP：在正式环境建议使用 HTTPS，并考虑证书/劫持风险。
 */
object UpdateChecker {

    /**
     * 默认更新 JSON 地址（你的网站域名）。
     */
    const val DEFAULT_UPDATE_JSON_URL: String = "http://autoglm.itianyou.cn/PHP/api/update.php"

    private const val PREFS_NAME = "autoglm_update"
    private const val KEY_LAST_PROMPT_VERSION_CODE = "last_prompt_version_code"

    /**
     * 拉取后端更新 JSON 并解析。
     */
    suspend fun fetchLatestInfo(url: String = DEFAULT_UPDATE_JSON_URL): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    if (stream == null) return@withContext null

                    val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    parse(body)
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 比较当前 App 版本与服务端版本（优先使用 versionCode）。
     */
    fun isUpdateNeeded(context: Context, latest: UpdateInfo): Boolean {
        val currentCode = getCurrentVersionCode(context)
        if (currentCode <= 0) return false
        return currentCode < latest.versionCode
    }

    /**
     * 控制弹窗不要重复频繁弹出（同一版本只弹一次）。
     */
    fun shouldPrompt(context: Context, latestVersionCode: Int): Boolean {
        if (latestVersionCode <= 0) return false
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = sp.getInt(KEY_LAST_PROMPT_VERSION_CODE, 0)
        return latestVersionCode > last
    }

    fun markPrompted(context: Context, latestVersionCode: Int) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_LAST_PROMPT_VERSION_CODE, latestVersionCode).apply()
    }

    private fun parse(json: String): UpdateInfo? {
        return try {
            val root = JSONObject(json)
            val latest = root.optJSONObject("latest") ?: return null

            val versionName = latest.optString("versionName", "")
            val versionCode = latest.optInt("versionCode", 0)
            val changelog = latest.optString("changelog", "")
            val forceUpdate = latest.optBoolean("forceUpdate", false)
            val downloadPage = latest.optString("downloadPage", "")
            val apkUrl = latest.optString("apkUrl", "")

            if (versionCode <= 0) return null

            UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                changelog = changelog,
                forceUpdate = forceUpdate,
                downloadPage = downloadPage,
                apkUrl = apkUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pm = context.packageManager
            val pi = if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }

            val v = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            v.toInt()
        } catch (_: Exception) {
            0
        }
    }
}
