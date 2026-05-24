package com.guardeye

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardeye.Detector
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class CameraService : LifecycleService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "guardeye_channel"
        const val ACTION_START = "com.guardeye.START"
        const val ACTION_STOP = "com.guardeye.STOP"
        const val ACTION_CAPTURE = "com.guardeye.CAPTURE"
    }

    private val vibrator: Vibrator? by lazy { getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    private var detector: Detector? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private var alarmReceiver: AlarmReceiver? = null
    private var lastOffset = 0L

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            ACTION_CAPTURE -> captureAndSend(hd = true)
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        val notification = buildNotification("GuardEye 监控已开启")
        startForeground(NOTIFICATION_ID, notification)

        // 复制 assets 中的模型到内部存储（首次）
        val modelFile = File(filesDir, "uniform_detector.tflite")
        if (!modelFile.exists()) {
            assets.open("uniform_detector.tflite").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        // 加载 YOLO 模型
        if (modelFile.exists()) {
            detector = Detector(modelFile.absolutePath)
            if (!detector!!.load()) {
                detector = null
            }
        }

        // 启动 Bot 轮询
        startBotPolling()

        // 启动定时拍照
        scheduleNextCapture()
    }

    private fun stopMonitoring() {
        pollJob?.cancel()
        alarmReceiver = null
        detector?.close()
        detector = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun scheduleNextCapture() {
        val interval = Config.intervalMinutes * 60 * 1000L
        val triggerTime = SystemClock.elapsedRealtime() + interval
        val intent = Intent(this, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi)
    }

    private fun startBotPolling() {
        if (Config.botToken.isBlank() || Config.chatId.isBlank()) {
            android.util.Log.e("GuardEye", "Bot not started: token or chatId is blank")
            TelegramBot.sendText("⚠️ GuardEye 启动失败：Token 或 Chat ID 未填写")
            return
        }
        TelegramBot.configure(Config.botToken, Config.chatId)
        android.util.Log.d("GuardEye", "Bot polling started. token=${Config.botToken.take(10)}... chatId=${Config.chatId}")
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    val updates = TelegramBot.getUpdates(lastOffset)
                    android.util.Log.d("GuardEye", "getUpdates returned ${updates.size} messages")
                    for (update in updates) {
                        android.util.Log.d("GuardEye", "Command: ${update.text}")
                        lastOffset = update.messageId + 1L
                        handleCommand(update.text)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GuardEye", "Polling error: ${e.message}", e)
                    e.printStackTrace()
                }
                delay(3000)
            }
        }
    }

    private fun handleCommand(text: String) {
        when {
            text.startsWith("/start") -> {
                Config.enabled = true
                TelegramBot.sendText("✅ GuardEye 已开启\n间隔：${Config.intervalMinutes}分钟\nAI 识别：${if (Config.detectionEnabled) "开启" else "关闭"}\n\n发送 /photo 立即拍照\n发送 /status 查看状态")
            }
            text.startsWith("/stop") -> {
                Config.enabled = false
                TelegramBot.sendText("⏸ GuardEye 已停止")
            }
            text.startsWith("/photo") -> captureAndSend(hd = true)
            text.startsWith("/status") -> sendStatus()
            text.startsWith("/interval") -> {
                val mins = text.removePrefix("/interval").trim().toIntOrNull()
                if (mins != null && mins in 1..10) {
                    Config.intervalMinutes = mins
                    TelegramBot.sendText("⏱ 拍摄间隔已设为 ${mins} 分钟")
                } else {
                    TelegramBot.sendText("用法：/interval 1-10\n当前：${Config.intervalMinutes}分钟")
                }
            }
            text.startsWith("/detect") -> {
                val enabled = !text.contains("off")
                Config.detectionEnabled = enabled
                TelegramBot.sendText("🔍 AI 识别：${if (enabled) "开启" else "关闭"}")
            }
        }
    }

    private fun sendStatus() {
        val battery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 0
        val mem = Runtime.getRuntime()
        val usedMB = (mem.totalMemory() - mem.freeMemory()) / 1024 / 1024
        val totalMB = mem.totalMemory() / 1024 / 1024
        val status = """
            📊 GuardEye 状态
            监控：${if (Config.enabled) "✅ 开启" else "❌ 关闭"}
            间隔：${Config.intervalMinutes} 分钟
            AI 识别：${if (Config.detectionEnabled) "✅ 开启" else "❌ 关闭"}
            电量：$battery%
            内存：${usedMB}MB / ${totalMB}MB
            检测模型：${if (detector != null) "✅ 已加载" else "❌ 未加载"}
        """.trimIndent()
        TelegramBot.sendText(status)
    }

    private fun captureAndSend(hd: Boolean) {
        serviceScope.launch {
            val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            takePicture(photoFile, hd) { bitmap ->
                if (bitmap == null) {
                    TelegramBot.sendText("❌ 拍照失败")
                    return@takePicture
                }

                // AI 识别
                if (Config.detectionEnabled && detector != null) {
                    val detections = detector!!.detect(bitmap)
                    if (!detections.isNullOrEmpty()) {
                        // 绘制标注
                        val annotated = drawBoxes(bitmap, detections)
                        val policeDetections = detections.filter { it.isPolice }
                        if (policeDetections.isNotEmpty()) {
                            val labels = policeDetections.joinToString(", ") {
                                "${it.label} (${(it.confidence * 100).toInt()}%)"
                            }
                            TelegramBot.sendText("🚨 检测到疑似目标：$labels")
                            TelegramBot.sendBitmap(annotated, "🚨 GuardEye 告警")
                            triggerAlert()
                        } else {
                            TelegramBot.sendText("ℹ️ 检测到：${detections.joinToString(", ") { it.label }}")
                        }
                    } else {
                        // 没人/没车，只发普通图片
                        if (!hd) TelegramBot.sendBitmap(bitmap)
                    }
                }

                // 高清图单独发送
                if (hd) {
                    FileOutputStream(photoFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                    }
                    TelegramBot.sendPhoto(photoFile, "📸 GuardEye 实时画面")
                }
            }
        }
    }

    private fun takePicture(file: File, hd: Boolean, callback: (Bitmap?) -> Unit) {
        val quality = if (hd) 95 else 40
        val width = if (hd) 1920 else 640

        // 模拟：创建测试图片（实际使用 CameraX）
        val bmp = Bitmap.createBitmap(width, (width * 1.33f).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (hd) Color.DKGRAY else Color.GRAY)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("GuardEye ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}", width / 2f, bmp.height / 2f, paint)

        try {
            FileOutputStream(file).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        callback(bmp)
    }

    private fun drawBoxes(bitmap: Bitmap, detections: List<Detector.Detection>): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val w = result.width.toFloat()
        val h = result.height.toFloat()
        for (d in detections) {
            val paint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = if (d.isPolice) 8f else 4f
                color = if (d.isPolice) Color.RED else Color.GREEN
            }
            val textPaint = Paint().apply {
                style = Paint.Style.FILL
                textSize = 36f
                color = if (d.isPolice) Color.RED else Color.GREEN
            }
            val rx1 = (d.box[0] * w).toInt().coerceIn(0, result.width - 1)
            val ry1 = (d.box[1] * h).toInt().coerceIn(0, result.height - 1)
            val rx2 = (d.box[2] * w).toInt().coerceIn(0, result.width - 1)
            val ry2 = (d.box[3] * h).toInt().coerceIn(0, result.height - 1)
            canvas.drawRect(rx1.toFloat(), ry1.toFloat(), rx2.toFloat(), ry2.toFloat(), paint)
            val label = "${d.label} ${(d.confidence * 100).toInt()}%${if (d.isPolice) " 🚨" else ""}"
            canvas.drawText(label, rx1.toFloat(), ((ry1 - 4).coerceAtLeast(36)).toFloat(), textPaint)
        }
        return result
    }

    private fun triggerAlert() {
        // 震动
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
        }
        // 系统通知
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 GuardEye 告警")
            .setContentText("检测到疑似警用目标！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, notification)
        } catch (e: SecurityException) {
            // 无通知权限
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
