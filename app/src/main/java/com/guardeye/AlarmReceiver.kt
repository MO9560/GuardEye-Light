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
        const val TAG = "GuardEye.Alarm"
        const val REQUEST_CODE = 1001

        fun scheduleAlarm(ctx: Context, intervalMinutes: Int) {
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
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Alarm scheduled: every $intervalMinutes min at $triggerAt")
        }

        fun cancelAlarm(ctx: Context) {
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
        Log.d(TAG, "Alarm triggered")
        Config.init(ctx)  // init Config in this process before use
        if (!Config.enabled) {
            Log.d(TAG, "Config.enabled=false, skipping capture")
            return
        }
        // Fire CameraService to take one photo
        val camIntent = Intent(ctx, CameraService::class.java).apply {
            action = CameraService.ACTION_CAPTURE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(camIntent)
        } else {
            ctx.startService(camIntent)
        }
        // Re-schedule next alarm
        if (Config.enabled) {
            scheduleAlarm(ctx, Config.intervalMinutes)
        }
    }
}
