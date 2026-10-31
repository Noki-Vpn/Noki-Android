package com.noki.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

internal class VpnScreenStateMonitor(
    private val context: Context,
    private val onScreenStateChanged: (Boolean) -> Unit,
) {
    private var registered = false
    private var screenOn = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> updateScreenState(true)
                Intent.ACTION_SCREEN_OFF -> updateScreenState(false)
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> updateScreenState(isInteractive())
            }
        }
    }

    fun isInteractive(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    fun start() {
        if (registered) return
        screenOn = isInteractive()
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            },
        )
        registered = true
    }

    private fun updateScreenState(next: Boolean) {
        if (screenOn == next) return
        screenOn = next
        onScreenStateChanged(next)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
