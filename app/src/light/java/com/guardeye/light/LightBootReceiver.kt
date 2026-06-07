package com.guardeye.light

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardeye.Config

class LightBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Config.init(ctx)
            if (Config.enabled) {
                Log.d(TAG, "Boot — restoring LightBotService")
                val svc = Intent(ctx, LightBotService::class.java)
                ctx.startForegroundService(svc)
                if (Config.enabled) {
                    LightAlarmReceiver.scheduleNextAlarm(ctx, Config.intervalMinutes)
                }
            }
        }
    }
    companion object { private const val TAG = "LightBootReceiver" }
}
