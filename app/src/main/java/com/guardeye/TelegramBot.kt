package com.guardeye

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Stateless Telegram Bot utility.
 * All methods are blocking — call from a background thread.
 */
object TelegramBot {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // ── Send text ────────────────────────────────────────────────

    fun sendText(token: String, chatId: String, text: String): Result<Unit> {
        return try {
            val body = JSONObject()
                .put("chat_id", chatId)
                .put("text", text)
                .put("parse_mode", "Markdown")
                .toString()
                .toRequestBody(JSON)
                .let { api("sendMessage", token, it) }
                .use { it.body?.string() ?: "" }
            if (JSONObject(body).getBoolean("ok")) Result.success(Unit)
            else Result.failure(Exception("sendText failed: $body"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Send photo via URL upload ────────────────────────────────
    // Telegram Bot API: POST to /bot{token}/sendPhoto with multipart/form-data

    fun sendPhoto(token: String, chatId: String, photoBytes: ByteArray, caption: String? = null): Result<Unit> {
        return try {
            val boundary = "GuardEyeBoundary_${System.currentTimeMillis()}"
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "photo",
                    "photo.jpg",
                    photoBytes.toRequestBody("image/jpeg".toMediaType())
                )
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("parse_mode", "Markdown")
                .apply { caption?.let { addFormDataPart("caption", it) } }
                .build()

            val req = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendPhoto")
                .post(multipartBody)
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: ""
            resp.close()

            val json = JSONObject(respBody)
            if (json.getBoolean("ok")) Result.success(Unit)
            else Result.failure(Exception("sendPhoto failed: $respBody"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Long-poll for updates ───────────────────────────────────

    fun fetchUpdates(token: String, offset: Long, timeoutSec: Int = 30): Result<List<Update>> {
        return try {
            val body = JSONObject()
                .put("offset", offset)
                .put("timeout", timeoutSec)
                .toString()
                .toRequestBody(JSON)
                .let { api("getUpdates", token, it) }
                .use { it.body?.string() ?: "" }

            val root = JSONObject(body)
            if (!root.getBoolean("ok")) return Result.failure(Exception("fetchUpdates: $body"))

            val result = root.getJSONArray("result")
            val updates = mutableListOf<Update>()
            for (i in 0 until result.length()) {
                updates.add(Update.fromJson(result.getJSONObject(i)))
            }
            Result.success(updates)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Internal ───────────────────────────────────────────────

    private fun api(method: String, token: String, body: RequestBody): Response {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(body)
            .build()
        return client.newCall(req).execute()
    }

    // ── Data model ──────────────────────────────────────────────

    data class Update(
        val updateId: Long,
        val chatId: String,
        val text: String
    ) {
        companion object {
            fun fromJson(obj: JSONObject): Update {
                val msg = obj.getJSONObject("message")
                return Update(
                    updateId = obj.getLong("update_id"),
                    chatId = msg.getJSONObject("chat").getString("id"),
                    text = msg.optString("text", "")
                )
            }
        }
    }
}
