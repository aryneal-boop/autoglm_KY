package com.example.pingmutest.system

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.pingmutest.shizuku.ShizukuShell
import com.example.pingmutest.shizuku.ShizukuServiceManager
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

object ActivityLaunchUtils {

    private const val TAG = "ActivityLaunchUtils"

    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

    fun launchPackageOnDisplay(
        context: Context,
        packageName: String,
        displayId: Int,
    ): Result<Unit> {
        return runCatching {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("No launch intent for package: $packageName")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)

            runCatching {
                val m = ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                m.invoke(options, WINDOWING_MODE_FULLSCREEN)
            }

            context.startActivity(intent, options.toBundle())
        }.onFailure { t ->
            Log.e(TAG, "launchPackageOnDisplay failed: pkg=$packageName displayId=$displayId", t)
        }
    }

    fun launchPackageOnDisplayByShizukuFullscreen(
        context: Context,
        packageName: String,
        displayId: Int,
    ): Result<Unit> {
        return runCatching {
            if (!Shizuku.pingBinder()) throw IllegalStateException("Shizuku binder is not ready")
            val granted = try {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (t: Throwable) {
                Log.w(TAG, "launchPackageOnDisplayByShizukuFullscreen: checkSelfPermission failed", t)
                false
            }
            if (!granted) throw IllegalStateException("Shizuku permission not granted")

            val moved = tryMoveExistingTaskToDisplayByShizuku(
                hostPackageName = context.packageName,
                packageName = packageName,
                displayId = displayId,
            )
            if (moved) {
                return@runCatching Unit
            }

            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("No launch intent for package: $packageName")
            val cn = intent.component ?: throw IllegalStateException("Launch intent has no component: $packageName")
            val component = cn.flattenToShortString()

            val flags = FLAG_ACTIVITY_NEW_TASK
            val candidates = arrayOf(
                "cmd activity start-activity --user 0 --display $displayId --windowing-mode $WINDOWING_MODE_FULLSCREEN --activity-reorder-to-front -n $component -f $flags",
                "cmd activity start-activity --user 0 --display $displayId --activity-reorder-to-front -n $component -f $flags",
                "am start --user 0 -n $component --display $displayId --windowingMode $WINDOWING_MODE_FULLSCREEN --activity-reorder-to-front -f $flags",
                "am start --user 0 -n $component --display $displayId --activity-reorder-to-front -f $flags",
            )

            val errors = StringBuilder()
            for (c in candidates) {
                val r = ShizukuShell.execForText(c)
                if (r.isSuccess) {
                    Log.i(TAG, "launchPackageOnDisplayByShizukuFullscreen: started by cmd=$c")
                    return@runCatching Unit
                }
                val msg = r.exceptionOrNull()?.message ?: "unknown"
                if (errors.isNotEmpty()) errors.append(" | ")
                errors.append(c)
                errors.append(" => ")
                errors.append(msg)
            }

            throw IllegalStateException(errors.toString().ifBlank { "start failed" })
            Unit
        }.onFailure { t ->
            Log.e(TAG, "launchPackageOnDisplayByShizukuFullscreen failed: pkg=$packageName displayId=$displayId", t)
        }
    }

    private fun tryMoveExistingTaskToDisplayByShizuku(
        hostPackageName: String,
        packageName: String,
        displayId: Int,
    ): Boolean {
        val textResult = ShizukuShell.execForText("dumpsys activity activities")
        val dumpsysText = textResult.getOrNull()
        if (dumpsysText.isNullOrBlank()) {
            if (textResult.isFailure) {
                Log.w(TAG, "tryMoveExistingTaskToDisplayByShizuku: dumpsys failed: pkg=$packageName", textResult.exceptionOrNull())
            }
            return false
        }
        val dumpsys = dumpsysText.lines()

        val taskId = findTaskIdForPackageFromDumpsys(dumpsys, packageName)
        if (taskId == null) {
            Log.d(TAG, "tryMoveExistingTaskToDisplayByShizuku: no taskId found in dumpsys: pkg=$packageName")
            return false
        }

        val movedByAtm = runCatching {
            val taskPkg = resolveTaskPackageNameByIActivityTaskManager(taskId)
            if (taskPkg == null) {
                Log.d(TAG, "tryMoveExistingTaskToDisplayByShizuku: getTaskInfo returned null: taskId=$taskId")
                return@runCatching false
            }
            if (!taskPkg.equals(packageName, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "tryMoveExistingTaskToDisplayByShizuku: task package mismatch, skip move: want=$packageName got=$taskPkg taskId=$taskId",
                )
                return@runCatching false
            }
            if (taskPkg.equals(hostPackageName, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "tryMoveExistingTaskToDisplayByShizuku: refusing to move host task: host=$hostPackageName taskId=$taskId",
                )
                return@runCatching false
            }

            moveTaskToDisplayByIActivityTaskManager(taskId = taskId, displayId = displayId)
        }.onFailure { t ->
            Log.w(
                TAG,
                "tryMoveExistingTaskToDisplayByShizuku: IActivityTaskManager move failed: pkg=$packageName taskId=$taskId displayId=$displayId",
                t,
            )
        }.getOrNull() == true
        if (movedByAtm) {
            Log.i(TAG, "Moved existing task to display via IActivityTaskManager: pkg=$packageName taskId=$taskId displayId=$displayId")
            return true
        }

        val candidates = arrayOf(
            "cmd activity task move-to-display $taskId $displayId",
            "cmd activity task move-task-to-display $taskId $displayId",
            "cmd activity move-task $taskId $displayId",
            "am task move-to-display $taskId $displayId",
        )
        for (c in candidates) {
            val r = ShizukuShell.execForText(c)
            if (r.isSuccess) {
                Log.i(TAG, "Moved existing task to display: pkg=$packageName taskId=$taskId displayId=$displayId")
                return true
            }
            Log.d(
                TAG,
                "tryMoveExistingTaskToDisplayByShizuku: move failed: cmd=$c err=${r.exceptionOrNull()?.message}",
            )
        }
        return false
    }

    private fun resolveTaskPackageNameByIActivityTaskManager(taskId: Int): String? {
        val atm = ShizukuServiceManager.getActivityTaskManager()
        val methods = atm.javaClass.methods
        val getTaskInfo = methods.firstOrNull { it.name == "getTaskInfo" && it.parameterTypes.size == 1 }
            ?: return null
        val taskInfo = getTaskInfo.invoke(atm, taskId) ?: return null

        fun readComponentPackage(fieldName: String): String? {
            val f = runCatching { taskInfo.javaClass.getField(fieldName) }.getOrNull()
                ?: runCatching { taskInfo.javaClass.getDeclaredField(fieldName).apply { isAccessible = true } }.getOrNull()
                ?: return null
            val cn = f.get(taskInfo) as? android.content.ComponentName ?: return null
            return cn.packageName
        }

        return readComponentPackage("topActivity")
            ?: readComponentPackage("baseActivity")
            ?: readComponentPackage("realActivity")
    }

    private fun moveTaskToDisplayByIActivityTaskManager(
        taskId: Int,
        displayId: Int,
    ): Boolean {
        val atm = ShizukuServiceManager.getActivityTaskManager()

        val methods = atm.javaClass.methods

        fun tryInvoke(methodName: String, vararg args: Any): Boolean {
            val m = methods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }
                ?: return false
            val result = m.invoke(atm, *args)
            return when (result) {
                null -> true
                is Boolean -> result
                else -> true
            }
        }

        if (tryInvoke("moveTaskToDisplay", taskId, displayId)) return true
        if (tryInvoke("moveRootTaskToDisplay", taskId, displayId)) return true

        val m3 = methods.firstOrNull { it.name == "moveTaskToDisplay" && it.parameterTypes.size == 3 }
        if (m3 != null) {
            val lastIsBoolean = m3.parameterTypes[2] == Boolean::class.javaPrimitiveType
            if (lastIsBoolean) {
                val result = m3.invoke(atm, taskId, displayId, false)
                return (result as? Boolean) ?: true
            }
        }

        val m4 = methods.firstOrNull { it.name == "moveTaskToDisplay" && it.parameterTypes.size == 4 }
        if (m4 != null) {
            val lastIsBoolean = m4.parameterTypes[2] == Boolean::class.javaPrimitiveType || m4.parameterTypes[3] == Boolean::class.javaPrimitiveType
            if (lastIsBoolean) {
                runCatching {
                    val result = m4.invoke(atm, taskId, displayId, false, false)
                    return (result as? Boolean) ?: true
                }
            }
        }

        return false
    }

    private fun findTaskIdForPackageFromDumpsys(
        lines: List<String>,
        packageName: String,
    ): Int? {
        val pkg = packageName.lowercase()
        val pkgComponentNeedle = "$pkg/"
        val taskIdPattern = Pattern.compile("\\btaskId=(\\d+)\\b")
        val taskHashIdPattern = Pattern.compile("Task\\{[^}]*#(\\d+)")

        var bestTaskId: Int? = null
        var inBlock = false
        var currentTaskId: Int? = null
        var blockMatchedPkg = false

        fun flushBlock() {
            if (inBlock && blockMatchedPkg && currentTaskId != null) {
                bestTaskId = currentTaskId
            }
            inBlock = false
            currentTaskId = null
            blockMatchedPkg = false
        }

        for (raw in lines) {
            val line = raw.trim()
            val m = taskIdPattern.matcher(line)
            if (m.find()) {
                flushBlock()
                inBlock = true
                currentTaskId = m.group(1)?.toIntOrNull()
            }

            if (!inBlock) {
                val m2 = taskHashIdPattern.matcher(line)
                if (m2.find()) {
                    flushBlock()
                    inBlock = true
                    currentTaskId = m2.group(1)?.toIntOrNull()
                }
            }

            if (inBlock) {
                val l = line.lowercase()
                if (l.contains(pkgComponentNeedle)) {
                    blockMatchedPkg = true
                }
            }
        }
        flushBlock()
        return bestTaskId
    }
}
