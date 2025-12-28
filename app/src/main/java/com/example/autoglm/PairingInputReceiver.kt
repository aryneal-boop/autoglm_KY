package com.example.autoglm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

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
