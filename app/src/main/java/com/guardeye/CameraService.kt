package com.guardeye

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.os.BatteryManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardeye.Detector
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class CameraService : LifecycleService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "guardeye_channel"
        const val ACTION_START = "com.guardeye.action.START"
        const val ACTION_STOP = "com.guardeye.action.STOP"
        const val ACTION_CAPTURE = "com.guardeye.action.CAPTURE"
    }

    private val vibrator: Vibrator? by lazy { getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    private var detector: Detector? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private var alarmReceiver: AlarmReceiver? = null
    private var lastOffset = 0L
    private var isMonitoring = false

    // CameraX
    private var imageCapture: ImageCapture? = null
    private var cameraInitialized = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()

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
        if (isMonitoring) return
        isMonitoring = true

        val notification = buildNotification("GuardEye 监控已开启")
        startForeground(NOTIFICATION_ID, notification)

        // 复制 assets 中的模型到内部存储（首次）
        val modelFile = File(filesDir, "yolov8n.tflite")
        if (!modelFile.exists()) {
            try {
                assets.open("yolov8n.tflite").use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                android.util.Log.d("GuardEye", "Model copied to ${modelFile.absolutePath}, size=${modelFile.length()}")
            } catch (e: Exception) {
                android.util.Log.e("GuardEye", "Failed to copy model: ${e.message}", e)
            }
        }

        // 加载 YOLO 模型
        if (modelFile.exists()) {
            detector = Detector(modelFile.absolutePath)
            if (!detector!!.load()) {
                android.util.Log.e("GuardEye", "Model load() returned false")
                detector = null
            } else {
                android.util.Log.d("GuardEye", "Model loaded successfully")
            }
        } else {
            android.util.Log.e("GuardEye", "Model file not found: ${modelFile.absolutePath}")
        }

        // 初始化 CameraX
        initCamera()

        // 读取持久化的 offset
        lastOffset = Config.botOffset

        // 启动 Bot 轮询
        startBotPolling()

        // 发送启动欢迎信息
        sendWelcomeMessage()

        // 启动定时拍照
        scheduleNextCapture()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        pollJob?.cancel()
        pollJob = null
        alarmReceiver = null
        detector?.close()
        detector = null
        cameraInitialized = false
        cameraExecutor.shutdown()
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

    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                val preview = Preview.Builder().build()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this@CameraService,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                cameraInitialized = true
                android.util.Log.d("GuardEye", "CameraX initialized")
            } catch (e: Exception) {
                android.util.Log.e("GuardEye", "CameraX init failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startBotPolling() {
        if (Config.botToken.isBlank()) {
            android.util.Log.e("GuardEye", "Bot not started: token is blank")
            return
        }
        android.util.Log.d("GuardEye", "Bot polling started. token=${Config.botToken.take(10)}... chatId=${Config.chatId}")
        pollJob = serviceScope.launch {
            while (isActive) {
                try {
                    val updates = TelegramBot.getUpdates(lastOffset)
                    android.util.Log.d("GuardEye", "getUpdates returned ${updates.size} messages")
                    for (update in updates) {
                        android.util.Log.d("GuardEye", "Command: ${update.text}, chatId=${update.chatId}")
                        lastOffset = update.messageId + 1L
                        Config.botOffset = lastOffset

                        // 自动保存 chatId（如果未保存）
                        if (Config.chatId.isBlank() && update.chatId.isNotBlank()) {
                            Config.chatId = update.chatId
                            android.util.Log.d("GuardEye", "Auto-saved chatId: ${update.chatId}")
                        }

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

    private fun sendWelcomeMessage() {
        if (Config.botToken.isNotBlank() && Config.chatId.isNotBlank()) {
            val welcomeText = """
                🛡️ GuardEye 已启动

                ✅ 监控服务已开启
                📸 拍照间隔：${Config.intervalMinutes} 分钟
                🔍 AI 识别：${if (Config.detectionEnabled) "开启" else "关闭"}

                发送 /start 查看完整命令列表
                发送 /photo 立即拍照
                发送 /status 查看状态
            """.trimIndent()
            TelegramBot.sendText(welcomeText)
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
            相机：${if (cameraInitialized) "✅ 已初始化" else "❌ 未初始化"}
        """.trimIndent()
        TelegramBot.sendText(status)
    }

    private fun captureAndSend(hd: Boolean) {
        serviceScope.launch {
            val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            val bitmap = takePicture(photoFile, hd)
            if (bitmap == null) {
                TelegramBot.sendText("❌ 拍照失败")
                return@launch
            }

            // AI 识别
            if (Config.detectionEnabled && detector != null) {
                val detections = detector!!.detect(bitmap)
                if (!detections.isNullOrEmpty()) {
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

    private suspend fun takePicture(file: File, hd: Boolean): Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val imageCapture = this.imageCapture
        if (imageCapture == null || !cameraInitialized) {
            android.util.Log.e("GuardEye", "CameraX not initialized")
            cont.resume(null, null)
            return@suspendCancellableCoroutine
        }

        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputFileOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        cont.resume(bitmap, null)
                    } catch (e: Exception) {
                        android.util.Log.e("GuardEye", "Failed to load captured image: ${e.message}", e)
                        cont.resume(null, null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    android.util.Log.e("GuardEye", "takePicture failed: ${exception.message}", exception)
                    cont.resume(null, null)
                }
            }
        )
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
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
