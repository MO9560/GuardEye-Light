package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.guardeye.BuildConfig
import com.guardeye.Config
import com.guardeye.TelegramBot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LightBotService — GuardEye Light (CameraX)
 * Merges Telegram polling + CameraX capture into one foreground service.
 * No AI, no TFLite. CameraX handles all camera lifecycle robustly.
 */
class LightBotService : LifecycleService() {

    // ── Telegram polling ──────────────────────────────────────────────
    private var pollingJob: Job? = null
    private val cmdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── CameraX ─────────────────────────────────────────────────────
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var capturing = false

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
            ACTION_CAPTURE        -> captureAndSend(source = "interval", chatId = null)
            ACTION_MANUAL_CAPTURE -> captureAndSend(source = "ui",      chatId = Config.chatId)
            ACTION_STOP         -> {
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
        shutdownCamera()
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
                        delay(30_000L)
                        continue
                    }

                    val result = TelegramBot.fetchUpdates(token, Config.botOffset)
                    result.getOrNull()?.let { updates ->
                        for (update in updates) {
                            Config.botOffset = update.updateId + 1
                            cmdScope.launch(Dispatchers.IO) {
                                handleCommand(update.text ?: "", update.chatId)
                            }
                        }
                    }

                    val waitMs = if (result.getOrNull()?.isEmpty() != false) 1_500L else 500L
                    delay(waitMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                    delay(10_000L)
                }
            }
        }
    }

    private fun handleCommand(text: String, chatId: String) {
        val token = Config.botToken
        if (token.isBlank() || chatId.isBlank()) return

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
                captureAndSend(source = "command", chatId = chatId)
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

    // ── CameraX Capture ───────────────────────────────────────────────

    private fun captureAndSend(source: String = "interval", chatId: String? = null) {
        if (capturing) {
            Log.w(TAG, "Capture already in progress, skipping")
            return
        }
        capturing = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Unbind any existing use cases
                provider.unbindAll()

                // ImageCapture — target 1280×720, JPEG quality 80
                @Suppress("DEPRECATION")
                val imgCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setJpegQuality(80)
                    .build()
                imageCapture = imgCapture

                // Bind to lifecycle (this service is a LifecycleService)
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imgCapture
                )

                // Take picture
                imgCapture.takePicture(
                    cameraExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                            val buf = image.planes[0].buffer
                            val data = ByteArray(buf.remaining())
                            buf.get(data)
                            image.close()
                            shutdownCamera()
                            capturing = false
                            Log.d(TAG, "JPEG captured: ${data.size} bytes")
                            processAndSend(data, source)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "CameraX capture error code: ${exception.imageCaptureError}", exception)
                            shutdownCamera()
                            capturing = false
                            if (Config.debugMode && chatId != null) {
                                val token = Config.botToken
                                TelegramBot.sendText(
                                    token, chatId,
                                    "🐛 [DEBUG] CameraX 异常：${exception.imageCaptureError}: ${exception.message}"
                                )
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "CameraX setup failed", e)
                shutdownCamera()
                capturing = false
                if (Config.debugMode && chatId != null) {
                    val token = Config.botToken
                    TelegramBot.sendText(
                        token, chatId,
                        "🐛 [DEBUG] CameraX 异常：${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shutdownCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraProvider?.shutdown()
        } catch (_: Exception) {}
        imageCapture = null
        cameraProvider = null
    }

    private fun processAndSend(jpegData: ByteArray, source: String) {
        cmdScope.launch(Dispatchers.IO) {
            try {
                val token = Config.botToken
                val chatId = Config.chatId
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val battery = getBatteryLevel()

                if (source == "interval") {
                    Config.lastIntervalCaptureTime = System.currentTimeMillis()
                } else {
                    Config.lastManualCaptureTime = System.currentTimeMillis()
                }

                val caption = buildCaption(source, now, battery)
                Log.d(TAG, "Sending photo to Telegram: ${jpegData.size} bytes, caption: $caption")
                val sendResult = TelegramBot.sendPhoto(token, chatId, jpegData, caption)
                if (sendResult.isFailure) {
                    Log.e(TAG, "sendPhoto failed: ${sendResult.exceptionOrNull()?.message}")
                    if (Config.debugMode && chatId.isNotBlank()) {
                        TelegramBot.sendText(
                            token, chatId,
                            "🐛 [DEBUG] sendPhoto 失败：${sendResult.exceptionOrNull()?.message}"
                        )
                    }
                } else {
                    Log.d(TAG, "Photo sent successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "processAndSend error", e)
                if (Config.debugMode) {
                    val token = Config.botToken
                    val chatId = Config.chatId
                    if (chatId.isNotBlank()) {
                        TelegramBot.sendText(token, chatId, "🐛 [DEBUG] processAndSend：${e.message}")
                    }
                }
            }
        }
    }

    private fun buildCaption(source: String, time: String, battery: Int): String {
        val srcLabel = when (source) {
            "interval" -> "闹钟拍照"
            "ui"      -> "App按钮拍照"
            else       -> "命令拍照"
        }
        val sb = StringBuilder()
        sb.append("📸 GuardEye Light\n")
        sb.append("$time\n")
        sb.append("来源：$srcLabel\n")
        sb.append("电量：$battery%")

        if (Config.debugMode) {
            sb.append("\n─────────────")
            sb.append("\n🤖 Bot：✅")
            sb.append("\n🔋 电量：$battery%")
            sb.append("\n⏱ 拍照间隔：${Config.intervalMinutes}min")
            sb.append("\n📐 分辨率：CameraX 1280×720")
            sb.append("\n🐛 调试：✅")
            sb.append("\n🤖 版本：${BuildConfig.VERSION_NAME}")
        }
        return sb.toString()
    }

    private fun buildStatusText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val interval = if (Config.lastIntervalCaptureTime > 0)
            fmt.format(Date(Config.lastIntervalCaptureTime)) else "无"
        val manual = if (Config.lastManualCaptureTime > 0)
            fmt.format(Date(Config.lastManualCaptureTime)) else "无"

        val sb = StringBuilder()
        sb.append("📊 GuardEye Light 状态\n")
        sb.append("─────────────────\n")
        sb.append("🤖 Bot：").append(if (Config.enabled) "✅ 运行中" else "⏸ 已停止").append("\n")
        sb.append("⏱ 拍照间隔：").append(Config.intervalMinutes).append(" 分钟\n")
        sb.append("🐛 调试：").append(if (Config.debugMode) "✅ 开启" else "❌ 关闭").append("\n")
        sb.append("─────────────────\n")
        sb.append("🕐 最近自动：").append(interval).append("\n")
        sb.append("📷 最近手动：").append(manual).append("\n")
        if (Config.debugMode) {
            sb.append("─────────────────\n")
            sb.append("🔋 电量：").append(getBatteryLevel()).append("%\n")
            sb.append("📐 相机：CameraX\n")
            sb.append("🤖 版本：").append(BuildConfig.VERSION_NAME).append("\n")
            sb.append("🤖 模型：N/A（Light版）")
        }
        return sb.toString()
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            (level * 100 / scale)
        } catch (e: Exception) { -1 }
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
        const val ACTION_CAPTURE        = "com.guardeye.light.ACTION_CAPTURE"
        const val ACTION_MANUAL_CAPTURE = "com.guardeye.light.ACTION_MANUAL_CAPTURE"
        const val ACTION_STOP           = "com.guardeye.light.ACTION_STOP"
    }
}
