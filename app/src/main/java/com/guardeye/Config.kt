package com.guardeye

import android.content.Context
import android.content.SharedPreferences

object Config {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("guard_eye_prefs", Context.MODE_PRIVATE)
    }

    private const val KEY_BOT_TOKEN   = "bot_token"
    private const val KEY_CHAT_ID      = "chat_id"
    private const val KEY_INTERVAL    = "interval_minutes"
    private const val KEY_ENABLED     = "enabled"
    private const val KEY_DETECTION   = "detection_enabled"
    private const val KEY_DEBUG       = "debug_enabled"
    private const val KEY_CAM_FACING  = "camera_facing"
    private const val KEY_OFFSET      = "bot_offset"
    private const val KEY_LAST_INTERVAL_CAPTURE = "last_interval_capture"
    private const val KEY_LAST_MANUAL_CAPTURE  = "last_manual_capture"
    private const val KEY_CAPTURE_SOURCE       = "last_capture_source"
    private const val KEY_ALERT_MODE             = "alert_mode"
    private const val KEY_LAST_CAPTURE_PATH = "last_capture_path"
    private const val KEY_TICKET_ENABLED      = "ticket_enabled"
    private const val KEY_TICKET_INTERVAL     = "ticket_interval_minutes"
    private const val KEY_TICKET_PLATES       = "ticket_plates"
    private const val KEY_TICKET_LAST_RESULT   = "ticket_last_result"

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
        set(v) { prefs.edit().putString(KEY_BOT_TOKEN, v).commit() }

    var chatId: String
        get() = prefs.getString(KEY_CHAT_ID, "") ?: ""
        set(v) { prefs.edit().putString(KEY_CHAT_ID, v).commit() }

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 5).coerceIn(1, 60)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceIn(1, 60)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).commit() }

    var detectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_DETECTION, true)
        set(v) = prefs.edit().putBoolean(KEY_DETECTION, v).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, true)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    var cameraFacing: String
        get() = prefs.getString(KEY_CAM_FACING, "back") ?: "back"
        set(v) = prefs.edit().putString(KEY_CAM_FACING, v).apply()

    var botOffset: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(v) { prefs.edit().putLong(KEY_OFFSET, v).commit() }

    var lastIntervalCapture: Long
        get() = prefs.getLong(KEY_LAST_INTERVAL_CAPTURE, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_INTERVAL_CAPTURE, v).apply()

    var lastIntervalCaptureTime: Long
        get() = prefs.getLong(KEY_LAST_INTERVAL_CAPTURE, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_INTERVAL_CAPTURE, v).apply()

    var lastManualCapture: Long
        get() = prefs.getLong(KEY_LAST_MANUAL_CAPTURE, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_MANUAL_CAPTURE, v).apply()

    var lastManualCaptureTime: Long
        get() = prefs.getLong(KEY_LAST_MANUAL_CAPTURE, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_MANUAL_CAPTURE, v).apply()

    var lastCapturePath: String?
        get() = prefs.getString(KEY_LAST_CAPTURE_PATH, null)
        set(v) = prefs.edit().putString(KEY_LAST_CAPTURE_PATH, v).apply()

    var lastCaptureSource: String
        get() = prefs.getString(KEY_CAPTURE_SOURCE, "manual") ?: "manual"
        set(v) = prefs.edit().putString(KEY_CAPTURE_SOURCE, v).apply()

    var alertMode: String
        get() = prefs.getString(KEY_ALERT_MODE, "photo") ?: "photo"
        set(v) = prefs.edit().putString(KEY_ALERT_MODE, v).apply()

    var ticketEnabled: Boolean
        get() = prefs.getBoolean(KEY_TICKET_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_TICKET_ENABLED, v).apply()

    var ticketIntervalMinutes: Int
        get() = prefs.getInt(KEY_TICKET_INTERVAL, 10).coerceIn(5, 60)
        set(v) = prefs.edit().putInt(KEY_TICKET_INTERVAL, v.coerceIn(5, 60)).apply()

    var ticketPlates: String
        get() = prefs.getString(KEY_TICKET_PLATES, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TICKET_PLATES, v).apply()

    var ticketLastResult: String
        get() = prefs.getString(KEY_TICKET_LAST_RESULT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TICKET_LAST_RESULT, v).apply()

    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()
}