package com.guardeye

import android.content.Context
import android.content.SharedPreferences

object Config {
    private const val PREFS = "guardeye_prefs"
    private const val KEY_BOT_TOKEN = "bot_token"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_INTERVAL = "interval_minutes"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DETECTION_ENABLED = "detection_enabled"

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
        get() = prefs.getInt(KEY_INTERVAL, 5)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var detectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_DETECTION_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_DETECTION_ENABLED, v).apply()

    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()

    var botOffset: Long
        get() = prefs.getLong("bot_offset", 0L)
        set(v) = prefs.edit().putLong("bot_offset", v).apply()
}
