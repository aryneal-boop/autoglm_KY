package com.example.autoglm.update

/**
 * 应用更新信息模型。
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val forceUpdate: Boolean,
    val downloadPage: String,
    val apkUrl: String,
)
