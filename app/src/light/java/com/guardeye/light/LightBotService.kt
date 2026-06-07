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

// ── Photo sources ────────────────────────────────────────────────────────────
private fun sourceLabelOf(source: String): String = when (source) {
    "interval" -> "定时"
    "ui"       -> "APP"
    "TEL"      -> "远控"
    "front"    -> "前镜头"
    else       -> source
}

// ── LightBotService ──────────────────────────────────────────────────────────

class LightBotService : LifecycleService() {

    // ── Camera service reference (singleton) ─────────────────────────────────
    // CameraForegroundService registers itself as instance in onCreate;
    // LightBotService accesses it directly after starting the service.
    private val cameraSvc: CameraForegroundService?
        get() = CameraForegroundService.instance
    // ── Coroutine scopes ─────────────────────────────────────────────────
    private val cmdScope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Telegram polling ─────────────────────────────────────────────────
    private var pollingJob: Job? = null

    // ── Foreground notification ───────────────────────────────────────────
    private val CHANNEL_ID = "guardeye_light_bot"
    private val NOTIF_ID   = 2001

    // ─────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        bindCameraService()
        startPolling()
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
        uploadScope.cancel()
        super.onDestroy()
    }

    // ── Bind camera service ───────────────────────────────────────────────

    private fun bindCameraService() {
        // CameraForegroundService.instance is set in its onCreate.
        // If not yet created, start the service so it initializes.
        if (cameraSvc == null) {
            val intent = Intent(this, CameraForegroundService::class.java)
            startForegroundService(intent)
        }
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
                LightAlarmReceiver.scheduleNextAlarm(this, Config.intervalMinutes)
                TelegramBot.sendText(token, chatId,
                    "[GuardEye Light 已启动]\n" +
                    "间隔：${Config.intervalMinutes}分钟\n" +
                    "提示：建议关闭电池优化以提高稳定性"
                )
            }

            text == "/stop" -> {
                Config.enabled = false
                LightAlarmReceiver.cancelAlarm(this)
                TelegramBot.sendText(token, chatId, "[已停止监控]")
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

            text == "/status" -> {
                TelegramBot.sendText(token, chatId, buildStatusText())
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
                    if (Config.enabled) LightAlarmReceiver.scheduleNextAlarm(this, mins)
                    TelegramBot.sendText(token, chatId, "[间隔已设为 $mins 分钟]")
                } else {
                    TelegramBot.sendText(token, chatId, "用法：/interval 1-60")
                }
            }

            text.startsWith("/debug") -> {
                val mode = text.removePrefix("/debug").trim().lowercase()
                Config.debugMode = (mode.isEmpty() || mode == "on")
                if (Config.enabled) startForegroundService(Intent(this, LightBotService::class.java))
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

        uploadScope.launch {
            try {
                // Wait for camera service to register (max 5 s)
                repeat(10) {
                    if (cameraSvc != null) return@repeat
                    delay(500)
                }
                if (cameraSvc == null) {
                    TelegramBot.sendText(Config.botToken, Config.chatId,
                        "[相机服务未就绪，请稍后重试]")
                    return@launch
                }

                val data = if (isFront) {
                    cameraSvc!!.captureFrontPhoto(resolvedQuality)
                } else {
                    cameraSvc!!.captureBackPhoto(resolvedQuality)
                }

                if (data.isEmpty()) {
                    TelegramBot.sendText(Config.botToken, Config.chatId,
                        "[拍照失败，请稍后重试]")
                    return@launch
                }

                // Post-process: resize JPEG in memory
                val finalData = resizeJpeg(data, resolvedQuality)

                // Build caption
                val now      = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val battery  = getBatteryLevel()
                val temp      = getBatteryTemperature()
                val tempStr   = if (temp > 0) "${temp}C" else "-"
                val resLabel  = PhotoQuality.labelFor(resolvedQuality)
                val srcLabel  = sourceLabelOf(source)
                val sep       = "-".repeat(14)

                val msg = buildString {
                    append("$now\n")
                    append("间隔：${Config.intervalMinutes}分钟  电量：$battery%  $tempStr\n")
                    append("来源：$srcLabel ($resLabel)\n")
                    append("$sep\n")
                    append("[ /photo ] [ /status ]")
                }

                TelegramBot.sendPhoto(Config.botToken, Config.chatId, finalData, msg)

            } catch (e: Exception) {
                Log.e(TAG, "triggerCapture failed", e)
                TelegramBot.sendText(Config.botToken, Config.chatId,
                    "[拍照失败：${e.javaClass.simpleName}]")
            }
        }
    }

    // ── In-memory JPEG post-processing ─────────────────────────────────

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

        // Landscape rotation (横拍需旋转 270 degrees)
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
            append("/interval -- 间隔（分钟）")
        }
    }

    companion object {
        private const val TAG = "LightBotService"
    }
}
