package com.guardeye.light

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
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

        // Re-schedule the next alarm immediately so captures repeat
        val interval = Config.intervalMinutes
        scheduleAlarm(ctx, interval)
    }

    companion object {
        private const val TAG = "LightAlarmReceiver"

        fun scheduleAlarm(ctx: Context, intervalMinutes: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + intervalMinutes * 60 * 1000L

            // Use setAlarmClock — highest priority, always fires even in Doze/Deep Sleep
            val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, pi)
            am.setAlarmClock(alarmInfo, pi)
            Log.d(TAG, "Alarm scheduled (setAlarmClock): in ${intervalMinutes}min")
        }

        fun cancelAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            Log.d(TAG, "Alarm cancelled")
        }

        private const val REQUEST_CODE = 2001
    }
}
