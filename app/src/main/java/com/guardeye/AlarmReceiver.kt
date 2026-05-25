package com.guardeye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmReceiver — 定时拍照闹钟触发器
 *
 * 仅负责触发一次拍照请求：
 * - 启动 CameraService.ACTION_CAPTURE（触发拍照，不重启服务）
 * - 不重启 Bot 服务（BotManager 是单例，轮询线程独立于 Service）
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "GuardEye.Alarm"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Log.d(TAG, "Alarm triggered")
        Config.init(ctx)

        if (!Config.enabled) {
            Log.d(TAG, "Monitoring disabled, skipping capture")
            return
        }

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
