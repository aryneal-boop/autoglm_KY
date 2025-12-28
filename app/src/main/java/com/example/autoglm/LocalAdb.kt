package com.example.autoglm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import kotlin.concurrent.thread

internal object LocalAdb {

    private const val TAG = "AutoglmAdb"
    private const val MAX_FAIL_LOG_CHARS = 1200
    private const val MAX_SERVER_LOG_HEAD_BYTES = 4096
    private const val MIN_SCREENSHOT_PNG_BYTES = 2048

    data class CommandResult(
        val exitCode: Int,
        val output: String,
    )

    data class CommandBytesResult(
        val exitCode: Int,
        val output: ByteArray,
    )

    data class CommandRequest(
        val argv: List<String>,
        val env: Map<String, String> = emptyMap(),
    )

    private fun mergeLdLibraryPath(env: MutableMap<String, String>, baseLd: String) {
        val existingLd = env["LD_LIBRARY_PATH"].orEmpty()
        if (existingLd.isEmpty()) {
            env["LD_LIBRARY_PATH"] = baseLd
            return
        }
        if (!existingLd.contains(baseLd)) {
            env["LD_LIBRARY_PATH"] = "$baseLd:$existingLd"
        }
    }

    private fun logCommandFailure(output: String) {
        val trimmed = if (output.length <= MAX_FAIL_LOG_CHARS) output else output.take(MAX_FAIL_LOG_CHARS) + "\n...<truncated>"
        Log.e(TAG, "命令失败输出(截断):\n$trimmed")

        try {
            val marker = "Full server startup log:"
            val idx = output.indexOf(marker)
            if (idx >= 0) {
                val after = output.substring(idx + marker.length).trim()
                val logPath = after.lineSequence().firstOrNull()?.trim().orEmpty()
                if (logPath.startsWith("/")) {
                    val f = File(logPath)
                    if (f.exists()) {
                        val head = f.safeHeadText(MAX_SERVER_LOG_HEAD_BYTES)
                        Log.e(TAG, "server startup log head: $logPath\n$head")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun baseEnv(context: Context): Map<String, String> {
        val homeDir = context.filesDir.absolutePath
        val tmpDir = context.cacheDir.absolutePath
        val vendorKeys = File(context.filesDir, ".android").absolutePath
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()
        val systemLib = if (abi.contains("arm64")) "/system/lib64" else "/system/lib"
        val extractedLibDir = File(context.filesDir, "lib").absolutePath
        val ldLibraryPath = "$extractedLibDir:$systemLib"
        return mapOf(
            "HOME" to homeDir,
            "TMPDIR" to tmpDir,
            "ADB_VENDOR_KEYS" to vendorKeys,
            "LD_LIBRARY_PATH" to ldLibraryPath,
        )
    }

    fun prepareAdbEnvironment(context: Context) {
        try {
            File(context.filesDir, ".android").mkdirs()
        } catch (_: Exception) {
        }

        try {
            val cache = context.cacheDir
            val logs = cache.listFiles { _, name ->
                name.startsWith("adb") && (name.endsWith(".log") || name.endsWith(".txt"))
            } ?: emptyArray()
            for (f in logs) {
                try {
                    f.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        try {
            val adbExecPath = resolveAdbExecPath(context)
            runCommand(
                context,
                buildAdbCommand(context, adbExecPath, listOf("kill-server")),
                timeoutMs = 5_000L
            )
        } catch (_: Exception) {
        }
    }

    fun resolveAdbExecPath(context: Context): String {
        val adbPath = File(context.filesDir, "bin/adb").absolutePath
        val adbBinPath = File(context.filesDir, "bin/adb.bin").absolutePath
        val adbBinFile = File(adbBinPath)
        // 优先使用脚本包装器：避免直接通过 linker64 启动 adb.bin 导致 adb server 再拉起子进程时走到异常参数分支。
        // 脚本会设置 LD_LIBRARY_PATH 并通过系统 linker 启动 adb.bin，可兼容 noexec ROM。
        val adbFile = File(adbPath)
        if (adbFile.exists() && !adbFile.isLikelyElfBinary()) return adbPath
        if (adbBinFile.exists() && adbBinFile.isLikelyElfBinary()) return adbBinPath
        return adbPath
    }

    fun buildAdbCommand(context: Context, adbExecPath: String, adbArgs: List<String>): CommandRequest {
        val execFile = File(adbExecPath)
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()
        val baseEnv = baseEnv(context)

        // 统一用 /system/bin/sh 启动脚本包装器，绕过 X_OK/noexec 差异。
        // 脚本内部会通过系统 linker 启动 adb.bin，并负责设置 LD_LIBRARY_PATH。
        if (execFile.name == "adb" && execFile.exists() && !execFile.isLikelyElfBinary()) {
            return CommandRequest(
                argv = listOf("/system/bin/sh", adbExecPath) + adbArgs,
                env = baseEnv,
            )
        }

        if (execFile.name == "adb.bin" && execFile.isLikelyElfBinary()) {
            val linkerPath = if (abi.contains("arm64")) "/system/bin/linker64" else "/system/bin/linker"
            val extractedLibDir = File(context.filesDir, "lib").absolutePath
            val systemLib = if (abi.contains("arm64")) "/system/lib64" else "/system/lib"

            // 说明：在部分 ROM（例如 MIUI/HyperOS）中，直接执行应用私有目录下的 ELF 可能会触发 noexec/EACCES。
            // 因此这里通过系统 linker 启动 adb.bin，绕过直接 exec 的限制。
            // 注意：后续会尽量避免调用 adb start-server 触发 daemon 自举路径（可能出现 -L），
            // 而统一走 LocalAdb.startAdbServer 的 nodaemon server 方式启动。
            val ldLibraryPath = "$extractedLibDir:$systemLib"

            return CommandRequest(
                argv = listOf(linkerPath, adbExecPath) + adbArgs,
                env = mapOf(
                    "LD_LIBRARY_PATH" to ldLibraryPath,
                ) + baseEnv,
            )
        }

        return CommandRequest(
            argv = listOf(adbExecPath) + adbArgs,
            env = baseEnv,
        )
    }

    fun runCommand(request: CommandRequest, timeoutMs: Long = 20_000L): CommandResult {
        Log.w(TAG, "runCommand(request) 已不推荐使用：缺少 Context 无法强制注入 HOME/TMPDIR 环境")
        return try {
            val builder = ProcessBuilder(request.argv)
                .redirectErrorStream(true)
            if (request.env.isNotEmpty()) {
                builder.environment().putAll(request.env)
            }
            val process = builder.start()
            val out = StringBuilder()
            val readerThread = thread(start = true) {
                try {
                    out.append(process.inputStream.readAllText())
                } catch (_: Exception) {
                }
            }

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                readerThread.join(500)
                Log.w(TAG, "命令超时（${timeoutMs}ms）：${request.argv.joinToString(" ")}")
                return CommandResult(exitCode = -1, output = out.append("\n命令超时（${timeoutMs}ms）。").toString())
            }

            readerThread.join(1000)
            val result = CommandResult(exitCode = process.exitValue(), output = out.toString().trim())
            Log.i(TAG, "命令结束 exitCode=${result.exitCode}，输出长度=${result.output.length}")

            if (result.exitCode != 0) {
                logCommandFailure(result.output)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "命令执行异常", e)
            CommandResult(exitCode = -1, output = "命令执行异常：${e.message}")
        }
    }

    fun runCommandBytes(context: Context, request: CommandRequest, timeoutMs: Long = 20_000L): CommandBytesResult {
        Log.i(TAG, "执行命令(二进制)：${request.argv.joinToString(" ")}")
        if (request.env.isNotEmpty()) {
            Log.i(TAG, "附加环境变量：${request.env.keys.joinToString(",")}")
        }

        return try {
            val builder = ProcessBuilder(request.argv)
                .redirectErrorStream(true)

            val env = builder.environment()

            if (request.env.isNotEmpty()) {
                env.putAll(request.env)
            }

            val base = baseEnv(context)
            env.putAll(base)

            val baseLd = base["LD_LIBRARY_PATH"].orEmpty()
            mergeLdLibraryPath(env, baseLd)

            val process = builder.start()

            val out = ByteArrayOutputStream()
            val readerThread = thread(start = true) {
                try {
                    val buf = ByteArray(32 * 1024)
                    val input = process.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                } catch (_: Exception) {
                }
            }

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                readerThread.join(500)
                Log.w(TAG, "命令(二进制)超时（${timeoutMs}ms）：${request.argv.joinToString(" ")}")
                return CommandBytesResult(exitCode = -1, output = out.toByteArray())
            }

            readerThread.join(1000)
            val result = CommandBytesResult(exitCode = process.exitValue(), output = out.toByteArray())
            Log.i(TAG, "命令结束(二进制) exitCode=${result.exitCode}，输出长度=${result.output.size}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "命令执行异常(二进制)", e)
            CommandBytesResult(exitCode = -1, output = ByteArray(0))
        }
    }

    fun runCommand(context: Context, request: CommandRequest, timeoutMs: Long = 20_000L): CommandResult {
        Log.i(TAG, "执行命令：${request.argv.joinToString(" ")}")
        if (request.env.isNotEmpty()) {
            Log.i(TAG, "附加环境变量：${request.env.keys.joinToString(",")}")
        }

        return try {
            val builder = ProcessBuilder(request.argv)
                .redirectErrorStream(true)

            val env = builder.environment()

            if (request.env.isNotEmpty()) {
                env.putAll(request.env)
            }

            val base = baseEnv(context)
            env.putAll(base)

            val baseLd = base["LD_LIBRARY_PATH"].orEmpty()
            mergeLdLibraryPath(env, baseLd)

            val process = builder.start()

            val out = StringBuilder()
            val readerThread = thread(start = true) {
                try {
                    out.append(process.inputStream.readAllText())
                } catch (_: Exception) {
                }
            }

            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                readerThread.join(500)
                Log.w(TAG, "命令超时（${timeoutMs}ms）：${request.argv.joinToString(" ")}")
                return CommandResult(exitCode = -1, output = out.append("\n命令超时（${timeoutMs}ms）。").toString())
            }

            readerThread.join(1000)
            val result = CommandResult(exitCode = process.exitValue(), output = out.toString().trim())
            Log.i(TAG, "命令结束 exitCode=${result.exitCode}，输出长度=${result.output.length}")

            if (result.exitCode != 0) {
                logCommandFailure(result.output)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "命令执行异常", e)
            CommandResult(exitCode = -1, output = "命令执行异常：${e.message}")
        }
    }

    fun isAdbServerRunning(): Boolean {
        return try {
            java.net.Socket("127.0.0.1", 5037).use { true }
        } catch (_: Exception) {
            false
        }
    }

    fun startAdbServer(context: Context, adbExecPath: String) {
        val serverLogFile = File(context.cacheDir, "adb_server.log")
        try {
            Log.i(TAG, "正在尝试手动启动 ADB Server...")

            if (serverLogFile.exists()) {
                serverLogFile.delete()
            }

            val cmdRequest = buildAdbCommand(context, adbExecPath, listOf("nodaemon", "server"))

            val builder = ProcessBuilder(cmdRequest.argv)
            val env = builder.environment()
            if (cmdRequest.env.isNotEmpty()) {
                env.putAll(cmdRequest.env)
            }

            // 强制注入 HOME/TMPDIR/LD_LIBRARY_PATH：确保 ADB 密钥与临时目录稳定、并避免 linker 找不到依赖库
            env.putAll(baseEnv(context))

            builder.redirectErrorStream(true)
            builder.redirectOutput(serverLogFile)

            val serverProcess = builder.start()

            var attempts = 0
            while (attempts < 20) {
                Thread.sleep(200)
                if (!serverProcess.isAlive) {
                    Log.e(TAG, "ADB Server 进程意外退出 exitValue=${serverProcess.exitValue()}")
                    break
                }
                if (isAdbServerRunning()) {
                    Log.i(TAG, "ADB Server 启动成功 (检测到端口监听)")
                    return
                }
                attempts++
            }

            Log.w(TAG, "ADB Server 启动后未在预期时间内检测到端口监听")
            if (serverProcess.isAlive) {
                serverProcess.destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "手动启动 ADB Server 异常", e)
        } finally {
            if (!isAdbServerRunning() && serverLogFile.exists()) {
                val logContent = serverLogFile.safeHeadText(4096)
                Log.e(TAG, "ADB Server 启动失败日志:\n$logContent")
            }
        }
    }

    fun sendGlobalTouchUp(context: Context, adbExecPath: String): CommandResult {
        return runCommand(
            context,
            buildAdbCommand(
                context,
                adbExecPath,
                listOf("shell", "input", "swipe", "0", "0", "0", "0", "1")
            ),
            timeoutMs = 3_000L
        )
    }

    private fun InputStream.readAllText(): String {
        return bufferedReader(Charsets.UTF_8).use { it.readText() }
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

    private fun File.safeHeadText(maxBytes: Int = 256): String {
        return try {
            if (!exists()) return "<file_not_found>"
            FileInputStream(this).use { fis ->
                val buf = ByteArray(maxBytes)
                val read = fis.read(buf)
                if (read <= 0) return "<empty>"
                val safe = buf.copyOfRange(0, read)
                safe.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "<read_error:${e.message}>"
        }
    }
}

object AdbBridge {

    private const val MIN_SCREENSHOT_PNG_BYTES = 2048

    @Volatile
    private var cachedCmdDisplayGetScreenshotSupported: Boolean? = null

    private fun isCmdDisplayGetScreenshotSupported(ctx: Context, adbExecPath: String): Boolean {
        val cached = cachedCmdDisplayGetScreenshotSupported
        if (cached != null) return cached

        fun readHelp(args: List<String>): String {
            val r = LocalAdb.runCommandBytes(
                ctx,
                LocalAdb.buildAdbCommand(ctx, adbExecPath, args),
                timeoutMs = 8_000L,
            )
            if (r.exitCode != 0 || r.output.isEmpty()) return ""
            return try {
                r.output.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }

        val helpText = sequenceOf(
            listOf("shell", "cmd", "display", "-h"),
            listOf("shell", "cmd", "display", "help"),
            listOf("shell", "cmd", "display"),
        ).map { readHelp(it) }
            .maxByOrNull { it.length }
            .orEmpty()

        val supported = helpText.contains("get-screenshot", ignoreCase = true)
        cachedCmdDisplayGetScreenshotSupported = supported
        return supported
    }

    private fun isLikelyValidPng(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_SCREENSHOT_PNG_BYTES) return false
        if (bytes.size < 8) return false
        val sig = byteArrayOf(
            0x89.toByte(),
            0x50.toByte(),
            0x4E.toByte(),
            0x47.toByte(),
            0x0D.toByte(),
            0x0A.toByte(),
            0x1A.toByte(),
            0x0A.toByte(),
        )
        for (i in sig.indices) {
            if (bytes[i] != sig[i]) return false
        }
        return true
    }

    private fun isLikelyBlackPng(bytes: ByteArray): Boolean {
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } ?: return true

        var thumb: Bitmap? = null
        try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return true

            val tw = minOf(64, w)
            val th = minOf(64, h)
            thumb = if (w != tw || h != th) {
                try {
                    Bitmap.createScaledBitmap(bmp, tw, th, true)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val src = thumb ?: bmp

            val pixels = IntArray(tw * th)
            src.getPixels(pixels, 0, tw, 0, 0, tw, th)

            var nonBlack = 0
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r > 10 || g > 10 || b > 10) {
                    nonBlack++
                    if (nonBlack >= 20) return false
                }
            }
            return true
        } finally {
            try {
                if (thumb != null && thumb !== bmp && !thumb!!.isRecycled) thumb!!.recycle()
            } catch (_: Exception) {
            }
            try {
                if (!bmp.isRecycled) bmp.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun validateScreenshotPng(bytes: ByteArray): Boolean {
        if (!isLikelyValidPng(bytes)) return false
        if (isLikelyBlackPng(bytes)) return false
        return true
    }

    private fun tryScreencapBytes(ctx: Context, adbExecPath: String, displayId: Int?): ByteArray {
        val args = ArrayList<String>()
        args.addAll(listOf("exec-out", "screencap"))
        if (displayId != null && displayId > 0) {
            args.addAll(listOf("-d", displayId.toString()))
        }
        args.add("-p")

        val r = LocalAdb.runCommandBytes(
            ctx,
            LocalAdb.buildAdbCommand(ctx, adbExecPath, args),
            timeoutMs = 12_000L,
        )
        if (r.exitCode != 0 || r.output.isEmpty()) return ByteArray(0)
        return r.output
    }

    private fun tryCmdDisplayGetScreenshotBytes(ctx: Context, adbExecPath: String, displayId: Int?): ByteArray {
        if (!isCmdDisplayGetScreenshotSupported(ctx, adbExecPath)) return ByteArray(0)
        val did = displayId ?: 0
        val remote = "/sdcard/autoglm_tmp_screenshot_${System.currentTimeMillis()}.png"

        val r1 = LocalAdb.runCommandBytes(
            ctx,
            LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("shell", "cmd", "display", "get-screenshot", did.toString(), remote)),
            timeoutMs = 12_000L,
        )
        if (r1.exitCode != 0) {
            val msg = try {
                r1.output.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            if (msg.contains("Unknown command", ignoreCase = true)) {
                cachedCmdDisplayGetScreenshotSupported = false
            }
            return ByteArray(0)
        }

        val r2 = LocalAdb.runCommandBytes(
            ctx,
            LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("exec-out", "cat", remote)),
            timeoutMs = 12_000L,
        )

        try {
            LocalAdb.runCommand(
                ctx,
                LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("shell", "rm", "-f", remote)),
                timeoutMs = 6_000L,
            )
        } catch (_: Exception) {
        }

        if (r2.exitCode != 0 || r2.output.isEmpty()) return ByteArray(0)
        return r2.output
    }

    @JvmStatic
    fun screencapPngBase64(displayId: Int?): String {
        val ctx = AppState.getAppContext() ?: return ""
        val adbExecPath = try {
            LocalAdb.resolveAdbExecPath(ctx)
        } catch (_: Exception) {
            "adb"
        }

        val a = tryScreencapBytes(ctx, adbExecPath, displayId)
        val bytes = if (a.isNotEmpty() && validateScreenshotPng(a)) {
            a
        } else {
            val b = tryCmdDisplayGetScreenshotBytes(ctx, adbExecPath, displayId)
            if (b.isNotEmpty() && validateScreenshotPng(b)) b else ByteArray(0)
        }

        if (bytes.isEmpty()) return ""
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    fun launchAppOnDisplay(packageName: String?, displayId: Int?): Boolean {
        val pkg = (packageName ?: "").trim()
        if (pkg.isEmpty()) return false

        val ctx = AppState.getAppContext() ?: return false
        val adbExecPath = try {
            LocalAdb.resolveAdbExecPath(ctx)
        } catch (_: Exception) {
            "adb"
        }

        val did = displayId ?: 0
        val base = arrayListOf(
            "shell",
            "am",
            "start",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            "-p",
            pkg,
        )

        fun run(args: List<String>): LocalAdb.CommandBytesResult {
            return LocalAdb.runCommandBytes(
                ctx,
                LocalAdb.buildAdbCommand(ctx, adbExecPath, args),
                timeoutMs = 12_000L,
            )
        }

        if (did > 0) {
            val withDisplay = arrayListOf<String>()
            withDisplay.addAll(listOf("shell", "am", "start", "--display", did.toString()))
            withDisplay.addAll(base.drop(3))
            val r1 = run(withDisplay)
            val out1 = try {
                r1.output.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
            if (r1.exitCode == 0 && !out1.contains("Error", ignoreCase = true)) return true
            if (!out1.contains("Unknown option", ignoreCase = true) && !out1.contains("--display", ignoreCase = true)) {
                return false
            }
        }

        val r2 = run(base)
        val out2 = try {
            r2.output.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
        return r2.exitCode == 0 && !out2.contains("Error", ignoreCase = true)
    }
}
