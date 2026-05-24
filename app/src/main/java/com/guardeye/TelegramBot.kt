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
            val url = "$BASE_URL/bot$_token/getUpdates?offset=$offset&timeout=30"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                android.util.Log.d("GuardEye", "getUpdates response: ${body.take(500)}")
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
            android.util.Log.e("GuardEye", "getUpdates error: ${e.message}", e)
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

    data class Update(val text: String, val chatId: String, val messageId: Long)
}
