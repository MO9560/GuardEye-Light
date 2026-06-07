package com.guardeye.light

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.guardeye.Config

/**
 * AlarmReceiver — schedules the next capture and triggers LightBotService.
 *
 * Uses AlarmManager.setExactAndAllowWhileIdle for reliable 1-minute interval
 * triggering even in Doze mode.  The next alarm is scheduled inside onReceive
 * immediately after triggering the capture, forming a tight loop.
 */
class LightAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Config.init(ctx)
        if (!Config.enabled) return

        Log.d(TAG, "Alarm fired — triggering capture")

        // Start LightBotService to handle capture + upload
        val i = Intent(ctx, LightBotService::class.java).apply {
            action = ACTION_CAPTURE
        }
        ctx.startForegroundService(i)

        // Schedule the next alarm immediately (capture → upload → schedule next)
        val interval = Config.intervalMinutes
        scheduleNextAlarm(ctx, interval)
    }

    companion object {
        private const val TAG = "LightAlarmReceiver"
        private const val REQUEST_CODE = 2001

        /**
         * Schedule the next capture alarm using setExactAndAllowWhileIdle.
         * Fires reliably even in Doze mode (unless device is in full standby).
         */
        fun scheduleNextAlarm(ctx: Context, intervalMinutes: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + intervalMinutes * 60_000L

            // setExactAndAllowWhileIdle — exact timing, allowed in Doze
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pi
            )
            Log.d(TAG, "Next alarm scheduled in ${intervalMinutes}min")
        }

        fun cancelAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            Log.d(TAG, "Alarm cancelled")
        }
    }
}
