package com.guardeye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Config.enabled) {
                val svc = Intent(ctx, CameraService::class.java).apply {
                    action = CameraService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(svc)
                } else {
                    ctx.startService(svc)
                }
            }
        }
    }
}
