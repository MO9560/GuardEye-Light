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
 * Foreground Service that long-polls Telegram for commands and routes them to handleCommand().
 *
 * Lifecycle:
 *   startService(ACTION_START) → starts polling loop
 *   startService(ACTION_STOP)  → stops polling, self-destroy
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
    /** Dedicated scope for command handlers — survives service lifecycle */
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
        pollingJob?.cancel()       // 只取消轮询循环
        // cmdScope.cancel() 先注释掉，让正在处理的命令完成（先跑通再优化）
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
        startForeground(NOTIFICATION_ID, buildNotification("🔗 GuardEye Bot 启动中..."))
        // 用 cmdScope 启动轮询，避免 lifecycleScope 被取消时轮询停止
        pollingJob = cmdScope.launch {
            Log.d(TAG, "Polling loop started, isActive=${isActive}")
            while (isActive && isRunning) {
                val token = Config.botToken
                val chatId = Config.chatId
                Log.d(TAG, "Polling... token=${token.take(10)}..., chatId=$chatId, offset=${Config.botOffset}")
                if (token.isBlank() || chatId.isBlank()) {
                    updateNotification("⚠️ 未配置 Token 或 Chat ID")
                    delay(10_000)
                    continue
                }
                val result = TelegramBot.fetchUpdates(token, Config.botOffset)
                result.onSuccess { updates ->
                    Log.d(TAG, "Received ${updates.size} updates")
                    if (updates.isNotEmpty()) updateNotification("📨 ${updates.size} 条新消息")
                    updates.forEach { update ->
                        Config.botOffset = update.updateId + 1
                        // Use NonCancellable so commands finish even if lifecycle shuts down
                        cmdScope.launch(NonCancellable) {
                            Log.d(TAG, "Handling command: ${update.text}")
                            handleCommand(update.text, update.chatId)
                        }
                    }
                }
                result.onFailure {
                    Log.e(TAG, "Poll error: ${it.message}")  // 改用 e，方便排查
                }
                delay(500)
            }
            Log.d(TAG, "Polling loop ended")
        }
        updateNotification("✅ Bot 已连接")
        Log.i(TAG, "Polling started")
    }

    // ── Command handler ────────────────────────────────────────────

    private suspend fun handleCommand(text: String, chatId: String) = withContext(Dispatchers.IO) {
        val token = Config.botToken
        val cfgChatId = Config.chatId

        // Authorization check
        if (cfgChatId.isNotBlank() && chatId != cfgChatId) {
            TelegramBot.sendText(token, chatId, "⚠️ 未授权用户")
            return@withContext
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        when {
            text.startsWith("/start") -> {
                Config.enabled = true
                TelegramBot.sendText(token, chatId, """
                    ✅ *GuardEye 已启动*
                    
                    📸 监控：${if (Config.enabled) "运行中" else "已停止"}
                    ⏱ 间隔：${Config.intervalMinutes} 分钟
                    🔍 AI识别：${if (Config.detectionEnabled) "开启" else "关闭"}
                    🐛 调试：${if (Config.debugMode) "开启" else "关闭"}
                    🤖 v${BuildConfig.VERSION_NAME}
                    
                    📋 /photo /status /stop
                """.trimIndent())
            }

            text.startsWith("/stop") -> {
                Config.enabled = false
                TelegramBot.sendText(token, chatId, "⏸ *GuardEye 已停止*")
                // Stop camera alarm too
                AlarmReceiver.cancelAlarm(this@BotService)
            }

            text.startsWith("/photo") -> {
                TelegramBot.sendText(token, chatId, "📸 正在拍照，请稍候...")
                // Trigger CameraService immediately
                val intent = Intent(this@BotService, CameraService::class.java).apply {
                    action = CameraService.ACTION_CAPTURE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }

            text.startsWith("/status") -> {
                pushStatus(token, chatId)
            }

            text.startsWith("/interval") -> {
                val mins = text.removePrefix("/interval").trim().toIntOrNull()
                if (mins != null && mins in 1..10) {
                    Config.intervalMinutes = mins
                    AlarmReceiver.scheduleAlarm(this@BotService, mins)
                    TelegramBot.sendText(token, chatId, "⏱ 拍摄间隔已设为 *${mins} 分钟*")
                } else {
                    TelegramBot.sendText(token, chatId, "📖 用法：`/interval 1-10`")
                }
            }

            text.startsWith("/detect") -> {
                val enabled = !text.contains("off")
                Config.detectionEnabled = enabled
                TelegramBot.sendText(token, chatId, "🔍 AI识别：${if (enabled) "✅ 开启" else "❌ 关闭"}")
            }

            text.startsWith("/debug") -> {
                val enabled = !text.contains("off")
                Config.debugMode = enabled
                TelegramBot.sendText(token, chatId, "🐛 调试模式：${if (enabled) "✅ 开启" else "❌ 关闭"}")
            }

            text.startsWith("/test") -> {
                TelegramBot.sendText(token, chatId, "🤖 Bot 在线！\n时间：$timestamp\nchatId: $chatId")
            }

            else -> {
                TelegramBot.sendText(token, chatId, "📖 未知指令：$text\n\n📋 /start /stop /photo /status")
            }
        }
    }

    private suspend fun pushStatus(token: String, chatId: String) = withContext(Dispatchers.IO) {
        val modelFile = java.io.File(filesDir, "yolov8n.tflite")
        val battery = getBatteryLevel()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val debug = if (Config.debugMode) """
            ─────────────────────
            🧠 模型：${if (modelFile.exists()) "✅ 已加载" else "❌ 未加载"}
            📡 offset：${Config.botOffset}
            🔋 电量：$battery%
            ⏰ 本地时间：$now
        """.trimIndent() else ""

        TelegramBot.sendText(token, chatId, """
            📊 *GuardEye 状态*
            ─────────────────────
            🤖 Bot：✅ 运行中
            📸 监控：${if (Config.enabled) "运行中" else "已停止"}
            ⏱ 间隔：${Config.intervalMinutes} 分钟
            🔍 AI识别：${if (Config.detectionEnabled) "✅ 开启" else "❌ 关闭"}
            🐛 调试：${if (Config.debugMode) "✅ 开启" else "❌ 关闭"}
            ─────────────────────$debug
        """.trimIndent())
    }

    private fun getBatteryLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 0
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
        val n = buildNotification(text)
        try {
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
