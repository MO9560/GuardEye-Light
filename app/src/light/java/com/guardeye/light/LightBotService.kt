package com.guardeye.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.guardeye.BuildConfig
import com.guardeye.Config
import com.guardeye.TelegramBot
import kotlinx.coroutines.*

// ── Intent actions ────────────────────────────────────────────────────────────
const val ACTION_CAPTURE         = "com.guardeye.light.ACTION_CAPTURE"
const val ACTION_MANUAL_CAPTURE  = "com.guardeye.light.ACTION_MANUAL_CAPTURE"
const val ACTION_STOP            = "com.guardeye.light.ACTION_STOP"
const val ACTION_REQUEST_BATTERY = "com.guardeye.light.ACTION_REQUEST_BATTERY"

// ── Photo quality tiers ──────────────────────────────────────────────────────
object PhotoQuality {
    const val HIGH   = "h"
    const val MEDIUM = "m"
    const val LOW    = "l"
    const val RAW    = "x"

    const val HIGH_W   = 1920; const val HIGH_H   = 1080
    const val MEDIUM_W = 1280; const val MEDIUM_H = 720
    const val LOW_W    = 854;  const val LOW_H    = 480

    fun jpegQualityFor(quality: String): Int = when (quality.lowercase()) {
        HIGH   -> 95
        MEDIUM -> 70
        LOW    -> 50
        RAW    -> 95
        else   -> 50
    }

    fun labelFor(quality: String): String = when (quality.lowercase()) {
        HIGH   -> "1920x1080"
        MEDIUM -> "1280x720"
        LOW    -> "854x480"
        RAW    -> "原图"
        else   -> "854x480"
    }

    fun sourceDefault(): String = LOW
}

// ── Source labels ────────────────────────────────────────────────────────────
private fun sourceLabelOf(source: String): String = when (source) {
    "interval" -> "定时"
    "ui"       -> "APP"
    "TEL"      -> "远控"
    "front"    -> "前镜头"
    else       -> source
}

// ── LightBotService ──────────────────────────────────────────────────────────
class LightBotService : LifecycleService() {

    // ── Camera service reference ──────────────────────────────────────────
    // MUST be accessed AFTER instance creation (after onCreate).
    private val cameraSvc: CameraForegroundService?
        get() = CameraForegroundService.instance

    // ── Coroutine scopes — separate to isolate failure domains ──────────────
    private val cmdScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureScope = CoroutineScope(newSingleThreadContext("CaptureScope") + SupervisorJob())

    // ── Telegram polling ─────────────────────────────────────────────────
    private var pollingJob: Job? = null

    // ── Foreground notification ───────────────────────────────────────────
    private val CHANNEL_ID = "guardeye_light_bot"
    private val NOTIF_ID   = 2001

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        ServiceStatus.setBotPolling(false)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        bindCameraService()
        startPolling()
        ServiceStatus.setMonitoring(Config.enabled, Config.intervalMinutes)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_CAPTURE, ACTION_MANUAL_CAPTURE -> {
                val source = if (intent.action == ACTION_CAPTURE) "interval" else "ui"
                triggerCapture(source = source)
            }
            ACTION_STOP -> {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REQUEST_BATTERY -> {
                requestIgnoreBatteryOptimizations()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        cmdScope.cancel()
        captureScope.cancel()
        stopCameraService()
        super.onDestroy()
    }

    // ── Bind / unbind camera service ──────────────────────────────────────

    private fun bindCameraService() {
        if (CameraForegroundService.instance == null) {
            val intent = Intent(this, CameraForegroundService::class.java)
            startForegroundService(intent)
            Log.d(TAG, "bindCameraService: started CameraForegroundService")
        }
    }

    private fun stopCameraService() {
        stopService(Intent(this, CameraForegroundService::class.java))
        Log.d(TAG, "stopCameraService: stopped CameraForegroundService")
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "GuardEye Light", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(false)
                description = "GuardEye Light monitoring service"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle("[GuardEye Light] 监控中")
        .setContentText(
            if (Config.enabled) "定时 · ${Config.intervalMinutes}分钟/次"
            else "已停止"
        )
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    // ── Telegram polling ─────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = cmdScope.launch {
            while (true) {
                try {
                    val token  = Config.botToken
                    val chatId = Config.chatId
                    if (token.isBlank() || chatId.isBlank()) {
                        delay(30_000L)
                        continue
                    }
                    val result = TelegramBot.fetchUpdates(token, Config.botOffset)
                    result.getOrNull()?.let { updates ->
                        for (update in updates) {
                            Config.botOffset = update.updateId + 1
                            ServiceStatus.recordCommand()
                            cmdScope.launch(Dispatchers.IO) {
                                handleCommand(update.text, update.chatId)
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
        ServiceStatus.setBotPolling(false)
    }

    // ── Battery whitelist ────────────────────────────────────────────────

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
            Log.e(TAG, "Cannot open battery settings", e)
        }
    }

    // ── Command router ───────────────────────────────────────────────────

    private fun handleCommand(text: String, chatId: String) {
        val token = Config.botToken
        if (token.isBlank() || chatId.isBlank()) return

        when {
            text == "/start" -> {
                Config.enabled = true
                ServiceStatus.setMonitoring(true, Config.intervalMinutes)
                LightAlarmReceiver.scheduleNextAlarm(this, Config.intervalMinutes)
                TelegramBot.sendText(token, chatId,
                    "[GuardEye Light 已启动]\n" +
                    "间隔：${Config.intervalMinutes}分钟\n" +
                    "提示：建议关闭电池优化以提高稳定性"
                )
            }

            text == "/stop" -> {
                Config.enabled = false
                ServiceStatus.setMonitoring(false, 0)
                LightAlarmReceiver.cancelAlarm(this)
                stopCameraService()
                TelegramBot.sendText(token, chatId, "[已停止监控]")
            }

            text == "/status" -> {
                TelegramBot.sendText(token, chatId, buildStatusText())
            }

            text == "/status2" -> {
                TelegramBot.sendText(token, chatId, buildDetailedStatusText())
            }

            text.startsWith("/photo") -> {
                val raw        = text.removePrefix("/photo").trim().lowercase()
                val isFront    = raw.startsWith("f ") || raw == "f"
                val qualityRaw = if (isFront) raw.removePrefix("f").trim() else raw
                val quality    = resolveQuality(qualityRaw)

                if (isFront) {
                    TelegramBot.sendText(token, chatId, "[前镜头拍照中...]")
                    triggerCapture(source = "front", isFront = true, quality = quality)
                } else {
                    TelegramBot.sendText(token, chatId, "[正在拍照...]")
                    triggerCapture(source = "TEL", isFront = false, quality = quality)
                }
            }

            text == "/battery" -> {
                TelegramBot.sendText(token, chatId,
                    "[正在打开电池优化设置]\n请选择「不限」或「不优化」以确保后台稳定运行"
                )
                startForegroundService(Intent(this, LightBotService::class.java).apply {
                    action = ACTION_REQUEST_BATTERY
                })
            }

            text.startsWith("/interval ") -> {
                val mins = text.removePrefix("/interval ").trim().toIntOrNull()
                if (mins != null && mins in 1..60) {
                    Config.intervalMinutes = mins
                    ServiceStatus.setMonitoring(Config.enabled, mins)
                    if (Config.enabled) LightAlarmReceiver.scheduleNextAlarm(this, mins)
                    TelegramBot.sendText(token, chatId, "[间隔已设为 $mins 分钟]")
                } else {
                    TelegramBot.sendText(token, chatId, "用法：/interval 1-60")
                }
            }

            text.startsWith("/debug") -> {
                val mode = text.removePrefix("/debug").trim().lowercase()
                Config.debugMode = (mode.isEmpty() || mode == "on")
                startForegroundService(Intent(this, LightBotService::class.java))
                TelegramBot.sendText(token, chatId,
                    if (Config.debugMode) "[调试模式开启]" else "[调试模式关闭]"
                )
            }

            text == "/test" -> {
                TelegramBot.sendText(token, chatId,
                    "[GuardEye Light 正常运行] 版本：${BuildConfig.VERSION_NAME}"
                )
            }
        }
    }

    // ── Quality resolution ───────────────────────────────────────────────

    private fun resolveQuality(raw: String): String = when (raw) {
        "h" -> PhotoQuality.HIGH
        "m" -> PhotoQuality.MEDIUM
        "l" -> PhotoQuality.LOW
        "x" -> PhotoQuality.RAW
        else -> PhotoQuality.sourceDefault()
    }

    // ── Capture trigger ─────────────────────────────────────────────────

    private fun triggerCapture(source: String, isFront: Boolean = false, quality: String = "") {
        val resolvedQuality = if (quality.isNotEmpty()) quality else PhotoQuality.sourceDefault()
        bindCameraService()

        // Run capture on a dedicated single thread to avoid blocking IO pool.
        // CountDownLatch.await() in CameraForegroundService would otherwise hold
        // an IO thread indefinitely while waiting for the camera response.
        captureScope.launch {
            try {
                // Wait for camera service + camera init (max 8 s)
                repeat(16) {
                    if (cameraSvc?.isReady() == true) return@repeat
                    delay(500)
                }
                if (cameraSvc?.isReady() != true) {
                    val reason = if (cameraSvc == null) "服务未启动" else "相机未初始化"
                    Log.e(TAG, "triggerCapture: $reason")
                    withContext(Dispatchers.Main) {
                        TelegramBot.sendText(Config.botToken, Config.chatId,
                            "[拍照失败，请稍后重试] ($reason)")
                    }
                    return@launch
                }

                ServiceStatus.recordCaptureStart()

                val data: ByteArray = if (isFront) {
                    ServiceStatus.recordFrontCaptureStart()
                    cameraSvc!!.captureFrontPhoto(resolvedQuality)
                } else {
                    cameraSvc!!.captureBackPhoto(resolvedQuality)
                }

                if (data.isEmpty()) {
                    Log.e(TAG, "triggerCapture: capture returned empty data")
                    withContext(Dispatchers.Main) {
                        TelegramBot.sendText(Config.botToken, Config.chatId,
                            "[拍照失败，请稍后重试]")
                    }
                    return@launch
                }

                // Resize if RAW or quality mismatch
                val finalData = if (resolvedQuality != PhotoQuality.RAW) {
                    resizeJpeg(data, resolvedQuality)
                } else {
                    data
                }

                val dimg = android.graphics.BitmapFactory.Options().run {
                    inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeByteArray(finalData, 0, finalData.size, this)
                    "$outWidth×$outHeight"
                }
                val caption = "=★= /status  ${sourceLabelOf(source)} $dimg"

                TelegramBot.sendPhoto(Config.botToken, Config.chatId, finalData, caption)

            } catch (e: Exception) {
                Log.e(TAG, "triggerCapture exception", e)
                try {
                    TelegramBot.sendText(Config.botToken, Config.chatId,
                        "[拍照失败] ${e.message ?: "未知错误"}")
                } catch (_: Exception) {}
            }
        }
    }

    // ── JPEG resize ─────────────────────────────────────────────────────
    // Handles EXIF rotation (wide images = landscape = 270° rotation).

    private fun resizeJpeg(jpegData: ByteArray, quality: String): ByteArray {
        if (quality == PhotoQuality.RAW) return jpegData

        val (maxW, maxH) = when (quality) {
            PhotoQuality.HIGH   -> PhotoQuality.HIGH_W   to PhotoQuality.HIGH_H
            PhotoQuality.MEDIUM -> PhotoQuality.MEDIUM_W to PhotoQuality.MEDIUM_H
            else               -> PhotoQuality.LOW_W    to PhotoQuality.LOW_H
        }
        val targetJpegQ = PhotoQuality.jpegQualityFor(quality)

        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight

        if (srcW <= maxW && srcH <= maxH && targetJpegQ >= 95) return jpegData

        opts.inSampleSize = calculateInSampleSize(srcW, srcH, maxW, maxH)
        opts.inJustDecodeBounds = false

        var bitmap = android.graphics.BitmapFactory.decodeByteArray(
            jpegData, 0, jpegData.size, opts) ?: return jpegData

        // Landscape rotation (横拍: width > height → 270°)
        if (srcW > srcH) {
            val matrix = android.graphics.Matrix().apply { postRotate(270f) }
            val rotated = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) { bitmap.recycle(); bitmap = rotated }
        }

        // Scale to exact target
        if (bitmap.width != maxW || bitmap.height != maxH) {
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bitmap, maxW, maxH, true)
            if (scaled != bitmap) { bitmap.recycle(); bitmap = scaled }
        }

        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, targetJpegQ, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun calculateInSampleSize(sw: Int, sh: Int, rw: Int, rh: Int): Int {
        var s = 1
        if (sh > rh || sw > rw) {
            val hs = sh / 2; val ws = sw / 2
            while (hs / s >= rh && ws / s >= rw) s *= 2
        }
        return s
    }

    // ── Battery ──────────────────────────────────────────────────────────

    private fun getBatteryLevel(): Int = try {
        val i = registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED))
        val l = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val s = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        (l * 100 / s)
    } catch (e: Exception) { -1 }

    private fun getBatteryTemperature(): Int = try {
        val i = registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (t > 0) t / 10 else -1
    } catch (e: Exception) { -1 }

    // ── Status ───────────────────────────────────────────────────────────

    private fun buildStatusText(): String {
        val battery = getBatteryLevel()
        val temp    = getBatteryTemperature()
        val tempStr = if (temp > 0) "${temp}C" else "-"
        val sep     = "-".repeat(15)
        return buildString {
            append("版本：${BuildConfig.VERSION_NAME}\n")
            append("$sep\n")
            append("状态：${if (Config.enabled) "监控中" else "已停止"}\n")
            append("调试：${if (Config.debugMode) "开启" else "关闭"}\n")
            append("间隔：${Config.intervalMinutes} 分钟\n")
            append("电量：$battery%  $tempStr\n")
            append("$sep\n")
            append("命令列表：\n")
            append("/start -- 启动监控\n")
            append("/stop -- 停止监控\n")
            append("/photo -- 拍照（后镜头）\n")
            append("/photo f -- 前镜头拍照\n")
            append("/interval -- 间隔（分钟）\n")
            append("/status2 -- 详细状态")
        }
    }

    /** Detailed machine-readable status using ServiceStatus. */
    private fun buildDetailedStatusText(): String {
        val cs = ServiceStatus.cameraStatus
        val bs = ServiceStatus.botStatus
        val now = System.currentTimeMillis()
        val lastCaptureAgo = if (cs.lastCaptureTime > 0)
            "${(now - cs.lastCaptureTime) / 1000}s ago" else "never"
        val lastErr = cs.lastErrorMessage.takeIf { it.isNotBlank() }?.let { "⚠ $it" } ?: "none"
        return buildString {
            appendLine("═══ GuardEye Light ═══")
            appendLine("Bot polling : ${if (bs.pollingActive) "ACTIVE" else "inactive"}")
            appendLine("Monitoring  : ${if (bs.monitoringEnabled) "ON (${bs.intervalMinutes}min)" else "OFF"}")
            appendLine("Commands    : ${bs.commandCount}")
            appendLine("────────────────────────────────")
            appendLine("CamSvc      : ${if (cs.serviceRunning) "running" else "STOPPED"}")
            appendLine("Lifecycle   : ${cs.lifecycleState}")
            appendLine("Provider    : ${if (cs.cameraProviderReady) "ready" else "NOT ready"}")
            appendLine("Back bound  : ${cs.backCameraBound}")
            appendLine("Front bound : ${cs.frontCameraBound}")
            appendLine("Front cap   : ${cs.frontCaptureInProgress}")
            appendLine("WakeLock    : ${if (cs.wakeLockHeld) "HELD" else "free"}")
            appendLine("────────────────────────────────")
            appendLine("Captures    : ${cs.captureCount} total")
            appendLine("Failed      : ${cs.failedCaptureCount}")
            appendLine("Last OK     : $lastCaptureAgo (${cs.lastCaptureDurationMs}ms)")
            appendLine("Last error  : $lastErr")
        }
    }

    companion object {
        private const val TAG = "LightBotService"
    }
}
