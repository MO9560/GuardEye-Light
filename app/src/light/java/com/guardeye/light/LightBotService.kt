package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.guardeye.BuildConfig
import com.guardeye.Config
import com.guardeye.R
import com.guardeye.TelegramBot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LightBotService — GuardEye Light (HM 1S compatible)
 * Merges Telegram polling + Camera1 capture into one foreground service.
 * No AI, no TFLite, no CameraX. Works on minSdk 19.
 */
class LightBotService : LifecycleService() {

    // ── Telegram polling ──────────────────────────────────────────────
    private var pollingJob: Job? = null
    private val cmdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Camera ───────────────────────────────────────────────────────
    private var camera: Camera? = null
    private val cameraHandler = Handler(Looper.getMainLooper())

    // ── Foreground notification ─────────────────────────────────────
    private val CHANNEL_ID = "guardeye_light_bot"
    private val NOTIF_ID = 2001

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_CAPTURE -> captureAndSend()
            ACTION_STOP    -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else           -> startPolling()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        cmdScope.cancel()
        releaseCamera()
        super.onDestroy()
    }

    // ── Telegram Polling ─────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = cmdScope.launch {
            while (true) {
                try {
                    val token = Config.botToken
                    val chatId = Config.chatId
                    if (token.isBlank() || chatId.isBlank()) {
                        delay(30_000)
                        continue
                    }

                    val updates = TelegramBot.fetchUpdates(token, Config.botOffset)
                    for (update in updates) {
                        Config.botOffset = update.updateId + 1
                        cmdScope.launch(NonCancellable) {
                            handleCommand(update.text ?: "", update.chatId)
                        }
                    }
                    if (updates.isEmpty()) delay(1500)
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                    delay(10_000)
                }
            }
        }
    }

    private fun handleCommand(text: String, chatId: String) {
        if (text != "/start" && text != "/stop" && text != "/photo" &&
            text != "/status" && text != "/test" &&
            !text.startsWith("/interval") &&
            !text.startsWith("/debug")) return

        val token = Config.botToken
        val chatId = Config.chatId

        when {
            text == "/start" -> {
                Config.enabled = true
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                TelegramBot.sendText(token, chatId, "✅ GuardEye Light 已启动\n间隔：${Config.intervalMinutes}分钟")
            }
            text == "/stop" -> {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                TelegramBot.sendText(token, chatId, "⏸ 已停止")
            }
            text == "/photo" -> {
                TelegramBot.sendText(token, chatId, "📸 正在拍照...")
                captureAndSend(source = "manual")
            }
            text == "/status" -> {
                val status = buildStatusText()
                TelegramBot.sendText(token, chatId, status)
            }
            text.startsWith("/interval ") -> {
                val mins = text.removePrefix("/interval ").trim().toIntOrNull()
                if (mins != null && mins in 1..60) {
                    Config.intervalMinutes = mins
                    if (Config.enabled) LightAlarmReceiver.scheduleAlarm(this, mins)
                    TelegramBot.sendText(token, chatId, "⏱ 间隔已设为 $mins 分钟")
                } else {
                    TelegramBot.sendText(token, chatId, "用法：/interval 1-60")
                }
            }
            text.startsWith("/debug") -> {
                val mode = text.removePrefix("/debug").trim().lowercase()
                Config.debugMode = (mode.isEmpty() || mode == "on")
                TelegramBot.sendText(token, chatId,
                    if (Config.debugMode) "🐛 调试模式开启" else "🐛 调试模式关闭")
            }
            text == "/test" -> {
                TelegramBot.sendText(token, chatId, "✅ GuardEye Light 正常运行\n版本：${BuildConfig.VERSION_NAME}")
            }
        }
    }

    // ── Camera1 Capture ───────────────────────────────────────────────

    fun captureAndSend(source: String = "interval") {
        cameraHandler.post {
            try {
                camera = Camera.open()
                val params = camera!!.parameters
                params.pictureFormat = ImageFormat.JPEG
                params.setPreviewSize(1280, 720)
                camera!!.parameters = params
                camera!!.setErrorCallback { _, err ->
                    Log.e(TAG, "Camera error: $err")
                }
                camera!!.takePicture(null, null) { data, cam ->
                    camera?.release()
                    camera = null
                    if (data != null) {
                        processAndSend(data, source)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera capture failed", e)
                camera?.release()
                camera = null
            }
        }
    }

    private fun processAndSend(jpegData: ByteArray, source: String) {
        cmdScope.launch {
            try {
                val token = Config.botToken
                val chatId = Config.chatId
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val battery = getBatteryLevel()

                // Record last capture time
                if (source == "interval") {
                    Config.lastIntervalCaptureTime = System.currentTimeMillis()
                } else {
                    Config.lastManualCaptureTime = System.currentTimeMillis()
                }

                val caption = buildCaption(source, now, battery)

                val sent = TelegramBot.sendPhoto(token, chatId, jpegData, caption)
                if (!sent) Log.e(TAG, "sendPhoto failed")
            } catch (e: Exception) {
                Log.e(TAG, "processAndSend error", e)
            }
        }
    }

    private fun buildCaption(source: String, time: String, battery: Int): String {
        val srcLabel = if (source == "interval") "自动拍照" else "手动拍照"
        var caption = "📸 GuardEye Light\n${time}\n来源：$srcLabel\n电量：$battery%"

        if (Config.debugMode) {
            caption += "\n─────────────"
            caption += "\n🤖 Bot：✅"
            caption += "\n🔋 电量：$battery%"
            caption += "\n⏱ 拍照间隔：${Config.intervalMinutes}min"
            caption += "\n📐 分辨率：1280×720"
            caption += "\n🐛 调试：✅"
            caption += "\n🤖 版本：${BuildConfig.VERSION_NAME}"
        }
        return caption
    }

    private fun buildStatusText(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val interval = if (Config.lastIntervalCaptureTime > 0)
            now.format(Date(Config.lastIntervalCaptureTime)) else "无"
        val manual = if (Config.lastManualCaptureTime > 0)
            now.format(Date(Config.lastManualCaptureTime)) else "无"

        return buildString {
            append("📊 GuardEye Light 状态\n")
            append("─────────────────\n")
            append("🤖 Bot：${if (Config.enabled) "✅ 运行中" else "⏸ 已停止"}\n")
            append("⏱ 拍照间隔：${Config.intervalMinutes} 分钟\n")
            append("🐛 调试：${if (Config.debugMode) "✅ 开启" else "❌ 关闭"}\n")
            append("─────────────────\n")
            append("🕐 最近自动：$interval\n")
            append("📷 最近手动：$manual\n")
            if (Config.debugMode) {
                append("─────────────────\n")
                append("🔋 电量：${getBatteryLevel()}%\n")
                append("📐 分辨率：1280×720\n")
                append("🤖 版本：${BuildConfig.VERSION_NAME}\n")
                append("🤖 模型：N/A（Light版）")
            }
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.content.BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }

    private fun releaseCamera() {
        try { camera?.release() } catch (e: Exception) { }
        camera = null
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "GuardEye Light", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("📸 GuardEye Light")
        .setContentText(if (Config.enabled) "监控中" else "已停止")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val TAG = "LightBotService"
        const val ACTION_CAPTURE = "com.guardeye.light.ACTION_CAPTURE"
        const val ACTION_STOP    = "com.guardeye.light.ACTION_STOP"
    }
}
