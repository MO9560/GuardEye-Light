package com.guardeye.light

import com.guardeye.Config
import com.guardeye.TelegramBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object TicketChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // 正确 URL：澳门交通违例定额罚款（海事及水务局）网上查询
    private const val URL = "https://www.fsm.gov.mo/webticket/Webform1.aspx?carClass=L&Lang=C"

    private val RE_VIEWSTATE = Regex("""id="__VIEWSTATE"[^>]*\svalue="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val RE_VIEWSTATEGENERATOR = Regex("""id="__VIEWSTATEGENERATOR"[^>]*\svalue="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val RE_EVENTVALIDATION = Regex("""id="__EVENTVALIDATION"[^>]*\svalue="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val RE_PLATE = Regex("""id="lbGetNum"[^>]*>([^<]+)<""")
    private val RE_MSG = Regex("""id="lbMsgText"[^>]*>([^<]+)<""")
    private val RE_CAR_TYPE_IMG = Regex("""id="ImgCarType"[^>]*\bsrc="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val RE_CAR_TYPE_LABEL = Regex("""id="Label2"[^>]*>([^<]+)<""")

    // 正确的按钮文字（注意有空格）
    private const val BTN_OK = "確  定"

    suspend fun checkAndPush() {
        withContext(Dispatchers.IO) {
            val plates = parsePlates(Config.ticketPlates)
            if (plates.isEmpty()) return@withContext
            val lastJson = try { JSONObject(Config.ticketLastResult) } catch (_: Exception) { JSONObject() }

            // Step 1: GET page
            val getReq = Request.Builder().url(URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            val getResp = client.newCall(getReq).execute()
            val html0 = getResp.body?.string() ?: throw Exception("Empty GET response")
            val cookies = getResp.headers("set-cookie")
                .joinToString("; ") { it.substringBefore(";") }
            getResp.close()

            val vs = RE_VIEWSTATE.find(html0)?.groupValues?.get(1) ?: ""
            val vsg = RE_VIEWSTATEGENERATOR.find(html0)?.groupValues?.get(1) ?: ""
            val ev = RE_EVENTVALIDATION.find(html0)?.groupValues?.get(1) ?: ""

            val results = mutableListOf<TicketResult>()
            for (plate in plates) {
                try {
                    val r = queryPlate(plate, vs, vsg, ev, cookies)
                    // 填入上次状态
                    val lastState = if (lastJson.has(plate)) lastJson.getBoolean(plate) else null
                    results.add(r.copy(lastHasTicket = lastState))
                } catch (e: Exception) {
                    results.add(TicketResult(plate, null, null, false, "查询失败: ${e.message}"))
                }
            }

            // 每次都推送，不分析变化
            pushToTelegram(results)

            // 存本次结果
            val json = JSONObject()
            for (r in results) {
                json.put(r.plate, r.hasTicket)
            }
            Config.ticketLastResult = json.toString()
        }
    }

    private fun queryPlate(plate: String, vs: String, vsg: String, ev: String, cookies: String): TicketResult {
        val normalizedPlate = plate.uppercase().replace(Regex("[- ]"), "")

        // Re-GET page for fresh VIEWSTATE per query
        val getReq = Request.Builder().url(URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val getResp = client.newCall(getReq).execute()
        val html0 = getResp.body?.string() ?: throw Exception("Empty GET response")
        getResp.close()

        val freshVs = RE_VIEWSTATE.find(html0)?.groupValues?.get(1) ?: ""
        val freshVsg = RE_VIEWSTATEGENERATOR.find(html0)?.groupValues?.get(1) ?: ""
        val freshEv = RE_EVENTVALIDATION.find(html0)?.groupValues?.get(1) ?: ""

        val pairs = listOf(
            "__VIEWSTATE" to freshVs,
            "__VIEWSTATEGENERATOR" to freshVsg,
            "__EVENTVALIDATION" to freshEv,
            "Calculator" to normalizedPlate,
            "resW" to "1920",
            "resH" to "1080",
            "btnOk" to BTN_OK
        )
        val postBody = pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "utf-8")}=${URLEncoder.encode(v, "utf-8")}"
        }
        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val requestBody = postBody.toRequestBody(mediaType)

        val postReq = Request.Builder().url(URL).post(requestBody)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", URL)
            .header("Cookie", cookies)
            .build()

        val postResp = client.newCall(postReq).execute()
        val html = postResp.body?.string() ?: throw Exception("Empty POST response")
        postResp.close()

        return parseResponse(html)
    }

    private fun parseResponse(html: String): TicketResult {
        val plateNumber = RE_PLATE.find(html)?.groupValues?.get(1)?.trim()

        // Car type from image src or label
        val imgSrc = RE_CAR_TYPE_IMG.find(html)?.groupValues?.get(1)?.lowercase() ?: ""
        val carType = when {
            imgSrc.contains("newcar") -> "新汽車"
            imgSrc.contains("car") -> "汽車"
            imgSrc.contains("bike") || imgSrc.contains("motor") -> "電單車"
            else -> RE_CAR_TYPE_LABEL.find(html)?.groupValues?.get(1)?.trim() ?: "---"
        }

        // System message
        val msgText = RE_MSG.find(html)?.groupValues?.get(1)?.trim() ?: ""
        val hasTicket = html.contains("有違例紀錄") || html.contains("有违例记录")
        val noTicket = html.contains("沒有違例紀錄") || html.contains("没有违例纪录")

        val message = when {
            msgText.isNotBlank() && !msgText.contains("查詢結果") -> msgText
            hasTicket -> "有違例紀錄"
            noTicket -> "沒有違例紀錄"
            else -> "無法解析查詢結果"
        }

        return TicketResult(
            plate = plateNumber ?: "---",
            plateNumber = plateNumber,
            carType = carType,
            hasTicket = hasTicket,
            message = message
        )
    }

    private fun pushToTelegram(results: List<TicketResult>) {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Macau")
        }.format(java.util.Date())

        val lines = mutableListOf<String>()
        lines.add(time)

        for (r in results) {
            val iconNow = if (r.hasTicket) "📛" else "✅"
            val iconLast = when (r.lastHasTicket) {
                true -> "📛"
                false -> "✅"
                null -> ""
            }
            val plate = r.plateNumber ?: r.plate
            val statusText = if (r.hasTicket) "有違例紀錄" else "無違例紀錄"
            val line = "$plate，$statusText$iconLast$iconNow"
            lines.add(line)
        }

        val text = lines.joinToString("\n")

        val token = Config.botToken
        val chatId = Config.chatId
        if (token.isNotBlank() && chatId.isNotBlank()) {
            TelegramBot.sendText(token, chatId, text)
        }
    }

    fun parsePlates(text: String): List<String> {
        return text.split(Regex("\\s+"))
            .map { it.trim().uppercase() }
            .filter { Regex("^[A-Z]{2}[0-9]{4}$").matches(it) }
    }

    data class TicketResult(
        val plate: String,
        val plateNumber: String?,
        val carType: String?,
        val hasTicket: Boolean,
        val message: String,
        val lastHasTicket: Boolean? = null  // 上次状态（null=首次查询）
    )
}
