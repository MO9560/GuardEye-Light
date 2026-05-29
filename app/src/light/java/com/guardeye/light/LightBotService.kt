package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LightBotService — GuardEye Light (CameraX)
 * Telegram polling + CameraX capture in one foreground service.
 * ImageCapture is bound once at startup and reused for all captures.
 * CameraLifecycleOwner stays STARTED independent of app foreground state.
 */
class LightBotService : LifecycleService() {

    // ── CameraX — bound once, reused for all captures ─────────────────
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var capturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureTimeoutRunnable: Runnable? = null
    private var cameraReady = false  // true when ImageCapture is bound and ready

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

    // ── Foreground notification ─────────────────────────────────────
    private val CHANNEL_ID = "guardeye_light_bot"
    private val NOTIF_ID = 2001

    // ─────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()

        // Keep camera lifecycle alive for the lifetime of this service
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cameraLifecycleOwner.stop()
                shutdownCameraNow()
            }
        })

        // Initialize camera once at startup — matches SentinelService pattern:
        // all CameraX calls run on main thread via addListener callback
        initCameraProvider()
    }

    /**
     * Initialize ProcessCameraProvider using addListener (SentinelService pattern).
     * All CameraX operations happen inside the callback on the main thread.
     */
    private fun initCameraProvider() {
        if (cameraProvider != null) {
            bindImageCapture()
            return
        }
        Log.d(TAG, "[main] initCameraProvider — requesting ProcessCameraProvider")
        val future = ProcessCameraProvider.getInstance(this)
        // Callback runs on main thread (ContextCompat.getMainExecutor) — matches SentinelService
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

    /**
     * Build ImageCapture and bind to cameraLifecycleOwner.
     * Called from initCameraProvider's callback — runs on main thread.
     */
    private fun bindImageCapture() {
        val provider = cameraProvider ?: return
        if (cameraReady) return  // already bound

        @Suppress("DEPRECATION")
        val imgCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(android.util.Size(1280, 720))
            .setJpegQuality(80)
            .build()
        imageCapture = imgCapture

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                cameraLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imgCapture
            )
            cameraReady = true
            cameraLifecycleOwner.start()
            Log.d(TAG, "[main] ImageCapture bound to cameraLifecycleOwner — camera ready")
        } catch (e: Exception) {
            Log.e(TAG, "[main] Failed to bind ImageCapture", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
            else -> startPolling()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        cmdScope.cancel()
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        cameraExecutor.shutdown()
        cameraProvider?.shutdown()
        cameraLifecycleOwner.stop()
        super.onDestroy()
    }

    // ── Telegram Polling — WakeLock keeps this alive when screen is off ──────

    private fun startPolling() {
        pollingJob?.cancel()
        wakeLock.acquire(10 * 60 * 1000L) // 10 min, renewed by keep-alive

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
        if (wakeLock.isHeld) wakeLock.release()
    }

    // ── Telegram Commands ──────────────────────────────────────────────

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
                TelegramBot.sendText(token, chatId, buildStatusText())
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

    // ── CameraX Capture — reuses pre-bound ImageCapture ─────────────────

    private fun captureAndSend(source: String, chatId: String?) {
        if (capturing) {
            if (source == "command" && chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "⚠️ 相机正忙，请稍后重试")
            }
            return
        }
        // All CameraX calls on main thread — required by CameraX contract
        mainHandler.post {
            // If camera not ready yet, trigger init; capture will succeed once bound
            if (!cameraReady) {
                Log.d(TAG, "Camera not ready — forcing init from capture path")
                initCameraProvider()
            }
            captureWithWait(source, chatId)
        }
    }

    /** Capture using the pre-bound ImageCapture instance. Runs on main thread. */
    private fun captureWithWait(source: String, chatId: String?) {
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
                        Log.d(TAG, "JPEG captured: ${data.size} bytes, source=$source")
                        processAndSend(data, source)
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

    // ── Send Photo to Telegram ─────────────────────────────────────────

    private fun processAndSend(jpegData: ByteArray, source: String) {
        cmdScope.launch(Dispatchers.IO) {
            try {
                val token = Config.botToken
                val chatId = Config.chatId
                if (token.isBlank() || chatId.isBlank()) return@launch

                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val battery = getBatteryLevel()
                val msg = buildString {
                    append("📸 GuardEye Light\n")
                    append("⏰ $now\n")
                    append("🔋 电量：$battery%\n")
                    append("📍 来源：${if (source == "interval") "定时" else "手动"}")
                }

                if (Config.debugMode) {
                    Log.d(TAG, "Sending photo to $chatId — ${jpegData.size} bytes")
                }

                TelegramBot.sendPhoto(token, chatId, jpegData, msg)
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
        return buildString {
            append("📸 GuardEye Light\n")
            append("─────────────────\n")
            append("状态：${if (enabled) "✅ 监控中" else "⏸ 已停止"}\n")
            append("间隔：$interval 分钟\n")
            append("电量：$battery%\n")
            append("调试：${if (mode) "🐛 开启" else "❌ 关闭"}\n")
            append("版本：${BuildConfig.VERSION_NAME}")
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
