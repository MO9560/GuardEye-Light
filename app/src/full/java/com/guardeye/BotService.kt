package com.guardeye

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service — GuardEye v4.0 Telegram bot polling.
 *
 * Routes commands to SentinelService (v4.0 continuous AI monitoring).
 * /start → SentinelService.ACTION_START
 * /stop  → SentinelService.ACTION_STOP
 * /photo → CameraService (one-shot manual capture)
 */
class BotService : LifecycleService() {

    companion object {
        const val TAG = "GuardEye.BotSvc"
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "guardeye_bot_channel"
        const val ACTION_START = "com.guardeye.action.BOT_START"
        const val ACTION_STOP  = "com.guardeye.action.BOT_STOP"
    }

    private var isRunning = false
    private var pollingJob: Job? = null
    private val cmdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()
        Log.d(TAG, "BotService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startPolling()
            ACTION_STOP  -> { isRunning = false; stopSelf() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        isRunning = false
        Log.d(TAG, "BotService destroyed")
        super.onDestroy()
    }

    // ── Polling ───────────────────────────────────────────────────

    private fun startPolling() {
        if (isRunning) {
            Log.d(TAG, "Already polling")
            return
        }
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("GuardEye Bot 启动中..."))
        pollingJob = cmdScope.launch {
            Log.d(TAG, "Polling loop started")
            while (isActive && isRunning) {
                val token = Config.botToken
                val chatId = Config.chatId
                if (token.isBlank() || chatId.isBlank()) {
                    updateNotification("请先配置 Token 和 Chat ID")
                    delay(10_000)
                    continue
                }
                val result = TelegramBot.fetchUpdates(token, Config.botOffset)
                result.onSuccess { updates ->
                    Log.d(TAG, "Received ${updates.size} updates")
                    if (updates.isNotEmpty()) updateNotification("${updates.size} 条新消息")
                    updates.forEach { update ->
                        Config.botOffset = update.updateId + 1
                        cmdScope.launch(NonCancellable) {
                            handleCommand(update.text, update.chatId)
                        }
                    }
                }
                result.onFailure {
                    Log.e(TAG, "Poll error: ${it.message}")
                }
                delay(500)
            }
            Log.d(TAG, "Polling loop ended")
        }
        updateNotification("🤖 Bot 已连接")
        Log.i(TAG, "Polling started")
    }

    // ── Command handler ────────────────────────────────────────────

    private suspend fun handleCommand(text: String, chatId: String) = withContext(Dispatchers.IO) {
        val token = Config.botToken
        val cfgChatId = Config.chatId

        // Authorization
        if (cfgChatId.isNotBlank() && chatId != cfgChatId) {
            TelegramBot.sendText(token, chatId, "❌ 未授权用户")
            return@withContext
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        when {
            text.startsWith("/start") -> {
                Config.enabled = true
                val intent = Intent(this@BotService, SentinelService::class.java).apply {
                    action = SentinelService.ACTION_START
                }
                startForegroundService(intent)
                TelegramBot.sendText(token, chatId, """
                    ✅ *GuardEye v4.0 已启动*

                    🛡️ 持续 AI 监控
                    🔍 AI 检测: ${ if (Config.detectionEnabled) "开启" else "关闭" }
                    🎬 告警模式: ${Config.alertMode.uppercase()}
                    🐛 调试: ${ if (Config.debugMode) "开启" else "关闭" }
                    🤖 v${BuildConfig.VERSION_NAME}

                    📋 /photo /status /stop /mode
                """.trimIndent())
            }

            text.startsWith("/stop") -> {
                Config.enabled = false
                val intent = Intent(this@BotService, SentinelService::class.java).apply {
                    action = SentinelService.ACTION_STOP
                }
                startForegroundService(intent)
                TelegramBot.sendText(token, chatId, "⏸ *GuardEye 已停止*")
            }

            text.startsWith("/mode") -> {
                val mode = text.removePrefix("/mode").trim().lowercase()
                when (mode) {
                    "photo" -> {
                        Config.alertMode = "photo"
                        SentinelService.instance?.handleAction("set_mode_photo")
                        TelegramBot.sendText(token, chatId, "📸 告警模式: 图片（检测到目标发单张图）")
                    }
                    "video" -> {
                        Config.alertMode = "video"
                        SentinelService.instance?.handleAction("set_mode_video")
                        TelegramBot.sendText(token, chatId, "🎬 告警模式: 视频（检测到目标发最近片段）")
                    }
                    "" -> {
                        TelegramBot.sendText(token, chatId,
                            "📋 当前模式: ${Config.alertMode.uppercase()}\n用法: /mode photo | /mode video")
                    }
                    else -> {
                        TelegramBot.sendText(token, chatId, "📋 用法: /mode photo | /mode video")
                    }
                }
            }

            text.startsWith("/photo") -> {
                TelegramBot.sendText(token, chatId, "📸 正在拍照，请稍候...")
                val intent = Intent(this@BotService, CameraService::class.java).apply {
                    action = CameraService.ACTION_CAPTURE
                    putExtra(CameraService.EXTRA_SOURCE, CameraService.SOURCE_MANUAL)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }

            text.startsWith("/status") -> {
                val status = buildStatusText()
                TelegramBot.sendText(token, chatId, status)
            }

            text.startsWith("/interval") -> {
                val mins = text.removePrefix("/interval").trim().toIntOrNull()
                if (mins != null && mins in 1..60) {
                    Config.intervalMinutes = mins
                    TelegramBot.sendText(token, chatId, "⏱ 间隔已设为 *${mins} 分钟*")
                } else {
                    TelegramBot.sendText(token, chatId, "📋 用法: /interval 1-60")
                }
            }

            text.startsWith("/detect") -> {
                val enabled = !text.contains("off")
                Config.detectionEnabled = enabled
                TelegramBot.sendText(token, chatId, "🔍 AI 检测: ${ if (enabled) "✅ 开启" else "❌ 关闭" }")
            }

            text.startsWith("/debug") -> {
                val enabled = !text.contains("off")
                Config.debugMode = enabled
                TelegramBot.sendText(token, chatId, "🐛 调试: ${ if (enabled) "✅ 开启" else "❌ 关闭" }")
            }

            text.startsWith("/test") -> {
                TelegramBot.sendText(token, chatId, "🤖 GuardEye v4.0 运行正常！\n时间: $timestamp\nChatID: $chatId")
            }

            else -> {
                TelegramBot.sendText(token, chatId, "📖 未知命令: $text\n\n📋 /start /stop /photo /status /mode /test")
            }
        }
    }

    private fun buildStatusText(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val modelFile = java.io.File(filesDir, "yolov8n.tflite")

        val debug = if (Config.debugMode) """
────────────────────
🧠 模型: ${ if (modelFile.exists()) "✅ 已加载" else "❌ 未加载" }
📡 offset: ${Config.botOffset}
🤖 版本: ${BuildConfig.VERSION_NAME}
⏰ 本地时间: $now
        """.trimIndent() else ""

        return """
📊 *GuardEye v4.0 状态*
────────────────────
🤖 Bot: ✅ 运行中
🛡️ 监控: ${ if (Config.enabled) "✅ 运行中" else "⏸ 已停止" }
🎬 告警模式: ${Config.alertMode.uppercase()}
🔍 AI 检测: ${ if (Config.detectionEnabled) "✅ 开启" else "❌ 关闭" }
🐛 调试: ${ if (Config.debugMode) "✅ 开启" else "❌ 关闭" }
$debug
        """.trimIndent()
    }

    // ── Notification ───────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("🛡️ GuardEye Bot")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val n = buildNotification(text)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GuardEye Bot",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bot service foreground notification"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
