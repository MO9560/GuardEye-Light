package com.guardeye

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.guardeye.BotManager.BotStatusListener
import com.guardeye.BotManager.NotifyLevel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * BotForegroundService — Telegram Bot 专用前台服务
 *
 * 完全独立于 CameraService：
 * - 不依赖 CameraX / Detector
 * - 独立 Notification Channel（Bot 专属）
 * - 独立前台通知（Bot 通信状态）
 * - 独立生命周期管理
 *
 * 职责：
 * 1. 启动时初始化 BotManager 并同步 Config
 * 2. 注册 BotStatusListener，接收所有 Telegram 指令
 * 3. 将指令路由到 handleCommand() 处理
 * 4. 状态变更主动推送 Telegram 通知
 * 5. 进程被系统销毁后自动重启（START_STICKY）
 */
class BotForegroundService : LifecycleService(), BotStatusListener {

    companion object {
        const val TAG = "GuardEye.BotSvc"
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "guardeye_bot_channel"
        const val ACTION_START = "com.guardeye.action.BOT_START"
        const val ACTION_STOP = "com.guardeye.action.BOT_STOP"

        // 相机服务是否在运行（用于状态查询）
        var isCameraServiceRunning = false
            private set
    }

    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // CameraService 实例引用（用于拍照）
    private var cameraServiceRef: CameraService? = null

    // 心跳计时器（定期检查 + 主动推送 Bot 链路状态）
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkAndNotifyBotHealth()
            mainHandler.postDelayed(this, 60_000) // 每 60 秒检查一次
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BotForegroundService created")
        createNotificationChannel()
        BotManager.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startBot()
            ACTION_STOP -> stopBot()
        }
        return START_STICKY
    }

    // ========== BotStatusListener 实现 ==========

    override fun onBotConnected() {
        Log.d(TAG, "Bot connected")
        isRunning = true
        updateNotification("✅ Bot 已连接 · 等待指令")
        // 首次连接主动推送状态
        pushFullStatus()
    }

    override fun onBotDisconnected() {
        Log.w(TAG, "Bot disconnected")
        isRunning = false
        updateNotification("⚠️ Bot 连接中断 · 重试中...")
    }

    override fun onCommandReceived(text: String, chatId: String) {
        Log.d(TAG, "Command: $text from $chatId")
        lifecycleScope.launch {
            handleCommand(text, chatId)
        }
    }

    override fun onOffsetUpdated(offset: Long) {
        // 持久化到 Config（确保 CameraService 重启后也能读到）
        Config.botOffset = offset
    }

    // ========== 核心方法 ==========

    private fun startBot() {
        if (isRunning) {
            Log.d(TAG, "Bot already running")
            return
        }

        // 同步 Config → BotManager
        BotManager.setToken(Config.botToken)
        BotManager.setChatId(Config.chatId)
        BotManager.setHeartbeatInterval(10)

        // 启动前台通知（必须）
        val notification = buildNotification("🔗 GuardEye Bot 启动中...")
        startForeground(NOTIFICATION_ID, notification)

        // 启动轮询
        BotManager.startPolling()

        // 启动心跳
        mainHandler.post(heartbeatRunnable)

        isRunning = true
        Log.i(TAG, "Bot service started. token=${Config.botToken.take(8)}... chatId=${Config.chatId}")
    }

    private fun stopBot() {
        isRunning = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        BotManager.removeListener(this)
        BotManager.stopPolling()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Bot service stopped")
    }

    private fun handleCommand(text: String, chatId: String) {
        // 安全检查：忽略非授权用户（如果配置了白名单）
        if (Config.chatId.isNotBlank() && chatId != Config.chatId) {
            BotManager.sendText("⚠️ 未授权用户，请联系管理员")
            return
        }

        when {
            text.startsWith("/start") -> {
                Config.enabled = true
                BotManager.sendText("""
                    ✅ *GuardEye 已开启*
                    
                    📸 监控模式：${if (Config.enabled) "运行中" else "已停止"}
                    ⏱ 拍摄间隔：${Config.intervalMinutes} 分钟
                    🔍 AI 识别：${if (Config.detectionEnabled) "开启" else "关闭"}
                    🤖 版本：${BuildConfig.VERSION_NAME}
                    
                    📋 指令列表：
                    /photo — 立即拍照
                    /status — 查看状态
                    /interval N — 设置间隔（1-10分钟）
                    /detect on/off — AI 识别开关
                    /start — 显示本消息
                    /stop — 停止监控
                """.trimIndent())
            }

            text.startsWith("/stop") -> {
                Config.enabled = false
                BotManager.sendText("⏸ *GuardEye 已停止*")
                // 同步停止相机服务
                if (isCameraServiceRunning) {
                    sendToCameraService(CameraService.ACTION_STOP)
                }
            }

            text.startsWith("/photo") -> {
                if (!isCameraServiceRunning) {
                    BotManager.sendText("⚠️ 相机服务未运行，请先 /start")
                    return
                }
                BotManager.sendText("📸 正在拍照，请稍候...")
                requestCameraPhoto()
            }

            text.startsWith("/status") -> {
                pushFullStatus()
            }

            text.startsWith("/interval") -> {
                val mins = text.removePrefix("/interval").trim().toIntOrNull()
                if (mins != null && mins in 1..10) {
                    Config.intervalMinutes = mins
                    BotManager.sendText("⏱ 拍摄间隔已设为 *${mins} 分钟*")
                    // 通知相机服务重新设置闹钟
                    sendToCameraService(CameraService.ACTION_CAPTURE)
                } else {
                    BotManager.sendText("📖 用法：`/interval 1-10`\n当前：${Config.intervalMinutes} 分钟")
                }
            }

            text.startsWith("/detect") -> {
                val enabled = !text.contains("off")
                Config.detectionEnabled = enabled
                val status = if (enabled) "✅ 开启" else "❌ 关闭"
                BotManager.sendText("🔍 AI 识别：$status")
                // 通知相机服务
                BotManager.notifyStatus("AI 识别已${if (enabled) "开启" else "关闭"}", level = NotifyLevel.INFO)
            }

            text.startsWith("/test") -> {
                // 测试命令 — 直接回复证明 Bot 活着
                BotManager.sendText("🤖 Bot 在线！\n时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\nchatId：$chatId\noffset：${Config.botOffset}")
            }

            text.startsWith("/health") -> {
                // 健康检查 — 始终发诊断文本
                checkAndNotifyBotHealth()
            }

            else -> {
                BotManager.sendText("📖 未知指令。发送 /start 查看命令列表")
            }
        }
    }

    private fun pushFullStatus() {
        val cameraStatus = if (isCameraServiceRunning) "✅ 运行中" else "❌ 未运行"
        val modelStatus = isModelLoaded()
        val uptime = getUptime()
        val battery = getBatteryLevel()

        val text = buildString {
            appendLine("📊 *GuardEye 状态报告*")
            appendLine("───")
            appendLine("🤖 Bot 链路：✅ 正常")
            appendLine("📸 相机服务：$cameraStatus")
            appendLine("🧠 AI 模型：$modelStatus")
            appendLine("⏱ 拍摄间隔：${Config.intervalMinutes} 分钟")
            appendLine("🔍 AI 识别：${if (Config.detectionEnabled) "✅ 开启" else "❌ 关闭"}")
            appendLine("🔋 电量：$battery%")
            appendLine("⏰ 运行时间：$uptime")
            appendLine("📡 offset：${Config.botOffset}")
            appendLine("───")
            appendLine("🕐 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("📋 版本：${BuildConfig.VERSION_NAME}")
        }
        BotManager.sendText(text)
    }

    private fun checkAndNotifyBotHealth() {
        if (!isRunning) {
            BotManager.sendText("⚠️ Bot 服务未运行，请重启应用")
            return
        }
        val status = BotManager.getStatus()

        val issues = mutableListOf<String>()
        val checks = mutableListOf<String>()

        if (status.isTokenSet) checks.add("✅ Token 已配置")
        else issues.add("⚠️ Token 未配置")

        if (status.isChatIdSet) checks.add("✅ Chat ID 已配置")
        else issues.add("⚠️ Chat ID 未配置")

        if (status.isPolling) checks.add("✅ 轮询运行中")
        else issues.add("🔴 轮询已停止")

        val text = buildString {
            appendLine("🏥 *Bot 健康检查*")
            appendLine("───")
            checks.forEach { appendLine(it) }
            issues.forEach { appendLine(it) }
            appendLine("───")
            appendLine("⏱ offset：${status.lastOffset}")
            appendLine("🕐 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        BotManager.sendText(text)
    }

    // ========== 辅助方法 ==========

    private fun isModelLoaded(): String {
        val modelFile = File(filesDir, "yolov8n.tflite")
        return if (modelFile.exists()) "✅ 已加载" else "❌ 未加载"
    }

    private fun getBatteryLevel(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 0
    }

    private fun getUptime(): String {
        val elapsedMs = android.os.SystemClock.elapsedRealtime()
        val hours = elapsedMs / 3600000
        val minutes = (elapsedMs % 3600000) / 60000
        return "${hours}h ${minutes}m"
    }

    private fun requestCameraPhoto() {
        // 通过 LocalBroadcastManager 通知 CameraService 拍照
        // CameraService 拍照完成后通过 BotManager.notifyStatus 推送结果
        sendToCameraService(CameraService.ACTION_CAPTURE)
    }

    private fun sendToCameraService(action: String) {
        try {
            val intent = Intent(this, CameraService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraService: ${e.message}")
            BotManager.sendText("❌ 无法启动相机服务：${e.message}")
        }
    }

    // ========== 通知栏 ==========

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("🛡️ GuardEye Bot")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) { /* 无权限 */ }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GuardEye Bot 服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bot 通信服务常驻通知"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        BotManager.removeListener(this)
        isRunning = false
        Log.d(TAG, "BotForegroundService destroyed")
        super.onDestroy()
    }
}
