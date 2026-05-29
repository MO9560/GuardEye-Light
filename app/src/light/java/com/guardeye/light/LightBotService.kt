package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LightBotService — GuardEye Light (CameraX)
 * Merges Telegram polling + CameraX capture into one foreground service.
 * ProcessCameraProvider is cached at service level to avoid async race conditions
 * across multiple captures. CameraX binds to CameraLifecycleOwner (stays STARTED
 * regardless of app foreground state).
 */
class LightBotService : LifecycleService() {

    // ── CameraX — cached provider so repeated captures are fast & reliable ──
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var capturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureTimeoutRunnable: Runnable? = null

    // ── Camera lifecycle — stays STARTED independent of app foreground/background ──
    private val cameraLifecycleOwner = CameraLifecycleOwner()

    // ── Telegram polling ──────────────────────────────────────────────
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
        initCameraProvider()   // Get + cache ProcessCameraProvider once

        // Keep camera lifecycle alive for the lifetime of this service
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cameraLifecycleOwner.stop()
                shutdownCameraNow()
            }
        })
    }

    /** Synchronously get (or wait for) the cached ProcessCameraProvider. */
    private fun initCameraProvider() {
        if (cameraProvider != null) return
        try {
            val future = ProcessCameraProvider.getInstance(this)
            cameraProvider = future.get(30, TimeUnit.SECONDS)
            Log.d(TAG, "CameraProvider ready: $cameraProvider")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CameraProvider in onCreate", e)
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

    private fun waitForCameraProvider(timeoutMs: Long): Long {
        if (cameraProvider != null) return 0
        var waited = 0L
        while (cameraProvider == null && waited < timeoutMs) {
            Thread.sleep(100)
            waited += 100
        }
        return waited
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

    // ── Telegram Polling ─────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        wakeLock.acquire(10 * 60 * 1000L) // 10 min, renew as needed

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

    private fun captureAndSend(source: String, chatId: String?) {
        if (capturing) {
            if (source == "command" && chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "⚠️ 相机正忙，请稍后重试")
            }
            return
        }
        // Always post to main thread — CameraX requires main thread for ProcessCameraProvider.
        // Matches SentinelService pattern: getInstance() called from main thread.
        mainHandler.post { captureWithWait(source, chatId) }
    }

    /**
     * Initialize cameraProvider on the main thread (CameraX requirement).
     * Calls back to the main thread, waits for the ListenableFuture to resolve,
     * then stores the real ProcessCameraProvider in cameraProvider.
     * Returns true if init succeeded, false otherwise.
     */
    private fun syncInitCamera(): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        mainHandler.post {
            try {
                if (cameraProvider != null) {
                    ok.set(true)
                    latch.countDown()
                    return@post
                }
                Log.d(TAG, "[main] initializing cameraProvider now")
                val future = ProcessCameraProvider.getInstance(this@LightBotService)
                future.addListener({
                    try {
                        cameraProvider = future.get()
                        cameraLifecycleOwner.start()
                        Log.d(TAG, "[main] cameraProvider ready: $cameraProvider")
                        ok.set(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "[main] future.get() failed", e)
                        ok.set(false)
                    } finally {
                        latch.countDown()
                    }
                }, ContextCompat.getMainExecutor(this@LightBotService))
            } catch (e: Exception) {
                Log.e(TAG, "[main] syncInitCamera failed", e)
                ok.set(false)
                latch.countDown()
            }
        }
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                Log.e(TAG, "syncInitCamera timed out")
                return false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return ok.get()
    }

    private fun captureWithWait(source: String, chatId: String?) {
        // Ensure cameraProvider is initialized on the main thread (CameraX requirement)
        if (cameraProvider == null) {
            Log.d(TAG, "cameraProvider not ready — syncing to main thread for init")
            if (!syncInitCamera()) {
                if (chatId != null) {
                    TelegramBot.sendText(Config.botToken, chatId, "❌ 相机初始化失败，请稍后重试")
                }
                return
            }
        }
        val provider = cameraProvider!!

        capturing = true

        // 15-second timeout — if no callback, abort and notify user
        val timeoutRunnable = Runnable {
            Log.e(TAG, "Capture timeout — no callback in 15s (source=$source)")
            // Don't call shutdownCamera() here — it would conflict with an in-flight callback
            // Just set a flag so the callback knows it was a timeout
            synchronized(this) {
                captureTimeoutRunnable = null   // signals the callback it was a timeout
            }
            capturing = false
            if (chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "❌ 拍照超时（15秒无响应），请检查相机是否被其他应用占用")
            }
        }
        captureTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, 15_000L)

        try {
            provider.unbindAll()

            @Suppress("DEPRECATION")
            val imgCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(android.util.Size(1280, 720))
                .setJpegQuality(80)
                .build()
            imageCapture = imgCapture

            // Bind to cameraLifecycleOwner — stays STARTED even when app is in background
            provider.bindToLifecycle(cameraLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imgCapture)
            Log.d(TAG, "CameraX bound, taking picture (source=$source)")
            imgCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                        // Cancel timeout if it hasn't fired yet
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
                        val isTimeout = (chatId == null)  // timeout path sets chatId=null as signal
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
            synchronized(this@LightBotService) {
                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                captureTimeoutRunnable = null
            }
            Log.e(TAG, "CameraX setup threw: ${e.javaClass.simpleName}", e)
            capturing = false
            if (chatId != null) {
                TelegramBot.sendText(Config.botToken, chatId, "❌ 相机初始化失败：${e.javaClass.simpleName} — ${e.message}")
            }
        }
    }

    private fun shutdownCameraNow() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { cameraProvider?.shutdown() } catch (_: Exception) {}
        cameraProvider = null
        imageCapture = null
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
                Log.d(TAG, "Sending photo: ${jpegData.size} bytes, caption: $caption")
                val sendResult = TelegramBot.sendPhoto(token, chatId, jpegData, caption)
                if (sendResult.isFailure) {
                    Log.e(TAG, "sendPhoto failed: ${sendResult.exceptionOrNull()?.message}")
                    if (Config.debugMode && chatId.isNotBlank()) {
                        TelegramBot.sendText(token, chatId,
                            "🐛 [DEBUG] sendPhoto 失败：${sendResult.exceptionOrNull()?.message}")
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
            "interval" -> "定时拍照"
            "ui"      -> "APP拍照"
            else       -> "TEL拍照"
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

/**
 * A LifecycleOwner that stays STARTED independently of the app's foreground state.
 * CameraX binds to this so ImageCapture callbacks are never paused when the app
 * goes to background. The owner is started when the service starts and stopped
 * when the service is destroyed.
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
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
    }
}
