package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ── Camera orientation ───────────────────────────────────────────────────────
// CameraX landscape-shot EXIF: try 90f first, switch to 270f if inverted
private const val ROTATE_LANDSCAPE = 270f   // 横拍旋转角度：90f 或 270f

// ── Intent actions (top-level for easy cross-class access) ─────────────────
const val ACTION_CAPTURE          = "com.guardeye.light.ACTION_CAPTURE"
const val ACTION_MANUAL_CAPTURE   = "com.guardeye.light.ACTION_MANUAL_CAPTURE"
const val ACTION_STOP             = "com.guardeye.light.ACTION_STOP"
const val ACTION_REQUEST_BATTERY  = "com.guardeye.light.ACTION_REQUEST_BATTERY"
const val ACTION_PREVIEW_FRONT    = "com.guardeye.light.ACTION_PREVIEW_FRONT"

// ── Photo quality tiers ─────────────────────────────────────────────────────
// H/M/L/X: post-processing rescales to target size
// X: original — no resize, no recompress
object PhotoQuality {
    const val HIGH   = "h"
    const val MEDIUM = "m"
    const val LOW    = "l"
    const val RAW    = "x"   // 原图，不缩不放

    // Target display sizes (used for post-processing resize)
    const val HIGH_W   = 1920; const val HIGH_H   = 1080
    const val MEDIUM_W = 1280; const val MEDIUM_H = 720
    const val LOW_W    = 854;  const val LOW_H    = 480

    // JPEG quality passed to compressor
    fun jpegQualityFor(quality: String): Int = when (quality.lowercase()) {
        HIGH   -> 95
        MEDIUM -> 70
        LOW    -> 50
        RAW    -> 95   // X 原图：直出，只 strip EXIF
        else   -> 95
    }

    // Display label for caption
    fun labelFor(quality: String): String = when (quality.lowercase()) {
        HIGH   -> "1920×1080"
        MEDIUM -> "1280×720"
        LOW    -> "854×480"
        RAW    -> "原图"
        else   -> "1920×1080"
    }
}

/**
 * LightBotService — GuardEye Light (CameraX)
 * Telegram polling + CameraX capture in one foreground service.
 * ImageCapture is bound once at startup and reused for all captures.
 * CameraLifecycleOwner stays STARTED independent of app foreground state.
 *
 * Background keep-alive strategy:
 *  1. ForegroundService — tells system this is a long-running service
 *  2. PARTIAL_WAKE_LOCK — prevents CPU from sleeping (camera capture still works with screen off)
 *  3. WakeLock keep-alive — re-acquires every 8 min before the 10-min lease expires
 *  4. setAlarmClock (in LightAlarmReceiver) — highest-priority alarm, always fires in Doze
 *  5. Notification IMPORTANCE_HIGH — less likely to be killed by system
 */
class LightBotService : LifecycleService() {

    // ── CameraX — bound once, reused for all captures ─────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var capturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureTimeoutRunnable: Runnable? = null
    private var cameraReady = false

    // ── Front camera preview ──────────────────────────────────────────
    private var previewJob: Job? = null
    private var isFrontPreview = false
    private var previewEndTime = 0L

    // ── Lifecycle — stays STARTED even when app is in background ──────
    private val cameraLifecycleOwner = CameraLifecycleOwner()

    // ── Telegram polling — protected by WakeLock so it works when screen off ─
    private var pollingJob: Job? = null
    private val cmdScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "GuardEye:LightPolling"
        ).apply {
            setReferenceCounted(false)
        }
    }
    // Keep-alive: re-acquire WakeLock every 8 min to prevent kernel from timing it out
    private var keepAliveRunnable: Runnable? = null

    // ── Foreground notification ─────────────────────────────────────
    private val CHANNEL_ID = "guardeye_light_bot"
    private val NOTIF_ID = 2001

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cameraLifecycleOwner.stop()
                shutdownCameraNow()
            }
        })

        initCameraProvider()
    }

    private fun initCameraProvider() {
        if (cameraProvider != null) {
            bindImageCapture()
            return
        }
        Log.d(TAG, "[main] initCameraProvider — requesting ProcessCameraProvider")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                Log.d(TAG, "[main] ProcessCameraProvider ready")
                bindImageCapture()
            } catch (e: Exception) {
                Log.e(TAG, "[main] ProcessCameraProvider.get() failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // CameraX always binds at HIGH (1920×1080); post-processing handles resize/compress.
    private fun bindImageCapture() {
        val provider = cameraProvider ?: return

        @Suppress("DEPRECATION")
        val imgCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(PhotoQuality.HIGH_W, PhotoQuality.HIGH_H))
            .setJpegQuality(95)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                cameraLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imgCapture
            )
            imageCapture = imgCapture
            cameraReady = true
            cameraLifecycleOwner.start()
            Log.d(TAG, "[main] ImageCapture bound — HIGH (1920×1080)")
        } catch (e: Exception) {
            Log.e(TAG, "[main] Failed to bind ImageCapture", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // ── 1. Acquire WakeLock immediately ──────────────────────────────
        // PARTIAL_WAKE_LOCK: CPU stays awake, screen can still turn off
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 60 * 1000L) // 10 min; renewed by keep-alive below
            Log.d(TAG, "WakeLock acquired on service start")
        }
        startKeepAlive() // re-acquire before each 10-min window expires

        // ── 2. Start Telegram polling — ALWAYS running ────────────────────
        // Unconditional: AlarmReceiver-only restarts still need polling alive.
        // Safe to call repeatedly (startPolling cancels old job first).
        startPolling()

        // ── 3. Start foreground service ──────────────────────────────────
        startForeground(NOTIF_ID, buildNotification())
        cameraLifecycleOwner.start()

        when (intent?.action) {
            ACTION_PREVIEW_FRONT -> {
                startFrontPreview(chatId = Config.chatId)
            }
            ACTION_CAPTURE, ACTION_MANUAL_CAPTURE -> {
                val source = if (intent.action == ACTION_CAPTURE) "interval" else "ui"
                captureAndSend(source = source, chatId = Config.chatId)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REQUEST_BATTERY -> {
                requestIgnoreBatteryOptimizations()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // else: nothing extra needed; polling already started above
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        stopKeepAlive()
        cmdScope.cancel()
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        cameraExecutor.shutdown()
        cameraProvider?.shutdown()
        cameraLifecycleOwner.stop()
        if (wakeLock.isHeld) {
            try { wakeLock.release() } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ── Telegram Polling ────────────────────────────────────────────────────────

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

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        // WakeLock released by stopKeepAlive(); don't release here (camera capture needs it)
    }

    // ── WakeLock keep-alive: re-acquire before each 10-min window expires ──────

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAliveRunnable = object : Runnable {
            override fun run() {
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(10 * 60 * 1000L)
                    Log.d(TAG, "WakeLock renewed via keep-alive")
                }
                mainHandler.postDelayed(this, 8 * 60 * 1000L) // every 8 min
            }
        }
        keepAliveRunnable?.let { mainHandler.postDelayed(it, 8 * 60 * 1000L) }
    }

    private fun stopKeepAlive() {
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        // Release WakeLock only when service is truly shutting down
        if (wakeLock.isHeld) {
            try { wakeLock.release() } catch (_: Exception) {}
        }
    }

    // ── Battery Optimization whitelist ─────────────────────────────────────────

    /**
     * Opens system dialog to request adding this app to battery whitelist (Doze exempt).
     * This is the single most effective thing users can do to prevent kill-by-battery.
     * Call via ACTION_REQUEST_BATTERY intent from MainActivity.
     */
    private fun requestIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open battery optimization settings", e)
        }
    }

    // ── Telegram Commands ───────────────────────────────────────────────────────

    private fun handleCommand(text: String, chatId: String) {
        val token = Config.botToken
        if (token.isBlank() || chatId.isBlank()) return

        when {
            text == "/start" -> {
                Config.enabled = true
                startForegroundService(Intent(this, LightBotService::class.java))
                LightAlarmReceiver.scheduleAlarm(this, Config.intervalMinutes)
                TelegramBot.sendText(token, chatId,
                    "✅ GuardEye Light 已启动\n" +
                    "⏱ 间隔：${Config.intervalMinutes}分钟\n" +
                    "🔋 提示：建议关闭电池优化以提高稳定性\n" +
                    "   App内发送 /battery 可一键设置")
            }
            text == "/stop" -> {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                TelegramBot.sendText(token, chatId, "⏸ 已停止")
            }
            text.startsWith("/preview") -> {
                TelegramBot.sendText(token, chatId, "📷 开启前镜头预览，30秒后自动关闭")
                startFrontPreview(chatId = chatId)
            }
            text.startsWith("/photo") -> {
                val raw = text.removePrefix("/photo").trim().lowercase()
                val isFront = raw.startsWith("f ") || raw.startsWith("f")
                val qualityRaw = if (isFront) raw.removePrefix("f").trim() else raw
                // "" = no suffix given (use source default); "h"/"m"/"l"/"x" = explicit
                val quality = when (qualityRaw) {
                    "h" -> PhotoQuality.HIGH
                    "m" -> PhotoQuality.MEDIUM
                    "l" -> PhotoQuality.LOW
                    "x" -> PhotoQuality.RAW
                    else -> ""  // no suffix → source default
                }
                val label = if (quality.isNotEmpty()) PhotoQuality.labelFor(quality) else "1920×1080"
                if (isFront) {
                    TelegramBot.sendText(token, chatId, "🤳 前镜头拍照中... [$label]")
                    captureFrontCamera(chatId = chatId, quality = if (quality.isNotEmpty()) quality else PhotoQuality.HIGH)
                } else {
                    TelegramBot.sendText(token, chatId, "📸 正在拍照... [$label]")
                    captureAndSend(source = "TEL", chatId = chatId, quality = quality)
                }
            }
            text == "/status" -> {
                TelegramBot.sendText(token, chatId, buildStatusText())
            }
            text == "/battery" -> {
                TelegramBot.sendText(token, chatId,
                    "🔋 正在打开电池优化设置...\n请选择「不限」或「不优化」以确保后台稳定运行")
                val i = Intent(this, LightBotService::class.java).apply {
                    action = ACTION_REQUEST_BATTERY
                }
                startForegroundService(i)
            }
            text.startsWith("/interval ") -> {
                val mins = text.removePrefix("/interval ").trim().toIntOrNull()
                if (mins != null && mins in 1..60) {
                    Config.intervalMinutes = mins
                    if (Config.enabled) {
                        startForegroundService(Intent(this, LightBotService::class.java))
                        LightAlarmReceiver.scheduleAlarm(this, mins)
                    }
                    TelegramBot.sendText(token, chatId, "⏱ 间隔已设为 $mins 分钟")
                } else {
                    TelegramBot.sendText(token, chatId, "用法：/interval 1-60")
                }
            }
            text.startsWith("/debug") -> {
                val mode = text.removePrefix("/debug").trim().lowercase()
                Config.debugMode = (mode.isEmpty() || mode == "on")
                if (Config.enabled) startForegroundService(Intent(this, LightBotService::class.java))
                TelegramBot.sendText(token, chatId,
                    if (Config.debugMode) "🐛 调试模式开启" else "🐛 调试模式关闭")
            }
            text == "/test" -> {
                TelegramBot.sendText(token, chatId, "✅ GuardEye Light 正常运行\n版本：${BuildConfig.VERSION_NAME}")
            }
        }
    }

    // ── CameraX Capture ────────────────────────────────────────────────────────

    private fun captureAndSend(source: String, chatId: String?, quality: String = PhotoQuality.HIGH) {
        // Source-default quality when no explicit suffix is given.
        // quality="" means no suffix → use source default.
        // Any non-empty value (h/m/l/x) is an explicit user choice that wins.
        val outputQuality = if (quality.isNotEmpty()) {
            quality
        } else {
            when (source) {
                "interval" -> PhotoQuality.MEDIUM
                "ui"       -> PhotoQuality.LOW
                else       -> PhotoQuality.LOW
            }
        }
        if (capturing) {
            if (source == "command" && chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "⚠️ 相机正忙，请稍后重试")
            }
            return
        }
        mainHandler.post {
            if (!cameraReady) {
                Log.d(TAG, "Camera not ready — forcing init from capture path")
                initCameraProvider()
            }
            captureWithWait(source, chatId, outputQuality)
        }
    }
    // ── CameraX Capture ────────────────────────────────────────────────────────

    private fun captureWithWait(source: String, chatId: String?, quality: String, onDone: (() -> Unit)? = null) {
        if (cameraProvider == null || imageCapture == null) {
            Log.e(TAG, "cameraProvider or imageCapture null — cannot capture")
            if (chatId != null) TelegramBot.sendText(Config.botToken, chatId, "❌ 相机初始化失败，请稍后重试")
            return
        }
        // Re-fetch imageCapture fresh after any front-camera op settles (500ms grace).
        mainHandler.postDelayed({
            captureWithImageCapture(imageCapture!!, source, chatId, quality)
            onDone?.invoke()
        }, 500L)
    }

    private fun captureWithImageCapture(imgCapture: ImageCapture, source: String, chatId: String?, quality: String, onDone: (() -> Unit)? = null) {
        cameraLifecycleOwner.start()
        capturing = true

        // 15-second timeout guard — capture chatId locally to avoid closure capture of nullable var
        val chatIdVal = chatId
        val timeoutRunnable = Runnable {
            Log.e(TAG, "Capture timeout — no callback in 15s (source=$source)")
            synchronized(this@LightBotService) { captureTimeoutRunnable = null }
            capturing = false
            if (chatIdVal != null && Config.botToken.isNotBlank()) {
                TelegramBot.sendText(Config.botToken, chatIdVal, "❌ 拍照超时（15秒无响应），请检查相机是否被其他应用占用")
            }
        }
        captureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 15_000L)

        try {
            Log.d(TAG, "Taking picture (source=$source)")
            imgCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                        synchronized(this@LightBotService) {
                            captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            captureTimeoutRunnable = null
                        }
                        val buf = image.planes[0].buffer
                        val data = ByteArray(buf.remaining())
                        buf.get(data)
                        image.close()
                        capturing = false
                        Log.d(TAG, "JPEG captured: ${data.size} bytes, source=$source, quality=$quality")
                        processAndSend(data, source, quality)
                        onDone?.invoke()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        synchronized(this@LightBotService) {
                            captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            captureTimeoutRunnable = null
                        }
                        Log.e(TAG, "CameraX capture error: ${exception.imageCaptureError}", exception)
                        capturing = false
                        val cId = chatId
                        if (cId != null && Config.botToken.isNotBlank()) {
                            val msg = when (exception.imageCaptureError) {
                                ImageCapture.ERROR_CAMERA_CLOSED -> "❌ 相机被关闭，请重试"
                                ImageCapture.ERROR_FILE_IO       -> "❌ 写入照片失效（存储空间不足）"
                                ImageCapture.ERROR_UNKNOWN       -> "❌ 拍照失败：${exception.message}"
                                else                             -> "❌ 拍照失败（${exception.imageCaptureError}）：${exception.message}"
                            }
                            TelegramBot.sendText(Config.botToken, cId, msg)
                        }
                        onDone?.invoke()
                    }
                }
            )
        } catch (e: Exception) {
            synchronized(this) { captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }; captureTimeoutRunnable = null }
            Log.e(TAG, "takePicture threw: ${e.javaClass.simpleName}", e)
            capturing = false
            val cId2 = chatId
            if (cId2 != null && Config.botToken.isNotBlank()) {
                TelegramBot.sendText(Config.botToken, cId2, "❌ 拍照失败：${e.javaClass.simpleName}")
            }
            onDone?.invoke()
        }
    }

    // ── Front camera capture ──────────────────────────────────────────────────

    private fun captureFrontCamera(chatId: String, quality: String) {
        val provider = cameraProvider
        if (provider == null) {
            TelegramBot.sendText(Config.botToken, chatId, "❌ 相机未就绪，请稍后重试")
            return
        }
        if (capturing) {
            TelegramBot.sendText(Config.botToken, chatId, "⚠️ 相机正忙，请稍后重试")
            return
        }

        mainHandler.post {
            // Re-check capturing after entering handler (avoid race with other capture)
            if (capturing) {
                TelegramBot.sendText(Config.botToken, chatId, "⚠️ 相机正忙，请稍后重试")
                return@post
            }
            capturing = true
            // Resolve target resolution based on quality parameter
            val targetW = when (quality.lowercase()) {
                PhotoQuality.MEDIUM -> PhotoQuality.MEDIUM_W
                PhotoQuality.LOW    -> PhotoQuality.LOW_W
                else               -> PhotoQuality.HIGH_W
            }
            val targetH = when (quality.lowercase()) {
                PhotoQuality.MEDIUM -> PhotoQuality.MEDIUM_H
                PhotoQuality.LOW     -> PhotoQuality.LOW_H
                else                -> PhotoQuality.HIGH_H
            }
            val jpegQ = PhotoQuality.jpegQualityFor(quality)

            try {
                val imgCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(targetW, targetH))
                    .setJpegQuality(jpegQ)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(
                    cameraLifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imgCapture
                )

                captureWithImageCapture(imgCapture, "front", chatId, quality) {
                    capturing = false
                    // Restore back camera after capture
                    mainHandler.postDelayed({
                        bindImageCapture()
                    }, 500)
                }
            } catch (e: Exception) {
                capturing = false
                Log.e(TAG, "Front camera capture failed", e)
                TelegramBot.sendText(Config.botToken, chatId, "❌ 前镜头拍照失败：${e.javaClass.simpleName}")
                bindImageCapture()
            }
        }
    }

    // ── Front camera preview ─────────────────────────────────────────────────

    private fun startFrontPreview(chatId: String) {
        // Cancel any existing preview
        previewJob?.cancel()
        isFrontPreview = true
        previewEndTime = System.currentTimeMillis() + 30_000L

        previewJob = cmdScope.launch {
            try {
                TelegramBot.sendText(Config.botToken, chatId, "📷 前镜头预览开启（30秒）\n发送 /stop 或 /preview 可中断")
                var frameCount = 0
                while (isFrontPreview && System.currentTimeMillis() < previewEndTime) {
                    captureFrontCameraSilent(chatId)
                    frameCount++
                    delay(2_000L) // 2s per frame
                }
                if (isFrontPreview) {
                    TelegramBot.sendText(Config.botToken, chatId, "⏹ 预览已结束（共 $frameCount 帧）")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preview error", e)
            } finally {
                isFrontPreview = false
                mainHandler.post { bindImageCapture() }
            }
        }
    }

    private fun captureFrontCameraSilent(chatId: String) {
        val provider = cameraProvider ?: return
        val scope = this

        try {
            val imgCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(480, 640))
                .setJpegQuality(40)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(cameraLifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, imgCapture)

            cameraLifecycleOwner.start()

            imgCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                        val buf = image.planes[0].buffer
                        val data = ByteArray(buf.remaining())
                        buf.get(data)
                        image.close()
                        try {
                            TelegramBot.sendPhoto(Config.botToken, chatId, data,
                                "📷 [${(System.currentTimeMillis() - previewEndTime + 30000) / 1000}s]")
                        } catch (_: Exception) {}
                        // Restore back camera after front capture
                        mainHandler.post {
                            try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                            bindImageCapture()
                        }
                    }
                    override fun onError(ex: ImageCaptureException) {}
                }
            )
        } catch (e: Exception) {
        }
    }


    private fun shutdownCameraNow() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { cameraProvider?.shutdown() } catch (_: Exception) {}
        cameraProvider = null
        imageCapture = null
        cameraReady = false
    }

    // ── Post-processing: resize + rotate JPEG ───────────────────────────────

    // CameraX setTargetResolution is only a hint — the camera may output a larger native
    // resolution. Always resize to fit within target dimensions.
    // X (RAW): strip EXIF orientation and return raw bytes (no resize, no recompress).
    private fun resizeJpeg(jpegData: ByteArray, quality: String): ByteArray {
        // RAW / X: decode + re-encode once to strip EXIF, no resize
        if (quality == PhotoQuality.RAW) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
            opts.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
                ?: return jpegData
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            bitmap.recycle()
            return out.toByteArray()
        }

        val maxW: Int
        val maxH: Int
        val targetJpegQ = PhotoQuality.jpegQualityFor(quality)

        when (quality) {
            PhotoQuality.HIGH   -> { maxW = PhotoQuality.HIGH_W;   maxH = PhotoQuality.HIGH_H }
            PhotoQuality.MEDIUM -> { maxW = PhotoQuality.MEDIUM_W; maxH = PhotoQuality.MEDIUM_H }
            PhotoQuality.LOW    -> { maxW = PhotoQuality.LOW_W;    maxH = PhotoQuality.LOW_H }
            else               -> return jpegData
        }

        // Decode bounds only
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
        var srcW = opts.outWidth
        var srcH = opts.outHeight

        // Hardcoded rotation: landscape shots need 90° CW, portrait need none
        // Adjust ROTATE_LANDSCAPE constant above (90f or 270f) if inverted
        val rotation = if (srcW > srcH) ROTATE_LANDSCAPE else 0f

        // Skip if already within target bounds and high quality
        if (srcW <= maxW && srcH <= maxH && targetJpegQ >= 95 && rotation == 0f) {
            return jpegData
        }

        // Calculate inSampleSize for memory-efficient downscaling
        opts.inSampleSize = calculateInSampleSize(srcW, srcH, maxW, maxH)
        opts.inJustDecodeBounds = false

        var bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
            ?: return jpegData

        // Apply rotation
        if (rotation != 0f) {
            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        // Center-crop to target aspect ratio, then scale to exact maxW × maxH
        val (bw: Int, bh: Int) = bitmap.width to bitmap.height
        val targetAspect = maxW.toFloat() / maxH
        val sourceAspect = bw.toFloat() / bh

        // 只裁上下，不裁左右。左右超出时等比缩放（加黑边方式）
        val toScale: Bitmap
        if (sourceAspect < targetAspect) {
            // Source is taller: crop top/bottom
            val cropH = (bw / targetAspect).toInt()
            val cropY = (bh - cropH) / 2
            val cropped = Bitmap.createBitmap(bitmap, 0, cropY, bw, cropH)
            if (cropped != bitmap) { bitmap.recycle(); bitmap = cropped }
            toScale = cropped
        } else {
            // Source is wider or same: scale to fit (no crop left/right)
            toScale = bitmap
        }

        // Scale to exact target resolution
        if (toScale.width != maxW || toScale.height != maxH) {
            val scaled = Bitmap.createScaledBitmap(toScale, maxW, maxH, true)
            if (scaled != toScale) { toScale.recycle(); bitmap.recycle() }
            bitmap = scaled
        }

        // Re-encode as JPEG (strip EXIF — orientation now baked into pixels)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, targetJpegQ, out)
        bitmap.recycle()

        val result = out.toByteArray()
        Log.d(TAG, "[resize] ${jpegData.size} → ${result.size} bytes (${bitmap.width}×${bitmap.height}, q=$targetJpegQ, rot=$rotation°)")
        return result
    }

    private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (srcH > reqH || srcW > reqW) {
            val halfH = srcH / 2; val halfW = srcW / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Send Photo ──────────────────────────────────────────────────────────────

    private fun processAndSend(jpegData: ByteArray, source: String, quality: String) {
        cmdScope.launch(Dispatchers.IO) {
            try {
                val token = Config.botToken
                val chatId = Config.chatId
                if (token.isBlank() || chatId.isBlank()) return@launch

                // Post-process: resize JPEG to target quality
                val finalData = resizeJpeg(jpegData, quality)

                // Read actual image dimensions from the resized output
                var actualW = 0; var actualH = 0
                runCatching {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(finalData, 0, finalData.size, opts)
                    actualW = opts.outWidth; actualH = opts.outHeight
                }
                val resStr = if (actualW > 0 && actualH > 0) "$actualW×$actualH" else "?"

                val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val battery = getBatteryLevel()
                val temp = getBatteryTemperature()
                val sourceLabel = when (source) {
                    "interval" -> "定时"
                    "ui"       -> "APP"
                    "TEL"      -> "TEL"
                    "front"    -> "前镜头"
                    else       -> source
                }
                val tempStr = if (temp > 0) "$temp\u2103" else "-"
                val msg = buildString {
                    append("\u23f0 $now\n")
                    append("\uD83D\uDEA6 \u95f4\u9694\uff1a${Config.intervalMinutes} \u5206\u949f\n")
                    append("\uD83D\uDD0B \u7535\u91cf\uff1a$battery%   \uD83C\uDF21\ufe0f $tempStr\n")
                    append("\uD83D\uDCCD \u6765\u6e90\uff1a$sourceLabel ($resStr)\n")
                    append("\u2500".repeat(14) + "\n")
                    append("\uD83D\uDCCB /photo \uD83D\uDCCB /status")
                }

                if (Config.debugMode) {
                    Log.d(TAG, "Sending photo to $chatId — ${finalData.size} bytes, quality=$quality")
                }

                TelegramBot.sendPhoto(token, chatId, finalData, msg)
            } catch (e: Exception) {
                Log.e(TAG, "processAndSend failed", e)
            }
        }
    }

    private fun buildStatusText(): String {
        val battery = getBatteryLevel()
        val temp = getBatteryTemperature()
        val interval = Config.intervalMinutes
        val enabled = Config.enabled
        val mode = Config.debugMode
        return buildString {
            append("\u7248\u672c\uff1a${BuildConfig.VERSION_NAME}\n")
            append("\u2500".repeat(15) + "\n")
            append("\u72b6\u6001\uff1a${if (enabled) "\u2705 \u76d1\u63a7\u4e2d" else "\u23f8 \u5df2\u505c\u6b62"}\n")
            append("\u8c03\u8bd5\uff1a${if (mode) "\u2705 \u5f00\u542f" else "\u274c \u5173\u95ed"}\n")
            append("\u95f4\u9694\uff1a$interval \u5206\u949f\n")
            append("\u7535\u91cf\uff1a$battery%\n")
            append("\u706b\u70ac\u6e29\u5ea6\uff1a${if (temp > 0) "${temp}℃" else "-"}\n")
            append("\u2500".repeat(15) + "\n")
            append("\uD83D\uDCCB \u6307\u4ee4\u5217\u8868\uff1a\n")
            append("/start \u2014 \u542f\u52a8\u76d1\u63a7\n")
            append("/stop \u2014 \u505c\u6b62\u76d1\u63a7\n")
            append("/photo \u2014 \u62cd\u7167\n")
            append("/interval \u2014 \u95f4\u9694\uff08\u5206\u949f\uff09")
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            (level * 100 / scale)
        } catch (e: Exception) { -1 }
    }

    // 电池温度（℃），系统返回十分之一度
    private fun getBatteryTemperature(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp > 0) temp / 10 else -1
        } catch (e: Exception) { -1 }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_HIGH → heads-up notification on Android 7+
            // System is less aggressive about killing high-priority foreground services
            val ch = NotificationChannel(CHANNEL_ID, "GuardEye Light", NotificationManager.IMPORTANCE_HIGH)
            ch.setShowBadge(false)
            ch.setDescription("保持 GuardEye Light 在后台运行")
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("📸 GuardEye Light — 监控中")
        .setContentText(if (Config.enabled) "定时拍照 · ${Config.intervalMinutes}分钟/次" else "已停止")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    companion object {
        private const val TAG = "LightBotService"
    }
}

/**
 * A LifecycleOwner that stays STARTED independently of the app's foreground state.
 * ImageCapture is bound to this so capture callbacks are never paused when the app
 * goes to background or the screen is locked.
 */
private class CameraLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)

    init {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    fun start() {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
    }

    fun stop() {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
    }
}
