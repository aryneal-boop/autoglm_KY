package com.example.autoglm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

object TaskControl {

    private const val TAG = "AutoglmAdb"

    @Volatile
    @JvmField
    var isTaskRunning: Boolean = false

    @Volatile
    @JvmField
    var shouldStopTask: Boolean = false

    @Volatile
    private var currentJob: Job? = null

    @Volatile
    private var pythonThread: Thread? = null

    @Volatile
    private var executor: ExecutorService? = null

    @Volatile
    private var runningFuture: Future<*>? = null

    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun start(job: Job?) {
        isTaskRunning = true
        shouldStopTask = false
        currentJob = job
        pythonThread = null
    }

    @JvmStatic
    fun isExecutorBusy(): Boolean {
        val f = runningFuture
        return f != null && !f.isDone
    }

    @JvmStatic
    fun tryStartPythonTask(job: Job?, task: Runnable): Boolean {
        synchronized(this) {
            if (isExecutorBusy()) {
                return false
            }
            start(job)
            if (executor == null || executor?.isShutdown == true) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "PythonTaskThread").apply {
                        isDaemon = true
                    }
                }
            }
            runningFuture = executor!!.submit {
                try {
                    task.run()
                } finally {
                    pythonThread = null
                }
            }
            return true
        }
    }

    @JvmStatic
    fun bindPythonThread(thread: Thread?) {
        pythonThread = thread
    }

    @JvmStatic
    fun shouldContinue(): Boolean {
        return isTaskRunning && !shouldStopTask
    }

    @JvmStatic
    fun stop() {
        isTaskRunning = false
        shouldStopTask = true

        try {
            currentJob?.cancel("User stopped")
        } catch (_: Exception) {
        }

        try {
            pythonThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            val ctx = appContext
            if (ctx != null) {
                try {
                    VirtualDisplayController.cleanupAsync(ctx)
                } catch (_: Exception) {
                }
                val adbExecPath = LocalAdb.resolveAdbExecPath(ctx)
                LocalAdb.runCommand(
                    ctx,
                    LocalAdb.buildAdbCommand(ctx, adbExecPath, listOf("kill-server")),
                    timeoutMs = 5_000L
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "停止时执行 adb kill-server 失败（已忽略）: ${t.message}")
        }
    }

    @JvmStatic
    fun forceStopPython() {
        isTaskRunning = false
        shouldStopTask = true

        try {
            currentJob?.cancel("User stopped")
        } catch (_: Exception) {
        }

        try {
            runningFuture?.cancel(true)
        } catch (_: Exception) {
        }

        try {
            executor?.shutdownNow()
        } catch (_: Exception) {
        }

        executor = null
        runningFuture = null

        try {
            pythonThread?.interrupt()
        } catch (_: Exception) {
        }

        try {
            val ctx = appContext
            if (ctx != null) {
                try {
                    VirtualDisplayController.cleanupAsync(ctx)
                } catch (_: Exception) {
                }
                val adbExecPath = LocalAdb.resolveAdbExecPath(ctx)
                try {
                    LocalAdb.sendGlobalTouchUp(ctx, adbExecPath)
                } catch (_: Exception) {
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "forceStopPython 善后失败（已忽略）: ${t.message}")
        }
    }

    @JvmStatic
    fun onTaskFinished() {
        isTaskRunning = false
        shouldStopTask = false
        currentJob = null
        pythonThread = null
        runningFuture = null
    }
}
