package com.guardeye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Config.init(ctx)
        // 触发定时拍照（不会重新启动整个服务，避免双重初始化）
        val svc = Intent(ctx, CameraService::class.java).apply {
            action = CameraService.ACTION_CAPTURE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(svc)
        } else {
            ctx.startService(svc)
        }
    }
}
