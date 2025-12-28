package com.example.autoglm

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

object ShizukuBridge {

    private const val TAG = "AutoglmShizuku"

    data class ExecResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) {
        fun stdoutText(): String {
            return try {
                stdout.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }

        fun stderrText(): String {
            return try {
                stderr.toString(Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun newProcess(command: Array<String>): Any {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null)
    }

    @JvmStatic
    fun pingBinder(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    fun resolvePackage(query: String?): String {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return ""
        if (q.contains('.')) return q
        val ctx = AppState.getAppContext() ?: return ""
        val pm = ctx.packageManager ?: return ""
        return try {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            var best: String? = null
            for (app in apps) {
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Throwable) {
                    ""
                }
                if (label.isEmpty()) continue
                if (label == q) {
                    return app.packageName ?: ""
                }
                if (best == null && label.equals(q, ignoreCase = true)) {
                    best = app.packageName
                }
            }
            best ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun execBytes(command: String): ByteArray {
        val r = execResult(command)
        return if (r.exitCode == 0) r.stdout else ByteArray(0)
    }

    @JvmStatic
    fun execText(command: String): String {
        val bytes = execBytes(command)
        if (bytes.isEmpty()) return ""
        return try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    fun execResult(command: String): ExecResult {
        return try {
            if (!pingBinder()) return ExecResult(exitCode = -1, stdout = ByteArray(0), stderr = ByteArray(0))
            if (!hasPermission()) return ExecResult(exitCode = -2, stdout = ByteArray(0), stderr = ByteArray(0))

            val process = newProcess(arrayOf("sh", "-c", command))
            val cls = process.javaClass
            val inputStream = cls.getMethod("getInputStream").invoke(process) as java.io.InputStream
            val errorStream = cls.getMethod("getErrorStream").invoke(process) as java.io.InputStream

            val outBytes = readAllBytes(inputStream)
            val errBytes = readAllBytes(errorStream)
            val exitCode = cls.getMethod("waitFor").invoke(process) as Int

            if (exitCode != 0) {
                val stderrText = try {
                    errBytes.toString(Charsets.UTF_8).trim()
                } catch (_: Exception) {
                    ""
                }
                val stdoutText = try {
                    outBytes.toString(Charsets.UTF_8).trim()
                } catch (_: Exception) {
                    ""
                }
                Log.w(TAG, "Shizuku exec failed: exitCode=$exitCode cmd=$command stderr=${stderrText.take(500)} stdout=${stdoutText.take(500)}")
            }

            ExecResult(exitCode = exitCode, stdout = outBytes, stderr = errBytes)
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku exec exception: ${t.message}")
            ExecResult(exitCode = -3, stdout = ByteArray(0), stderr = ByteArray(0))
        }
    }

    private fun readAllBytes(input: java.io.InputStream): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
