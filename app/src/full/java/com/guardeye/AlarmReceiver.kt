package com.guardeye

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Schedules and receives timed capture alarms.
 * On receipt → starts CameraService to take one photo.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GuardEye.Alarm"
        private const val REQUEST_CODE = 1001
        const val EXTRA_INTERVAL_MILLIS = "interval_millis"

        fun scheduleAlarm(ctx: Context, intervalMinutes: Int) {
            Config.init(ctx) // init before reading Config values
            val intent = Intent(ctx, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intervalMs = intervalMinutes * 60_000L
            val triggerAt = System.currentTimeMillis() + intervalMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.d(TAG, "Alarm scheduled (exact): every $intervalMinutes min at $triggerAt")
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.w(TAG, "Alarm scheduled (inexact — SCHEDULE_EXACT_ALARM not granted): every $intervalMinutes min")
                }
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.d(TAG, "Alarm scheduled: every $intervalMinutes min at $triggerAt")
            }
        }

        fun cancelAlarm(ctx: Context) {
            Config.init(ctx)
            val intent = Intent(ctx, AlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(pi)
            Log.d(TAG, "Alarm cancelled")
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Config.init(ctx) // CRITICAL: init before reading any Config values
        Log.d(TAG, "Alarm fired — enabled=${Config.enabled}, interval=${Config.intervalMinutes}")

        if (!Config.enabled) {
            Log.d(TAG, "Skipping capture (disabled)")
            return
        }

        // Fire CameraService to take one photo
        val camIntent = Intent(ctx, CameraService::class.java).apply {
            action = CameraService.ACTION_CAPTURE
            putExtra(CameraService.EXTRA_SOURCE, CameraService.SOURCE_INTERVAL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(camIntent)
        } else {
            ctx.startService(camIntent)
        }

        // Re-schedule next alarm
        scheduleAlarm(ctx, Config.intervalMinutes)
    }
}
