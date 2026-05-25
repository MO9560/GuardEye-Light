package com.guardeye

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardeye.BotManager
import com.guardeye.BotManager.NotifyLevel
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * CameraService — 相机监控服务
 *
 * 职责：
 * - CameraX 相机初始化与管理
 * - YOLOv8n 目标检测（TensorFlow Lite）
 * - 定时拍照 + AlarmManager 调度
 * - 状态变更 → BotManager 推送 Telegram 通知
 *
 * Bot 通信完全由 BotManager/BotForegroundService 处理，
 * CameraService 不负责任何网络通信。
 */
class CameraService : LifecycleService() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "guardeye_channel"
        const val ACTION_START = "com.guardeye.action.START"
        const val ACTION_STOP = "com.guardeye.action.STOP"
        const val ACTION_CAPTURE = "com.guardeye.action.CAPTURE"

        // 供 BotForegroundService 查询相机是否在运行
        @Volatile var isInstanceRunning = false
            private set
    }

    private val vibrator: Vibrator? by lazy { getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    private var detector: Detector? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // CameraX
    private var imageCapture: ImageCapture? = null
    private var cameraInitialized = false
    private var isMonitoring = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // 模型加载状态（供 BotForegroundService 查询）
    var isModelReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()
        isInstanceRunning = true
        BotManager.setToken(Config.botToken)
        BotManager.setChatId(Config.chatId)
        BotManager.notifyStatus("CameraService 启动", "模型加载中...", NotifyLevel.INFO)
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
        BotForegroundService.setCameraServiceRunning(true)

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
                android.util.Log.d("GuardEye", "Model copied: ${modelFile.length()} bytes")
            } catch (e: Exception) {
                android.util.Log.e("GuardEye", "Failed to copy model: ${e.message}", e)
                BotManager.notifyStatus("模型复制失败", "${e.message}", NotifyLevel.WARN)
            }
        }

        // 加载 YOLO 模型
        if (modelFile.exists()) {
            detector = Detector(modelFile.absolutePath)
            if (!detector!!.load()) {
                android.util.Log.e("GuardEye", "Model load() returned false")
                BotManager.notifyStatus("模型加载失败", "load() 返回 false", NotifyLevel.WARN)
                detector = null
                isModelReady = false
            } else {
                android.util.Log.d("GuardEye", "Model loaded successfully")
                isModelReady = true
                BotManager.notifyStatus("✅ 模型加载成功", "yolov8n.tflite 已就绪", NotifyLevel.SUCCESS)
            }
        } else {
            android.util.Log.e("GuardEye", "Model file not found: ${modelFile.absolutePath}")
            isModelReady = false
            BotManager.notifyStatus("模型文件不存在", modelFile.absolutePath, NotifyLevel.WARN)
        }

        // 初始化 CameraX
        initCamera()
        scheduleNextCapture()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        isModelReady = false
        BotForegroundService.isCameraServiceRunning = false
        detector?.close()
        detector = null
        cameraInitialized = false
        cameraExecutor.shutdown()
        BotManager.notifyStatus("相机服务已停止", "CameraService stopped", NotifyLevel.INFO)
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        android.util.Log.d("GuardEye", "Next capture scheduled in ${Config.intervalMinutes} min")
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
                BotManager.notifyStatus("📸 相机初始化成功", "CameraX ready", NotifyLevel.SUCCESS)
                android.util.Log.d("GuardEye", "CameraX initialized")
            } catch (e: Exception) {
                android.util.Log.e("GuardEye", "CameraX init failed: ${e.message}", e)
                BotManager.notifyStatus("相机初始化失败", "${e.message}", NotifyLevel.WARN)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 拍照并发送（由 AlarmReceiver 或 BotForegroundService 调用）
     */
    fun captureAndSend(hd: Boolean) {
        serviceScope.launch {
            val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
            val bitmap = takePicture(photoFile, hd)
            if (bitmap == null) {
                BotManager.notifyStatus("❌ 定时拍照失败", "takePicture returned null", NotifyLevel.WARN)
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
                        BotManager.notifyStatus("🚨 检测到告警目标", labels, NotifyLevel.ALERT)
                        BotManager.sendText("🚨 *GuardEye 告警*\n目标：$labels")
                        BotManager.sendBitmap(annotatedToBytes(annotated), "🚨 GuardEye 告警")
                        triggerAlert()
                    } else {
                        val allLabels = detections.joinToString(", ") { "${it.label} (${(it.confidence*100).toInt()}%)" }
                        BotManager.sendText("ℹ️ *检测结果*\n$allLabels")
                    }
                } else {
                    if (!hd) BotManager.sendBitmap(annotatedToBytes(bitmap))
                }
            }

            // 高清图单独发送
            if (hd) {
                FileOutputStream(photoFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }
                BotManager.sendPhoto(photoFile.readBytes(), "📸 GuardEye 实时画面")
            }

            // 重新排程下次拍照
            scheduleNextCapture()
        }
    }

    private suspend fun takePicture(file: File, hd: Boolean): Bitmap? = suspendCancellableCoroutine { cont ->
        val ic = this.imageCapture
        if (ic == null || !cameraInitialized) {
            android.util.Log.e("GuardEye", "CameraX not initialized")
            cont.resume(null, null)
            return@suspendCancellableCoroutine
        }

        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        ic.takePicture(
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

    private fun annotatedToBytes(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }

    private fun triggerAlert() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 GuardEye 告警")
            .setContentText("检测到疑似警用目标！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, notification)
        } catch (e: SecurityException) { /* 无通知权限 */ }
    }

    override fun onDestroy() {
        isInstanceRunning = false
        BotForegroundService.setCameraServiceRunning(false)
        serviceScope.cancel()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
