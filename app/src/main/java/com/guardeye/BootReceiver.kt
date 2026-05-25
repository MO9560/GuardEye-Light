package com.guardeye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restores monitoring after device boot.
 * Fires only if Config.enabled == true.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "GuardEye.Boot"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "Boot completed")

        Config.init(ctx)

        if (!Config.enabled || !Config.isConfigured) {
            Log.d(TAG, "GuardEye not enabled or not configured, skipping start")
            return
        }

        // Restart BotService
        val botIntent = Intent(ctx, BotService::class.java).apply {
            action = BotService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(botIntent)
        } else {
            ctx.startService(botIntent)
        }

        // Restart alarm schedule
        AlarmReceiver.scheduleAlarm(ctx, Config.intervalMinutes)
        Log.i(TAG, "GuardEye restored after boot")
    }
}
