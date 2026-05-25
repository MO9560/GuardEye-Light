package com.guardeye

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BotManager — 全局单例，独立于 Service 生命周期
 *
 * 设计原则：
 * 1. 独立 OkHttpClient，永远在线
 * 2. SharedPreferences 持久化 offset，服务重启不丢状态
 * 3. 主动心跳 — 定期发送心跳消息，证明链路活着
 * 4. 状态变更回调 — 相机/模型状态变化主动推送到 Telegram
 * 5. UI 可随时查询实时状态
 * 6. 完整日志，Logcat 可追踪每个 API 调用
 */
object BotManager {

    private const val TAG = "GuardEye.Bot"
    private const val BASE_URL = "https://api.telegram.org"
    private const val PREFS = "bot_manager_prefs"
    private const val KEY_OFFSET = "last_offset"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_HEARTBEAT_MINUTES = "heartbeat_minutes"

    private const val NOTIFICATION_ID = 1002
    private const val CHANNEL_ID = "guardeye_bot_channel"

    // 心跳间隔（分钟）
    private var heartbeatIntervalMin = 10

    // 外部注入的 context（ApplicationContext，安全）
    private var appContext: Context? = null

    // OkHttp client（独立，不受 Service 影响）
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // Polling 线程
    private var pollingThread: Thread? = null
    private var isPolling = false
    private var shouldPoll = false

    // 内存缓存（热数据）
    private var cachedOffset = 0L
    private var cachedChatId = ""
    private var cachedToken = ""

    // SharedPreferences（进程重启不丢 offset）
    private val prefs: android.content.SharedPreferences by lazy {
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // Handler for main thread callbacks
    private val mainHandler = Handler(Looper.getMainLooper())

    // 状态监听器（UI / Service 注册监听）
    interface BotStatusListener {
        fun onBotConnected()
        fun onBotDisconnected()
        fun onCommandReceived(text: String, chatId: String)
        fun onOffsetUpdated(offset: Long)
    }

    private val listeners = mutableListOf<BotStatusListener>()

    // ========== 公开 API ==========

    /**
     * 初始化（Application.onCreate 调用一次即可）
     */
    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        cachedOffset = prefs.getLong(KEY_OFFSET, 0L)
        cachedChatId = prefs.getString(KEY_CHAT_ID, "") ?: ""
        cachedToken = ""

        Log.d(TAG, "BotManager init. cachedOffset=$cachedOffset chatId=$cachedChatId")
        createNotificationChannel()
    }

    /**
     * 设置 Token（每次 Config 保存后调用）
     */
    fun setToken(token: String) {
        cachedToken = token
        Log.d(TAG, "Token updated: ${token.take(8)}...")
    }

    /**
     * 设置 Chat ID（每次 Config 保存后调用）
     */
    fun setChatId(chatId: String) {
        if (chatId.isNotBlank() && chatId != cachedChatId) {
            cachedChatId = chatId
            prefs.edit().putString(KEY_CHAT_ID, chatId).apply()
            Log.d(TAG, "ChatId saved: $chatId")
        }
    }

    /**
     * 设置心跳间隔（分钟）
     */
    fun setHeartbeatInterval(minutes: Int) {
        heartbeatIntervalMin = minutes
        prefs.edit().putInt(KEY_HEARTBEAT_MINUTES, minutes).apply()
    }

    /**
     * 注册状态监听器
     */
    fun addListener(listener: BotStatusListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    /**
     * 移除监听器
     */
    fun removeListener(listener: BotStatusListener) {
        listeners.remove(listener)
    }

    /**
     * 查询当前状态（供 UI 调用）
     */
    fun getStatus(): BotStatus {
        return BotStatus(
            isTokenSet = cachedToken.isNotBlank(),
            isChatIdSet = cachedChatId.isNotBlank(),
            isPolling = isPolling,
            lastOffset = cachedOffset,
            chatId = cachedChatId,
            token = cachedToken.take(8) + "..."
        )
    }

    /**
     * 启动轮询（独立线程）
     */
    fun startPolling() {
        if (appContext == null) {
            Log.e(TAG, "startPolling called before init()")
            return
        }
        if (cachedToken.isBlank()) {
            Log.e(TAG, "startPolling: token is blank, cannot start")
            return
        }

        shouldPoll = true
        if (pollingThread != null && pollingThread!!.isAlive) {
            Log.d(TAG, "Polling already running")
            return
        }

        Log.i(TAG, "Starting bot polling thread...")
        isPolling = true
        notifyListenersOnMain { it.onBotConnected() }

        pollingThread = Thread {
            Log.d(TAG, "Polling thread started. offset=$cachedOffset")
            var consecutiveErrors = 0
            val maxErrors = 5

            // 启动时先发送一条在线消息
            sendStartupNotification()

            while (shouldPoll) {
                try {
                    val updates = fetchUpdates(cachedOffset)

                    if (updates.isNotEmpty()) {
                        Log.d(TAG, "Got ${updates.size} update(s)")
                        consecutiveErrors = 0 // 重置错误计数

                        for (update in updates) {
                            // 自动保存 chatId（首次对话）
                            if (cachedChatId.isBlank() && update.chatId.isNotBlank()) {
                                setChatId(update.chatId)
                                // 通知上层保存到 Config
                                notifyListenersOnMain { it.onCommandReceived(update.text, update.chatId) }
                            } else {
                                notifyListenersOnMain { it.onCommandReceived(update.text, update.chatId) }
                            }

                            // 推进 offset
                            val newOffset = update.messageId + 1
                            if (newOffset > cachedOffset) {
                                cachedOffset = newOffset
                                saveOffset(newOffset)
                                notifyListenersOnMain { it.onOffsetUpdated(newOffset) }
                            }
                        }
                    } else {
                        // 无新消息，正常轮询
                    }

                    // 正常轮询间隔（3秒）
                    Thread.sleep(3000)

                } catch (e: InterruptedException) {
                    Log.d(TAG, "Polling interrupted")
                    break
                } catch (e: Exception) {
                    consecutiveErrors++
                    Log.e(TAG, "Polling error ($consecutiveErrors/$maxErrors): ${e.message}")
                    e.printStackTrace()

                    if (consecutiveErrors >= maxErrors) {
                        Log.e(TAG, "Too many errors, stopping polling")
                        isPolling = false
                        notifyListenersOnMain { it.onBotDisconnected() }
                        break
                    }

                    // 退避重试（错误越多，间隔越长）
                    val backoff = minOf(30_000L, (consecutiveErrors * 5_000L).toLong())
                    Thread.sleep(backoff)
                }
            }

            Log.d(TAG, "Polling thread exiting")
            isPolling = false
        }.apply { name = "BotPollingThread"; start() }
    }

    /**
     * 停止轮询
     */
    fun stopPolling() {
        Log.i(TAG, "Stopping bot polling...")
        shouldPoll = false
        pollingThread?.interrupt()
        pollingThread = null
        isPolling = false
        notifyListenersOnMain { it.onBotDisconnected() }
    }

    /**
     * 发送文本消息（同步，失败返回 false）
     */
    fun sendText(text: String): Boolean {
        if (cachedToken.isBlank() || cachedChatId.isBlank()) {
            Log.e(TAG, "sendText: token or chatId is blank")
            return false
        }

        val url = "$BASE_URL/bot$cachedToken/sendMessage"
        val body = JSONObject()
            .put("chat_id", cachedChatId)
            .put("text", text)
            .put("parse_mode", "Markdown")

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string() ?: ""
                if (code == 200) {
                    Log.d(TAG, "sendText OK: ${text.take(50)}")
                    true
                } else {
                    Log.e(TAG, "sendText failed: HTTP $code — $bodyStr")
                    false
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "sendText IO error: ${e.message}")
            false
        }
    }

    /**
     * 发送图片（同步）
     */
    fun sendPhoto(photoBytes: ByteArray, caption: String = ""): Boolean {
        if (cachedToken.isBlank() || cachedChatId.isBlank()) return false

        val url = "$BASE_URL/bot$cachedToken/sendPhoto"
        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("chat_id", cachedChatId)
            .addFormDataPart("photo", "photo.jpg",
                photoBytes.toRequestBody("image/jpeg".toMediaType()))
            .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption) }
            .build()

        return try {
            client.newCall(Request.Builder().url(url).post(requestBody).build())
                .execute().use { it.code == 200 }
        } catch (e: Exception) {
            Log.e(TAG, "sendPhoto error: ${e.message}")
            false
        }
    }

    /**
     * 发送启动通知
     */
    private fun sendStartupNotification() {
        if (cachedToken.isBlank() || cachedChatId.isBlank()) {
            Log.d(TAG, "Cannot send startup notification: token or chatId not set")
            return
        }
        val text = buildString {
            appendLine("🛡️ *GuardEye 已上线*")
            appendLine("───")
            appendLine("⏰ ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("🔗 Bot 链路正常")
            appendLine("📡 等待指令中...")
        }
        sendText(text)
    }

    /**
     * 主动发送状态变更消息到 Telegram
     * CameraService 在以下情况调用：
     *   - 模型加载成功/失败
     *   - 相机初始化成功/失败
     *   - 检测到告警目标
     *   - 定时拍照完成
     */
    fun notifyStatus(title: String, detail: String = "", level: NotifyLevel = NotifyLevel.INFO) {
        val emoji = when (level) {
            NotifyLevel.INFO -> "ℹ️"
            NotifyLevel.WARN -> "⚠️"
            NotifyLevel.ALERT -> "🚨"
            NotifyLevel.SUCCESS -> "✅"
        }
        val text = buildString {
            appendLine("$emoji *$title*")
            if (detail.isNotBlank()) appendLine(detail)
            appendLine("───")
            appendLine("⏰ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        sendText(text)
    }

    enum class NotifyLevel { INFO, WARN, ALERT, SUCCESS }

    // ========== 内部方法 ==========

    private fun fetchUpdates(offset: Long): List<Update> {
        val token = cachedToken
        if (token.isBlank()) return emptyList()

        val url = "$BASE_URL/bot$token/getUpdates?offset=$offset&timeout=30"
        val request = Request.Builder().url(url).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    Log.e(TAG, "fetchUpdates HTTP ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: "{}"
                Log.v(TAG, "fetchUpdates response: ${body.take(200)}")

                val json = JSONObject(body)
                val result = json.optJSONArray("result") ?: JSONArray()
                val updates = mutableListOf<Update>()

                for (i in 0 until result.length()) {
                    val obj = result.getJSONObject(i)
                    val updateId = obj.optLong("update_id", 0L)
                    val msg = obj.optJSONObject("message") ?: continue
                    val text = msg.optString("text", "")
                    val chatObj = msg.optJSONObject("chat")
                    val chatIdFrom = chatObj?.optLong("id", 0L)?.toString() ?: ""
                    updates.add(Update(text, chatIdFrom, updateId))
                }
                updates
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchUpdates exception: ${e.message}")
            emptyList()
        }
    }

    private fun saveOffset(offset: Long) {
        cachedOffset = offset
        prefs.edit().putLong(KEY_OFFSET, offset).apply()
        Log.v(TAG, "Offset saved: $offset")
    }

    private fun notifyListenersOnMain(action: (BotStatusListener) -> Unit) {
        mainHandler.post {
            listeners.forEach { listener ->
                try {
                    action(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener exception: ${e.message}")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (appContext == null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GuardEye Bot 状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bot 通信状态通知"
            setShowBadge(false)
        }
        appContext!!.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * 显示 Bot 在线通知（前台常驻提示"Bot 通信正常"）
     */
    fun showBotOnlineNotification() {
        if (appContext == null) return
        val ctx = appContext!!
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("GuardEye Bot 在线")
            .setContentText("Telegram 链路正常 · ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notif)
        } catch (e: SecurityException) { /* 无权限 */ }
    }

    // ========== 数据类 ==========

    data class Update(val text: String, val chatId: String, val messageId: Long)

    data class BotStatus(
        val isTokenSet: Boolean,
        val isChatIdSet: Boolean,
        val isPolling: Boolean,
        val lastOffset: Long,
        val chatId: String,
        val token: String
    )
}
