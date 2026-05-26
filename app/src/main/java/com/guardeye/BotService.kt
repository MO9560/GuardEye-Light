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
 *   startService(ACTION_START) -> starts polling loop
 *   startService(ACTION_STOP)  -> stops polling, self-destroy
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
        startForeground(NOTIFICATION_ID, buildNotification("GuardEye Bot starting..."))
        pollingJob = cmdScope.launch {
            Log.d(TAG, "Polling loop started, isActive=${isActive}")
            while (isActive && isRunning) {
                val token = Config.botToken
                val chatId = Config.chatId
                Log.d(TAG, "Polling... token=${token.take(10)}..., chatId=$chatId, offset=${Config.botOffset}")
                if (token.isBlank() || chatId.isBlank()) {
                    updateNotification("Token or Chat ID not configured")
                    delay(10_000)
                    continue
                }
                val result = TelegramBot.fetchUpdates(token, Config.botOffset)
                result.onSuccess { updates ->
                    Log.d(TAG, "Received ${updates.size} updates")
                    if (updates.isNotEmpty()) updateNotification("${updates.size} new messages")
                    updates.forEach { update ->
                        Config.botOffset = update.updateId + 1
                        cmdScope.launch(NonCancellable) {
                            Log.d(TAG, "Handling command: ${update.text}")
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
        updateNotification("Bot connected")
        Log.i(TAG, "Polling started")
    }

    // ── Command handler ────────────────────────────────────────────

    private suspend fun handleCommand(text: String, chatId: String) = withContext(Dispatchers.IO) {
        val token = Config.botToken
        val cfgChatId = Config.chatId

        // Authorization check
        if (cfgChatId.isNotBlank() && chatId != cfgChatId) {
            TelegramBot.sendText(token, chatId, "Unauthorized user")
            return@withContext
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        when {
            text.startsWith("/start") -> {
                Config.enabled = true
                AlarmReceiver.scheduleAlarm(this@BotService, Config.intervalMinutes)
                TelegramBot.sendText(token, chatId, """
                    ✅ *GuardEye Started*

                    📸 Monitoring: ${if (Config.enabled) "Running" else "Stopped"}
                    ⏱ Interval: ${Config.intervalMinutes} minutes
                    🔍 AI Detection: ${if (Config.detectionEnabled) "On" else "Off"}
                    🐛 Debug: ${if (Config.debugMode) "On" else "Off"}
                    🤖 v${BuildConfig.VERSION_NAME}

                    📋 /photo /status /stop
                """.trimIndent())
            }

            text.startsWith("/stop") -> {
                Config.enabled = false
                TelegramBot.sendText(token, chatId, "⏸ *GuardEye Stopped*")
                AlarmReceiver.cancelAlarm(this@BotService)
            }

            text.startsWith("/photo") -> {
                TelegramBot.sendText(token, chatId, "📸 Taking photo, please wait...")
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
                pushStatus(token, chatId)
            }

            text.startsWith("/interval") -> {
                val mins = text.removePrefix("/interval").trim().toIntOrNull()
                if (mins != null && mins in 1..10) {
                    Config.intervalMinutes = mins
                    AlarmReceiver.scheduleAlarm(this@BotService, mins)
                    TelegramBot.sendText(token, chatId, "⏱ Interval set to *${mins} minutes*")
                } else {
                    TelegramBot.sendText(token, chatId, "Usage: `/interval 1-10`")
                }
            }

            text.startsWith("/detect") -> {
                val enabled = !text.contains("off")
                Config.detectionEnabled = enabled
                TelegramBot.sendText(token, chatId, "🔍 AI Detection: ${if (enabled) "✅ On" else "❌ Off"}")
            }

            text.startsWith("/debug") -> {
                val enabled = !text.contains("off")
                Config.debugMode = enabled
                TelegramBot.sendText(token, chatId, "🐛 Debug Mode: ${if (enabled) "✅ On" else "❌ Off"}")
            }

            text.startsWith("/test") -> {
                TelegramBot.sendText(token, chatId, "🤖 Bot online!\nTime: $timestamp\nchatId: $chatId")
            }

            else -> {
                TelegramBot.sendText(token, chatId, "📖 Unknown: $text\n\n📋 /start /stop /photo /status")
            }
        }
    }

    private suspend fun pushStatus(token: String, chatId: String) = withContext(Dispatchers.IO) {
        val modelFile = java.io.File(filesDir, "yolov8n.tflite")
        val battery = getBatteryLevel()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        fun fmtTime(ts: Long): String =
            if (ts > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
            else "—"

        val lastInterval = fmtTime(Config.lastIntervalCaptureTime)
        val lastManual  = fmtTime(Config.lastManualCaptureTime)

        val debug = if (Config.debugMode) """
            ─────────────────────
            🧠 Model: ${ if (modelFile.exists()) "✅ Loaded" else "❌ Not loaded" }
            📡 offset: ${Config.botOffset}
            🔋 Battery: $battery%
            ⏰ Local time: $now
        """.trimIndent() else ""

        TelegramBot.sendText(token, chatId, """
            📊 *GuardEye Status*
            ─────────────────────
            🤖 Bot: ✅ Running
            📸 Monitoring: ${ if (Config.enabled) "Running" else "Stopped" }
            ⏱ Interval: ${Config.intervalMinutes} minutes
            🔍 AI Detection: ${ if (Config.detectionEnabled) "✅ On" else "❌ Off" }
            🐛 Debug: ${ if (Config.debugMode) "✅ On" else "❌ Off" }
            ─────────────────────
            ⏰ Last auto capture: $lastInterval
            📷 Last manual capture: $lastManual
            $debug
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
