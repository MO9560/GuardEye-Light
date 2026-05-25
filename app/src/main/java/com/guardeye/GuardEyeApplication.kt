package com.guardeye

import android.app.Application
import android.util.Log

/**
 * GuardEye Application 入口
 *
 * 保证 BotManager 在任何 Activity/Service 之前初始化，
 * 避免 Config 未 init 导致 Bot 静默失败。
 */
class GuardEyeApplication : Application() {

    companion object {
        const val TAG = "GuardEye.App"
    }

    override fun onCreate() {
        super.onCreate()
        // 最先初始化 BotManager（单例，不依赖任何其他组件）
        BotManager.init(this)
        Log.d(TAG, "GuardEye Application started. BotManager initialized.")
    }
}
