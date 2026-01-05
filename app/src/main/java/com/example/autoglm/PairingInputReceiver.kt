package com.example.autoglm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * 通知栏 RemoteInput 输入接收器。
 *
 * **用途**
 * - 接收用户在通知栏输入的 6 位无线调试配对码，并转发给 [PairingService] 执行配对/连接流程。
 *
 * **引用路径（常见）**
 * - `AndroidManifest.xml`：声明 `<receiver android:name=".PairingInputReceiver" ...>`
 * - `PairingService`：构建带 RemoteInput 的通知并指定该 Receiver 为 PendingIntent 目标。
 *
 * **使用注意事项**
 * - Receiver 生命周期极短：只做解析与启动服务，不做耗时工作。
 * - 出于日志安全，会对配对码进行脱敏打印。
 */
class PairingInputReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingService.ACTION_REMOTE_INPUT) return

        Log.i("AutoglmAdb", "PairingInputReceiver.onReceive action=${intent.action}")

        val results = RemoteInput.getResultsFromIntent(intent)
        val pairingCode = results?.getCharSequence(PairingService.EXTRA_PAIRING_CODE)?.toString()?.trim().orEmpty()
        val pairPort = intent.getStringExtra(PairingService.EXTRA_PAIR_PORT)?.trim().orEmpty()

        val masked = if (pairingCode.length <= 2) "**" else "${pairingCode.take(1)}***${pairingCode.takeLast(1)}"
        Log.i("AutoglmAdb", "RemoteInput 收到：pairPort=$pairPort pairingCode=$masked")

        val svc = Intent(context, PairingService::class.java).apply {
            action = PairingService.ACTION_PAIR
            putExtra(PairingService.EXTRA_PAIRING_CODE, pairingCode)
            putExtra(PairingService.EXTRA_PAIR_PORT, pairPort)
        }

        Log.i("AutoglmAdb", "准备启动 PairingService ACTION_PAIR")

        PairingService.startForegroundCompat(context, svc)
    }
}
