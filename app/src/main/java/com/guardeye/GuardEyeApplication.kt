package com.guardeye

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GuardEyeApplication : Application() {
    companion object {
        const val CHANNEL_ID = "guardeye_light_bot"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "GuardEye Light",
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.setShowBadge(false)
            ch.setDescription("保持 GuardEye Light 在后台运行")
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }
}
