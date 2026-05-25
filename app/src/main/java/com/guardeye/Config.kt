package com.guardeye

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREFS = "guardeye_prefs_v3"
    private const val KEY_BOT_TOKEN   = "bot_token"
    private const val KEY_CHAT_ID      = "chat_id"
    private const val KEY_INTERVAL    = "interval_minutes"
    private const val KEY_ENABLED     = "enabled"
    private const val KEY_DETECTION   = "detection_enabled"
    private const val KEY_DEBUG       = "debug_enabled"
    private const val KEY_CAM_FACING  = "camera_facing"
    private const val KEY_OFFSET      = "bot_offset"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var botToken: String
        get() = prefs.getString(KEY_BOT_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BOT_TOKEN, v).apply()

    var chatId: String
        get() = prefs.getString(KEY_CHAT_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CHAT_ID, v).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 5).coerceIn(1, 10)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceIn(1, 10)).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var detectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_DETECTION, true)
        set(v) = prefs.edit().putBoolean(KEY_DETECTION, v).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG, true)   // default ON for dev
        set(v) = prefs.edit().putBoolean(KEY_DEBUG, v).apply()

    /** "back" or "front" */
    var cameraFacing: String
        get() = prefs.getString(KEY_CAM_FACING, "back") ?: "back"
        set(v) = prefs.edit().putString(KEY_CAM_FACING, v).apply()

    var botOffset: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(v) = prefs.edit().putLong(KEY_OFFSET, v).apply()

    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()
}
