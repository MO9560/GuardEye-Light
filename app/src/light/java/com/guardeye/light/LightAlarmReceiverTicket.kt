package com.guardeye.light

import com.guardeye.light.LightBotService

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.guardeye.Config

class LightAlarmReceiverTicket : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Config.init(ctx)
        if (!Config.ticketEnabled) return

        Log.d(TAG, "Ticket alarm fired")
        val i = Intent(ctx, LightBotService::class.java).apply {
            action = LightBotService.ACTION_CHECK_TICKET
        }
        ctx.startForegroundService(i)

        // Re-schedule next alarm
        val interval = Config.ticketIntervalMinutes
        scheduleAlarm(ctx, interval)
    }

    companion object {
        private const val TAG = "LightAlarmReceiverTicket"
        private const val REQUEST_CODE = 2002

        fun scheduleAlarm(ctx: Context, intervalMinutes: Int) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, LightAlarmReceiverTicket::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + intervalMinutes * 60 * 1000L
            val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, pi)
            am.setAlarmClock(alarmInfo, pi)
            Log.d(TAG, "Ticket alarm scheduled: in ${intervalMinutes}min")
        }

        fun cancelAlarm(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(ctx, LightAlarmReceiverTicket::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            Log.d(TAG, "Ticket alarm cancelled")
        }
    }
}
