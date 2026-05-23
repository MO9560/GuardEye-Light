package com.guardeye

import android.graphics.Bitmap
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object TelegramBot {
    private const val BASE_URL = "https://api.telegram.org"
    private var _token: String = ""
    private var _chatId: String = ""

    val token: String get() = _token
    val chatId: String get() = _chatId

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun configure(token: String, chatId: String) {
        _token = token
        _chatId = chatId
    }

    fun sendText(text: String): Boolean {
        if (_token.isBlank() || _chatId.isBlank()) return false
        return try {
            val url = "$BASE_URL/bot$_token/sendMessage"
            val body = JSONObject().put("chat_id", _chatId).put("text", text)
            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.code == 200 }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun sendPhoto(photoFile: File, caption: String = ""): Boolean {
        if (_token.isBlank() || _chatId.isBlank()) return false
        return try {
            val url = "$BASE_URL/bot$_token/sendPhoto"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", _chatId)
                .addFormDataPart("photo", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaType()))
                .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption) }
                .build()
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().use { it.code == 200 }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun sendBitmap(bitmap: Bitmap, caption: String = ""): Boolean {
        if (_token.isBlank() || _chatId.isBlank()) return false
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            val bytes = stream.toByteArray()
            val url = "$BASE_URL/bot$_token/sendPhoto"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", _chatId)
                .addFormDataPart("photo", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption) }
                .build()
            val request = Request.Builder().url(url).post(requestBody).build()
            client.newCall(request).execute().use { it.code == 200 }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getUpdates(offset: Long = 0): List<Update> {
        if (_token.isBlank()) return emptyList()
        return try {
            val url = "$BASE_URL/bot$_token/getUpdates?offset=$offset&timeout=0"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val result = json.optJSONArray("result") ?: JSONArray()
                val updates = mutableListOf<Update>()
                for (i in 0 until result.length()) {
                    val obj = result.getJSONObject(i)
                    val msg = obj.optJSONObject("message") ?: continue
                    val text = msg.optString("text", "")
                    val chatIdFrom = msg.optJSONObject("chat")?.optString("id", "") ?: continue
                    val msgId = msg.optInt("message_id", 0)
                    updates.add(Update(text, chatIdFrom, msgId))
                }
                updates
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getChatId(username: String): String? {
        if (_token.isBlank()) return null
        return try {
            val url = "$BASE_URL/bot$_token/getUpdates"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val result = json.optJSONArray("result") ?: JSONArray()
                for (i in 0 until result.length()) {
                    val obj = result.getJSONObject(i)
                    val msg = obj.optJSONObject("message") ?: continue
                    val from = msg.optJSONObject("from") ?: continue
                    val uname = from.optString("username", "")
                    if (uname == username) {
                        return@use from.optString("id", "")
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class Update(val text: String, val chatId: String, val messageId: Int)
}
