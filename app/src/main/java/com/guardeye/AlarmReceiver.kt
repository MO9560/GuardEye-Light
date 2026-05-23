package com.guardeye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // 触发定时拍照
        val svc = Intent(ctx, CameraService::class.java).apply {
            action = CameraService.ACTION_CAPTURE
        }
        ctx.startService(svc)

        // 重新调度下一次
        Config.init(ctx)
        val svc2 = Intent(ctx, CameraService::class.java).apply {
            action = CameraService.ACTION_START
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc2)
        } else {
            ctx.startService(svc2)
        }
    }
}
