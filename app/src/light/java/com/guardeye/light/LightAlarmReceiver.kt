package com.guardeye.light

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.guardeye.Config

/**
 * LightAlarmReceiver — schedules the next capture and triggers LightBotService.
 *
 * Key design decisions (v2.0):
 * - Uses cached PendingIntent (companion object) so cancel() actually works.
 * - Checks SCHEDULE_EXACT_ALARM permission before calling setExactAndAllowWhileIdle,
 *   falls back to setAndAllowWhileIdle to avoid SecurityException on Android 12+.
 * - Uses FLAG_MUTABLE (required by setExactAndAllowWhileIdle on Android 12+).
 * - Schedules the next alarm INSIDE onReceive to form a tight loop.
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

        // Stable request code — must be the same for both schedule and cancel.
        // 2001 is the logical ID for this app's alarm.
        private const val REQUEST_CODE = 2001

        // ── FIX v2.0: Cache the PendingIntent so cancel() actually works ──
        // Without caching, each call to getBroadcast() returns a NEW PI that
        // Android cannot match to the scheduled one → cancel() is a no-op.
        @Volatile
        private var scheduledPi: PendingIntent? = null

        /**
         * Schedule the next capture alarm.
         *
         * Strategy:
         * 1. Check canScheduleExactAlarms() first (Android 12+).
         * 2. If exact alarms are not permitted → use setAndAllowWhileIdle
         *    (FRT recommendation: less intrusive, no system notification).
         * 3. If permitted → try setExactAndAllowWhileIdle with try-catch.
         *    Use try-catch because permission can be revoked between the
         *    canScheduleExactAlarms() check and the actual call (race condition).
         * 4. On SecurityException → fall back to setAndAllowWhileIdle.
         *
         * @param intervalMinutes capture interval in minutes (1-60)
         */
        fun scheduleNextAlarm(ctx: Context, intervalMinutes: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Cancel any previously scheduled alarm first.
            // Uses the SAME PI object that was used to schedule → cancel succeeds.
            cancelAlarm(ctx)

            // Build the Intent and PI.
            val intent = Intent(ctx, LightAlarmReceiver::class.java)
            // FIX: FLAG_MUTABLE is required by setExactAndAllowWhileIdle on Android 12+.
            // FLAG_UPDATE_CURRENT ensures updates to Intent extras are reflected.
            scheduledPi = PendingIntent.getBroadcast(
                ctx,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val triggerAt = SystemClock.elapsedRealtime() + intervalMinutes * 60_000L

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: check permission first.
                if (am.canScheduleExactAlarms()) {
                    // Permission granted — try exact alarm, handle race condition.
                    try {
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            scheduledPi!!
                        )
                        Log.d(TAG, "Alarm scheduled: ${intervalMinutes}min [exact]")
                    } catch (e: SecurityException) {
                        // Permission was revoked between check and call (race condition).
                        Log.w(TAG, "Exact alarm permission revoked: ${e.message}")
                        am.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            scheduledPi!!
                        )
                        Log.d(TAG, "Alarm scheduled: ${intervalMinutes}min [non-exact fallback]")
                    }
                } else {
                    // Permission denied — use non-exact alarm (no notification harassment).
                    // FRT recommendation: default to setAndAllowWhileIdle.
                    am.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        scheduledPi!!
                    )
                    Log.d(TAG, "Alarm scheduled: ${intervalMinutes}min [non-exact, permission denied]")
                }
            } else {
                // Android < 12: no exact alarm permission concept — use exact directly.
                @Suppress("DEPRECATION")
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    scheduledPi!!
                )
                Log.d(TAG, "Alarm scheduled: ${intervalMinutes}min [exact, pre-Android12]")
            }
        }

        /**
         * Cancel the scheduled alarm.
         *
         * FIX v2.0: Uses the SAME cached PI object that was used to schedule.
         * Previous version called getBroadcast() each time, which returns a NEW
         * PendingIntent that Android cannot match to the original → cancel was a no-op.
         */
        fun cancelAlarm(ctx: Context) {
            scheduledPi?.let { pi ->
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pi)
                pi.cancel()
                Log.d(TAG, "Alarm cancelled")
            }
            scheduledPi = null
        }
    }
}
