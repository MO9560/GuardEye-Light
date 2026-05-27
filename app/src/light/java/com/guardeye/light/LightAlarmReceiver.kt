package com.guardeye.light

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.guardeye.Config

class LightAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Config.init(ctx)
        if (!Config.enabled) return

        Log.d(TAG, "Alarm fired — starting LightBotService capture")
        val i = Intent(ctx, LightBotService::class.java).apply {
            action = LightBotService.ACTION_CAPTURE
        }
        ctx.startForegroundService(i)
    }

    companion object {
        private const val TAG = "LightAlarmReceiver"

        fun scheduleAlarm(ctx: Context, intervalMinutes: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + intervalMinutes * 60 * 1000L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.d(TAG, "Alarm scheduled (exact): in ${intervalMinutes}min")
                } else {
                    am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.w(TAG, "Alarm scheduled (inexact — SCHEDULE_EXACT_ALARM not granted): in ${intervalMinutes}min")
                }
            } else {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.d(TAG, "Alarm scheduled: in ${intervalMinutes}min")
            }
        }

        fun cancelAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            am.cancel(pi)
            Log.d(TAG, "Alarm cancelled")
        }

        private const val REQUEST_CODE = 2001
    }
}
