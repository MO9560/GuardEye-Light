package com.guardeye

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardeye.Detector
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * SentinelService — GuardEye v4.0 full (continuous AI surveillance)
 *
 * Architecture:
 *   CameraX ImageAnalysis (continuous frame stream)
 *       → FrameBuffer (rolling N-second cache)
 *       → PersonAnalyzer (YOLOv8n, person + motorbike focus)
 *       → SecurityState (state machine)
 *       → AlertHandler (JPEG or MP4 → Telegram)
 *
 * Telegram commands handled by BotService polling → SentinelService actions.
 */
class SentinelService : LifecycleService() {

    // ── Detection model ────────────────────────────────────────────
    private var detector: Detector? = null
    private var detectorReady = false

    // ── CameraX ───────────────────────────────────────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Frame buffer ───────────────────────────────────────────────
    private val frameBuffer = RollingFrameBuffer(MAX_BUFFER_SECONDS, TARGET_FPS)

    // ── Security state machine ─────────────────────────────────────
    private val securityState = SecurityState()
    private var stateCallback: ((SecurityState.State) -> Unit)? = null

    // ── Alert mode ────────────────────────────────────────────────
    private var alertMode: AlertMode = AlertMode.PHOTO

    // ── Alert cooldown ────────────────────────────────────────────
    private var lastAlertTime = 0L
    private val alertCooldownMs get() = ALERT_COOLDOWN_MINUTES * 60_000L

    // ── Thermal throttling ─────────────────────────────────────────
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    }

    // ── Foreground notification ────────────────────────────────────
    private val CHANNEL_ID = "guardeye_sentinel_v4"
    private val NOTIF_ID = 4001

    // ── Coroutine scope ───────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Stats ─────────────────────────────────────────────────────
    private var frameCount = 0L
    private var detectCount = 0L
    private var lastFpsReport = 0L

    // ───────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()
        initDetector()
    }

    private fun initDetector() {
        serviceScope.launch(Dispatchers.IO) {
            detector = Detector(this@SentinelService)
            detectorReady = detector?.load() == true
            Log.d(TAG, "Detector ready: $detectorReady")
            if (detectorReady) {
                withContext(Dispatchers.Main) {
                    updateNotification("🤖 AI 已就绪")
                }
            } else {
                withContext(Dispatchers.Main) {
                    updateNotification("⚠️ AI 模型加载失败")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification("启动中..."))

        when (intent?.action) {
            ACTION_START, ACTION_RESTART -> startSurveillance()
            ACTION_STOP -> stopSurveillance()
            ACTION_TRIGGER_ALERT -> {
                val type = intent.getStringExtra(EXTRA_ALERT_TYPE) ?: "motion"
                triggerAlert(type)
            }
            else -> startSurveillance()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSurveillance()
        serviceScope.cancel()
        detector?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ── Surveillance ───────────────────────────────────────────────

    private fun startSurveillance() {
        if (cameraProvider != null) {
            Log.d(TAG, "Already running")
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
                updateNotification("🛡️ 监控中")
                Log.i(TAG, "SentinelService started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                updateNotification("❌ 相机启动失败")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        @Suppress("DEPRECATION")
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(ANALYSIS_RESOLUTION)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    onFrame(imageProxy)
                }
            }

        val cameraSelector = if (Config.cameraFacing == "front")
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                cameraSelector,
                imageAnalysis
            )
            Log.d(TAG, "CameraX bound with ImageAnalysis")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private fun stopSurveillance() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        frameBuffer.clear()
        securityState.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "SentinelService stopped")
    }

    // ── Frame processing ──────────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
    private fun onFrame(imageProxy: ImageProxy) {
        if (!detectorReady || !Config.enabled) {
            imageProxy.close()
            return
        }

        // Thermal throttling — skip frames if device is hot
        val thermalStatus = getThermalStatus()
        if (thermalStatus >= 4) {  // THERMAL_STATUS_SEVERE = 4
            imageProxy.close()
            return
        }

        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsReport >= 5000) {
            val fps = frameCount / ((now - lastFpsReport) / 1000f)
            Log.d(TAG, "FPS: %.1f, frames: $frameCount".format(fps))
            frameCount = 0
            lastFpsReport = now
        }

        // Convert YUV → RGB bitmap
        val bitmap = yuvToRgbBitmap(imageProxy) ?: run {
            imageProxy.close()
            return
        }

        // Store in rolling buffer
        frameBuffer.add(bitmap, now)

        // Run detection
        val detections = detector?.detect(bitmap) ?: emptyList()
        val personOrBike = detections.filter { (label, _) ->
            label in TARGET_CLASSES
        }

        if (personOrBike.isNotEmpty()) detectCount++

        // Feed to state machine
        val newState = securityState.update(
            hasPersonOrBike = personOrBike.isNotEmpty(),
            timestamp = now
        )

        // State transition → trigger alert
        if (newState == SecurityState.State.ALERT_TRIGGERED) {
            mainHandler.post {
                triggerAlert("person_detected")
            }
        }

        imageProxy.close()
    }

    // ── Alert ─────────────────────────────────────────────────────

    private fun triggerAlert(type: String) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < alertCooldownMs) {
            Log.d(TAG, "Alert suppressed (cooldown): ${(alertCooldownMs - (now - lastAlertTime)) / 1000}s remaining")
            return
        }
        lastAlertTime = now

        val alertFrames = frameBuffer.getRecent(ALERT_BUFFER_SECONDS)
        if (alertFrames.isEmpty()) {
            Log.w(TAG, "No frames in buffer for alert")
            return
        }

        val topDetections = securityState.lastDetections.take(3)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        serviceScope.launch(Dispatchers.IO) {
            try {
                when (alertMode) {
                    AlertMode.PHOTO -> {
                        // Send most recent frame as photo
                        val latest = alertFrames.last()
                        val caption = buildAlertCaption(type, timestamp, topDetections, latest.second)
                        sendPhotoAlert(latest.first, caption)
                    }
                    AlertMode.VIDEO -> {
                        // Encode frames to MP4 and send
                        val caption = buildAlertCaption(type, timestamp, topDetections, alertFrames.size)
                        sendVideoAlert(alertFrames, caption)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alert failed", e)
            }
        }
    }

    private fun buildAlertCaption(type: String, timestamp: String, detections: List<Pair<String, Float>>, extra: Any): String {
        val detectionText = if (detections.isEmpty()) {
            "⚠️ 移动检测（无目标分类）"
        } else {
            "🚨 检测到:\n" + detections.joinToString("\n") { (label, conf) ->
                val emoji = when (label) {
                    "person" -> "👤"
                    "motorbike" -> "🏍️"
                    "bicycle" -> "🚲"
                    "car" -> "🚗"
                    "bus", "truck" -> "🚛"
                    else -> "🔍"
                }
                "$emoji $label ${(conf * 100).toInt()}%"
            }
        }

        val debugBlock = if (Config.debugMode) """
            ─────────────────────
            📊 状态: ${securityState.currentState}
            🧠 模型: ✅ 已加载
            📐 分辨率: ${ANALYSIS_RESOLUTION.width}×${ANALYSIS_RESOLUTION.height}
            🤖 版本: ${BuildConfig.VERSION_NAME}
        """.trimIndent() else ""

        return """
            🚨 GuardEye 告警
            ─────────────────────
            🕐 $timestamp
            📋 类型: $type
            $detectionText
            $debugBlock
        """.trimIndent()
    }

    private suspend fun sendPhotoAlert(bitmap: Bitmap, caption: String) {
        val bytes = bitmapToJpeg(bitmap, 85)
        TelegramBot.sendPhoto(Config.botToken, Config.chatId, bytes, caption)
        Log.d(TAG, "Photo alert sent")
    }

    private suspend fun sendVideoAlert(frames: List<Pair<Bitmap, Long>>, caption: String) {
        // Encode frames to MP4 using JCodec-compatible approach
        // For now, fall back to sending the most recent frame + all frames as separate photos
        // TODO: Integrate JCodec for proper MP4 encoding
        val latest = frames.last().first
        val bytes = bitmapToJpeg(latest, 85)
        TelegramBot.sendPhoto(Config.botToken, Config.chatId, bytes, "$caption\n\n🎬 视频模式待完成")
        Log.d(TAG, "Video alert (photo fallback) sent, ${frames.size} frames")
    }

    // ── Telegram action handlers ───────────────────────────────────

    fun handleAction(action: String, extras: Map<String, String> = emptyMap()) {
        when (action) {
            ACTION_START -> startSurveillance()
            ACTION_STOP -> stopSurveillance()
            "set_mode_photo" -> {
                alertMode = AlertMode.PHOTO
                Config.alertMode = "photo"
                Log.d(TAG, "Alert mode: PHOTO")
            }
            "set_mode_video" -> {
                alertMode = AlertMode.VIDEO
                Config.alertMode = "video"
                Log.d(TAG, "Alert mode: VIDEO")
            }
            "get_status" -> {
                serviceScope.launch {
                    val status = buildStatusText()
                    TelegramBot.sendText(Config.botToken, Config.chatId, status)
                }
            }
        }
    }

    private fun buildStatusText(): String {
        val sb = StringBuilder()
        sb.append("🛡️ GuardEye v4.0 状态\n")
        sb.append("─────────────────\n")
        sb.append("🤖 监控：").append(if (cameraProvider != null) "✅ 运行中" else "⏸ 已停止").append("\n")
        sb.append("🔍 AI 检测：").append(if (detectorReady) "✅ 就绪" else "⏳ 加载中").append("\n")
        sb.append("🎬 告警模式：").append(alertMode.name).append("\n")
        sb.append("⏱ 帧缓冲：").append(MAX_BUFFER_SECONDS).append("秒 / ").append(ALERT_BUFFER_SECONDS).append("秒告警\n")
        sb.append("📊 目标类：").append(TARGET_CLASSES.joinToString(", ")).append("\n")
        sb.append("─────────────────\n")
        sb.append("🔋 状态：").append(getThermalLabel()).append("\n")
        sb.append("🤖 版本：").append(BuildConfig.VERSION_NAME).append("\n")
        return sb.toString()
    }

    private fun getThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val method = powerManager.javaClass.getMethod("getThermalStatus")
                (method.invoke(powerManager) as? Int) ?: -1
            } catch (_: Exception) { -1 }
        } else -1
    }

    private fun getThermalLabel(): String {
        return when (getThermalStatus()) {
            android.os.PowerManager.THERMAL_STATUS_NONE -> "✅ 正常"
            android.os.PowerManager.THERMAL_STATUS_LIGHT -> "⚠️ 轻度发热"
            android.os.PowerManager.THERMAL_STATUS_MODERATE -> "🔶 中度发热"
            android.os.PowerManager.THERMAL_STATUS_SEVERE -> "🔴 严重发热"
            android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "🆘 危险"
            else -> "—"
        }
    }

    // ── YUV → RGB conversion ─────────────────────────────────────

    @SuppressLint("UnsafeOptInUsageError")
    private fun yuvToRgbBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, baos)
            val jpegBytes = baos.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)?.let { fullBitmap ->
                // Scale to 640x640 for YOLOv8n
                Bitmap.createScaledBitmap(fullBitmap, DETECTOR_INPUT_SIZE, DETECTOR_INPUT_SIZE, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "YUV→RGB failed", e)
            null
        }
    }

    private fun bitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return baos.toByteArray()
    }

    // ── Notification ───────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "GuardEye v4.0", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("🛡️ GuardEye v4.0")
        .setContentText(text)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun updateNotification(text: String) {
        try {
            val n = buildNotification(text)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {}
    }

    companion object {
        private const val TAG = "SentinelService"

        // Intent actions — called by BotService
        const val ACTION_START = "com.guardeye.action.SENTINEL_START"
        const val ACTION_STOP  = "com.guardeye.action.SENTINEL_STOP"
        const val ACTION_RESTART = "com.guardeye.action.SENTINEL_RESTART"
        const val ACTION_TRIGGER_ALERT = "com.guardeye.action.TRIGGER_ALERT"
        const val EXTRA_ALERT_TYPE = "alert_type"

        // Analysis
        const val DETECTOR_INPUT_SIZE = 640
        val ANALYSIS_RESOLUTION = Size(640, 480)

        // Rolling buffer
        const val MAX_BUFFER_SECONDS = 10   // keep last 10 seconds
        const val ALERT_BUFFER_SECONDS = 5  // send last 5 seconds on alert

        // Alert cooldown
        const val ALERT_COOLDOWN_MINUTES = 2

        // Target detection classes (F's requirement: 交警制服 + 摩托车)
        val TARGET_CLASSES = setOf(
            "person",     // 人员
            "motorbike",  // 摩托车
            "bicycle",    // 自行车
            "car",        // 私家车
            "bus",        // 公交车
            "truck"       // 货车
        )

        // Target FPS for analysis
        const val TARGET_FPS = 5

        // Singleton instance for BotService to communicate with
        @Volatile var instance: SentinelService? = null
            private set
    }

    init {
        instance = this
    }
}

// ── Alert mode ────────────────────────────────────────────────────────

enum class AlertMode {
    PHOTO, VIDEO
}

// ── Security State Machine ─────────────────────────────────────────────

class SecurityState {
    enum class State { IDLE, GRACE_PERIOD, ALERT_TRIGGERED }

    @Volatile var currentState: State = State.IDLE
        private set

    @Volatile var lastDetections: List<Pair<String, Float>> = emptyList()
        private set

    private var graceStartTime = 0L
    private var noPersonStartTime = 0L

    // Config
    var gracePeriodMs = 5_000L      // 5s of continuous person before alert
    var noPersonTimeoutMs = 10_000L  // 10s of no person → reset to IDLE

    fun update(hasPersonOrBike: Boolean, timestamp: Long): State {
        lastDetections = lastDetections  // keep last

        return when (currentState) {
            State.IDLE -> {
                if (hasPersonOrBike) {
                    graceStartTime = timestamp
                    currentState = State.GRACE_PERIOD
                    Log.d("SecurityState", "IDLE → GRACE_PERIOD (person detected)")
                }
                currentState
            }
            State.GRACE_PERIOD -> {
                if (!hasPersonOrBike) {
                    // Person gone during grace — check if just briefly
                    if (noPersonStartTime == 0L) noPersonStartTime = timestamp
                    val gap = timestamp - noPersonStartTime
                    if (gap > noPersonTimeoutMs) {
                        reset()
                        Log.d("SecurityState", "GRACE_PERIOD → IDLE (person left, timeout)")
                    }
                } else {
                    noPersonStartTime = 0L
                    val elapsed = timestamp - graceStartTime
                    if (elapsed >= gracePeriodMs) {
                        currentState = State.ALERT_TRIGGERED
                        Log.d("SecurityState", "GRACE_PERIOD → ALERT_TRIGGERED (%.1fs elapsed)".format(elapsed / 1000f))
                    }
                }
                currentState
            }
            State.ALERT_TRIGGERED -> {
                // One-shot: BotService/BroadcastReceiver handles the alert
                // After alert is sent, reset after a cooldown
                currentState
            }
        }
    }

    fun reset() {
        currentState = State.IDLE
        graceStartTime = 0L
        noPersonStartTime = 0L
        lastDetections = emptyList()
    }
}

// ── Rolling Frame Buffer ───────────────────────────────────────────────

class RollingFrameBuffer(
    private val maxSeconds: Int,
    private val fps: Int
) {
    // Each entry: Pair(bitmap, timestamp_ms)
    private val buffer = LinkedList<Pair<Bitmap, Long>>()
    private val maxFrames = maxSeconds * fps

    @Synchronized
    fun add(bitmap: Bitmap, timestamp: Long) {
        // Keep a copy (bitmap is recycled by CameraX after callback)
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        buffer.addLast(copy to timestamp)

        // Evict old frames
        while (buffer.size > maxFrames) {
            buffer.removeFirst().first.recycle()
        }
    }

    @Synchronized
    fun getRecent(seconds: Int): List<Pair<Bitmap, Long>> {
        val cutoff = System.currentTimeMillis() - seconds * 1000L
        return buffer.filter { it.second >= cutoff }.map { it.first to it.second }
    }

    @Synchronized
    fun clear() {
        buffer.forEach { it.first.recycle() }
        buffer.clear()
    }

    @Synchronized
    fun size(): Int = buffer.size
}
