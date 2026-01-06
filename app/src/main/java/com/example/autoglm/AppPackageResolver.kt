package com.example.autoglm

import android.os.SystemClock

import java.util.concurrent.ConcurrentHashMap

/**
 * 应用包名解析器（label/模糊查询 -> packageName）。
 *
 * **用途**
 * - 将用户输入的“应用名称/关键字”解析为 Android `packageName`，用于后续通过 ADB/Shizuku 启动应用。
 * - 在 Shizuku 可用时，通过命令行方式加速获取：
 *   - `cmd package list packages` / `cmd package list packages -3`
 *   - `dumpsys package <pkg>` 提取 `application-label`
 * - 内置缓存（TTL + 时间预算）避免频繁全量遍历导致卡顿。
 *
 * **典型用法**
 * - `val pkg = AppPackageResolver.resolvePackageBestEffort("微信")`
 *
 * **引用路径（常见）**
 * - Python 侧动作（Launch/打开应用）或 Kotlin 侧调试工具可能会调用该能力。
 *
 * **使用注意事项**
 * - 强依赖 Shizuku：未授权/不可用时只会返回空结果或走降级逻辑。
 * - `dumpsys package` 输出在不同 ROM 可能差异：解析时需 best-effort。
 */
object AppPackageResolver {

    private fun normalize(s: String): String {
        return s.trim().replace(" ", "")
    }

    @Volatile
    private var cachedAtMs: Long = 0L

    private val labelToPkgCache: MutableMap<String, String> = ConcurrentHashMap()

    @Volatile
    private var thirdPartyCachedAtMs: Long = 0L

    private val thirdPartyPackagesCache: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun shizukuReady(): Boolean {
        return try {
            ShizukuBridge.pingBinder() && ShizukuBridge.hasPermission()
        } catch (_: Throwable) {
            false
        }
    }

    private fun listPackagesViaShizuku(): List<String> {
        val out = try {
            ShizukuBridge.execText("cmd package list packages")
        } catch (_: Throwable) {
            ""
        }
        if (out.isBlank()) return emptyList()
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun listThirdPartyPackagesViaShizuku(): List<String> {
        val out = try {
            ShizukuBridge.execText("cmd package list packages -3")
        } catch (_: Throwable) {
            ""
        }
        if (out.isBlank()) return emptyList()
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun ensureThirdPartyCacheBestEffort(nowMs: Long) {
        if (!shizukuReady()) return
        val ttlMs = 10 * 60_000L
        if (thirdPartyPackagesCache.isNotEmpty() && nowMs - thirdPartyCachedAtMs <= ttlMs) return

        val pkgs = listThirdPartyPackagesViaShizuku()
        if (pkgs.isEmpty()) return
        thirdPartyPackagesCache.clear()
        thirdPartyPackagesCache.addAll(pkgs)
        thirdPartyCachedAtMs = nowMs
    }

    @JvmStatic
    fun prewarmThirdPartyPackagesAsync() {
        if (!shizukuReady()) return
        Thread {
            try {
                val nowMs = System.currentTimeMillis()
                ensureThirdPartyCacheBestEffort(nowMs)
                ensureLabelCacheBestEffort(nowMs)
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun readLabelViaShizuku(packageName: String): String {
        if (packageName.isBlank()) return ""
        val out = try {
            ShizukuBridge.execText("dumpsys package $packageName")
        } catch (_: Throwable) {
            ""
        }
        if (out.isBlank()) return ""

        val lines = out.lineSequence().map { it.trim() }
        for (line in lines) {
            if (!line.startsWith("application-label")) continue
            val idx = line.indexOf(':')
            if (idx <= 0 || idx >= line.length - 1) continue
            var v = line.substring(idx + 1).trim()
            if (v.startsWith("'")) v = v.removePrefix("'")
            if (v.endsWith("'")) v = v.removeSuffix("'")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun ensureLabelCacheBestEffort(nowMs: Long) {
        if (!shizukuReady()) return
        val ttlMs = 60_000L
        if (labelToPkgCache.isNotEmpty() && nowMs - cachedAtMs <= ttlMs) return

        // 仅基于第三方应用构建 label 缓存，避免全量遍历导致卡顿。
        ensureThirdPartyCacheBestEffort(nowMs)
        val pkgs = thirdPartyPackagesCache.toList()
        if (pkgs.isEmpty()) return

        val timeBudgetMs = 2_000L
        val t0 = SystemClock.uptimeMillis()

        labelToPkgCache.clear()
        for (pkg in pkgs) {
            if (SystemClock.uptimeMillis() - t0 > timeBudgetMs) break
            val label = readLabelViaShizuku(pkg)
            if (label.isBlank()) continue
            val k = normalize(label).lowercase()
            if (k.isBlank()) continue
            labelToPkgCache[k] = pkg
        }
        cachedAtMs = nowMs
    }

    @JvmStatic
    fun resolvePackage(appNameOrPackage: String?): String {
        val q0 = (appNameOrPackage ?: "").trim()
        if (q0.isEmpty()) return ""
        if (q0.contains('.') && !q0.contains(' ')) return q0

        val q = normalize(q0)
        if (q.isEmpty()) return ""

        val nowMs = System.currentTimeMillis()
        ensureLabelCacheBestEffort(nowMs)

        val qKey = q.lowercase()
        val exact = labelToPkgCache[qKey]
        if (!exact.isNullOrBlank()) return exact

        var bestContains: String = ""
        var bestContainsLabelLen = Int.MAX_VALUE

        for ((labelKey, pkg) in labelToPkgCache) {
            if (labelKey.contains(qKey) || qKey.contains(labelKey)) {
                val plen = labelKey.length
                if (plen < bestContainsLabelLen) {
                    bestContainsLabelLen = plen
                    bestContains = pkg
                }
            }
        }

        return bestContains
    }

    @JvmStatic
    fun isPackageInstalled(packageName: String?): Boolean {
        val pkg = (packageName ?: "").trim()
        if (pkg.isEmpty()) return false

        if (shizukuReady()) {
            val nowMs = System.currentTimeMillis()
            ensureThirdPartyCacheBestEffort(nowMs)
            if (thirdPartyPackagesCache.contains(pkg)) return true

            // 兼容部分 ROM：cmd package path 可能返回 exitCode=1 但实际已安装。
            val out1 = try {
                ShizukuBridge.execText("cmd package list packages $pkg")
            } catch (_: Throwable) {
                ""
            }
            if (out1.lineSequence().any { it.trim() == "package:$pkg" }) {
                thirdPartyPackagesCache.add(pkg)
                return true
            }

            val out2 = try {
                ShizukuBridge.execText("pm path $pkg")
            } catch (_: Throwable) {
                ""
            }
            if (out2.trim().startsWith("package:")) {
                thirdPartyPackagesCache.add(pkg)
                return true
            }

            val out3 = try {
                ShizukuBridge.execText("dumpsys package $pkg")
            } catch (_: Throwable) {
                ""
            }
            if (out3.contains("Package [$pkg]")) {
                thirdPartyPackagesCache.add(pkg)
                return true
            }

            return false
        }

        return false
    }
}
