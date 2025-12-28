package com.example.pingmutest.shizuku

import rikka.shizuku.Shizuku
import android.util.Log
import java.io.ByteArrayOutputStream

object ShizukuShell {

    private const val TAG = "ShizukuShell"
    private const val MAX_ERROR_TEXT_CHARS = 800

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

    fun execForBytes(command: String): Result<ByteArray> {
        return runCatching {
            if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku binder is not ready")

            val process = newProcess(arrayOf("sh", "-c", command))
            val cls = process.javaClass
            val inputStream = cls.getMethod("getInputStream").invoke(process) as java.io.InputStream
            val errorStream = cls.getMethod("getErrorStream").invoke(process) as java.io.InputStream

            val outBytes = readAllBytes(inputStream)
            val errBytes = readAllBytes(errorStream)
            val exitCode = cls.getMethod("waitFor").invoke(process) as Int

            if (exitCode != 0) {
                val stdoutText = outBytes.decodeToString().trim()
                val stderrText = errBytes.decodeToString().trim()
                val msg = buildString {
                    append("exitCode=")
                    append(exitCode)
                    if (stderrText.isNotBlank()) {
                        append(" stderr=")
                        append(stderrText.take(MAX_ERROR_TEXT_CHARS))
                    }
                    if (stdoutText.isNotBlank()) {
                        append(" stdout=")
                        append(stdoutText.take(MAX_ERROR_TEXT_CHARS))
                    }
                }
                throw IllegalStateException(msg)
            }

            outBytes
        }.onFailure { t ->
            Log.e(TAG, "execForBytes failed: cmd=$command", t)
        }
    }

    fun execForText(command: String): Result<String> {
        return execForBytes(command).map { it.decodeToString() }.onFailure { t ->
            Log.e(TAG, "execForText failed: cmd=$command", t)
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
