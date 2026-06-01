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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ── Photo quality tiers ─────────────────────────────────────────────────────
// CameraX always shoots at HIGH (1920×1080); post-processing resizes/compresses.
object PhotoQuality {
    const val HIGH   = "high"
    const val MEDIUM = "medium"
    const val LOW    = "low"

    // Target display sizes (used for post-processing resize)
    const val HIGH_W   = 1920; const val HIGH_H   = 1080
    const val MEDIUM_W = 1280; const val MEDIUM_H = 720
    const val LOW_W    = 854;  const val LOW_H    = 480

    // JPEG quality passed to compressor
    fun jpegQualityFor(quality: String): Int = when (quality.lowercase()) {
        HIGH   -> 95
        MEDIUM -> 70
        LOW    -> 50
        else   -> 95
    }

    fun labelFor(quality: String): String = when (quality.lowercase()) {
        HIGH   -> "1920×1080"
        MEDIUM -> "1280×720"
        LOW    -> "854×480"
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
            text.startsWith("/photo") -> {
                val raw = text.removePrefix("/photo").trim().lowercase()
                val quality = when (raw) {
                    "h", "high"   -> PhotoQuality.HIGH
                    "m", "medium" -> PhotoQuality.MEDIUM
                    "l", "low"    -> PhotoQuality.LOW
                    else           -> PhotoQuality.HIGH
                }
                val label = PhotoQuality.labelFor(quality)
                TelegramBot.sendText(token, chatId, "📸 正在拍照... [$label]")
                captureAndSend(source = "command", chatId = chatId, quality = quality)
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

    // ── CameraX Capture ────────────────────────────────────────────────────────

    private fun captureAndSend(source: String, chatId: String?, quality: String = PhotoQuality.HIGH) {
        // Map capture source to output quality:
        //   interval → MEDIUM (定时拍照，节省流量)
        //   ui       → LOW    (App内拍照，低分辨率)
        //   command  → uses the quality arg passed in (h/m/l, default HIGH)
        val outputQuality = when (source) {
            "interval" -> PhotoQuality.MEDIUM
            "ui"      -> PhotoQuality.LOW
            else       -> quality
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

    private fun captureWithWait(source: String, chatId: String?, quality: String) {
        // CameraX always shoots at HIGH; post-processing handles resize/compress.
        // No rebind needed — stable single-session capture.

        val imgCapture = imageCapture
        if (cameraProvider == null || imgCapture == null) {
            Log.e(TAG, "cameraProvider or imageCapture null — cannot capture")
            if (chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "❌ 相机初始化失败，请稍后重试")
            }
            return
        }

        cameraLifecycleOwner.start()
        capturing = true

        // 15-second timeout guard
        val timeoutRunnable = Runnable {
            Log.e(TAG, "Capture timeout — no callback in 15s (source=$source)")
            synchronized(this) { captureTimeoutRunnable = null }
            capturing = false
            if (chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "❌ 拍照超时（15秒无响应），请检查相机是否被其他应用占用")
            }
        }
        captureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 15_000L)

        try {
            Log.d(TAG, "Taking picture with pre-bound ImageCapture (source=$source)")
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
                    }

                    override fun onError(exception: ImageCaptureException) {
                        synchronized(this@LightBotService) {
                            captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            captureTimeoutRunnable = null
                        }
                        Log.e(TAG, "CameraX capture error: ${exception.imageCaptureError}", exception)
                        capturing = false
                        if (chatId != null) {
                            val msg = when (exception.imageCaptureError) {
                                ImageCapture.ERROR_CAMERA_CLOSED -> "❌ 相机被关闭，请重试"
                                ImageCapture.ERROR_FILE_IO       -> "❌ 写入照片失败（存储空间不足？）"
                                ImageCapture.ERROR_UNKNOWN       -> "❌ 拍照失败：${exception.message}"
                                else                             -> "❌ 拍照失败（${exception.imageCaptureError}）：${exception.message}"
                            }
                            TelegramBot.sendText(Config.botToken, chatId, msg)
                        }
                    }
                }
            )
        } catch (e: Exception) {
            synchronized(this) { captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }; captureTimeoutRunnable = null }
            Log.e(TAG, "takePicture threw: ${e.javaClass.simpleName}", e)
            capturing = false
            if (chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "❌ 拍照失败：${e.javaClass.simpleName}")
            }
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
    private fun resizeJpeg(jpegData: ByteArray, quality: String): ByteArray {
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

        // Read EXIF rotation and apply it so the Bitmap has correct orientation
        var exifRotation = 0f
        try {
            val exif = ExifInterface(java.io.ByteArrayInputStream(jpegData))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            exifRotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (exifRotation != 0f) {
                // Swap width/height for 90/270 degree rotations
                srcW = opts.outHeight; srcH = opts.outWidth
            }
        } catch (_: Exception) {}

        // Skip if already within target bounds and high quality
        if (srcW <= maxW && srcH <= maxH && targetJpegQ >= 95 && exifRotation == 0f) {
            return jpegData
        }

        // Calculate inSampleSize for memory-efficient downscaling
        opts.inSampleSize = calculateInSampleSize(srcW, srcH, maxW, maxH)
        opts.inJustDecodeBounds = false

        var bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
            ?: return jpegData

        // Apply EXIF rotation
        if (exifRotation != 0f) {
            val matrix = Matrix().apply { postRotate(exifRotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            bitmap = rotated
        }

        // Aspect-ratio-preserving scale: fit within maxW × maxH
        val (bw: Int, bh: Int) = bitmap.width to bitmap.height
        val scale = minOf(maxW.toFloat() / bw, maxH.toFloat() / bh)
        if (scale < 1f) {
            val dstW = (bw * scale).toInt()
            val dstH = (bh * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
            if (scaled != bitmap) bitmap.recycle()
            bitmap = scaled
        }

        // Re-encode as JPEG (strip EXIF — orientation now baked into pixels)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, targetJpegQ, out)
        bitmap.recycle()

        val result = out.toByteArray()
        Log.d(TAG, "[resize] ${jpegData.size} → ${result.size} bytes (${bitmap.width}×${bitmap.height}, q=$targetJpegQ, rot=$exifRotation°)")
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

                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val battery = getBatteryLevel()
                val resLabel = PhotoQuality.labelFor(quality)
                val sourceLabel = when (source) {
                    "interval" -> "定时"
                    "ui"      -> "App"
                    else       -> "手动"
                }
                val msg = buildString {
                    append("📸 GuardEye Light\n")
                    append("⏰ $now\n")
                    append("🔋 电量：$battery%\n")
                    append("📍 来源：$sourceLabel\n")
                    append("─────────────────\n")
                    append("📋 /photo （$resLabel）\n")
                    append("📋 /status")
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
        val interval = Config.intervalMinutes
        val enabled = Config.enabled
        val mode = Config.debugMode
        val wlStatus = if (wakeLock.isHeld) "✅ 已持有" else "⚠️ 未持有"
        return buildString {
            append("📸 GuardEye Light\n")
            append("─────────────────\n")
            append("状态：${if (enabled) "✅ 监控中" else "⏸ 已停止"}\n")
            append("间隔：$interval 分钟\n")
            append("电量：$battery%\n")
            append("WakeLock：$wlStatus\n")
            append("调试：${if (mode) "🐛 开启" else "❌ 关闭"}\n")
            append("版本：${BuildConfig.VERSION_NAME}\n")
            append("─────────────────\n")
            append("📋 指令列表：\n")
            append("/start — 启动监控\n")
            append("/stop  — 停止监控\n")
            append("/photo [high|medium|low] — 拍照\n")
            append("/status — 状态\n")
            append("/interval N — 间隔(分钟)\n")
            append("/battery — 电池优化设置\n")
            append("/debug [on|off] — 调试模式")
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
        const val ACTION_CAPTURE        = "com.guardeye.light.ACTION_CAPTURE"
        const val ACTION_MANUAL_CAPTURE = "com.guardeye.light.ACTION_MANUAL_CAPTURE"
        const val ACTION_STOP           = "com.guardeye.light.ACTION_STOP"
        const val ACTION_REQUEST_BATTERY = "com.guardeye.light.ACTION_REQUEST_BATTERY"
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
