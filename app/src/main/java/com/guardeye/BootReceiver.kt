package com.guardeye

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BootReceiver — 开机自启
 *
 * 如果开机前监控是开启的（Config.enabled = true），则启动：
 * 1. BotForegroundService — 确保 Bot 链路永远在线
 * 2. CameraService — 如果需要定时拍照
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "GuardEye.Boot"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed, Config.enabled=${Config.enabled}")
            Config.init(ctx)

            if (!Config.enabled) {
                Log.d(TAG, "Monitoring was disabled, not auto-starting")
                return
            }

            // 先启动 Bot 服务（即使没有拍照，Bot 也应该在线）
            startBotService(ctx)

            // 然后启动相机服务
            if (CameraService.isInstanceRunning || shouldStartCamera()) {
                startCameraService(ctx)
            }
        }
    }

    private fun shouldStartCamera(): Boolean {
        // 如果需要定时拍照，启动相机服务
        // 这里用 Config.enabled 作为判断
        return Config.enabled
    }

    private fun startBotService(ctx: Context) {
        try {
            val svc = Intent(ctx, BotForegroundService::class.java).apply {
                action = BotForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc)
            } else {
                ctx.startService(svc)
            }
            Log.d(TAG, "BotForegroundService started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BotForegroundService: ${e.message}")
        }
    }

    private fun startCameraService(ctx: Context) {
        try {
            val svc = Intent(ctx, CameraService::class.java).apply {
                action = CameraService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc)
            } else {
                ctx.startService(svc)
            }
            Log.d(TAG, "CameraService started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraService: ${e.message}")
        }
    }
}
