package com.guardeye.light

import com.guardeye.Config
import com.guardeye.TelegramBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * 澳门交通告票查询（对齐 traffic_ticket_query.js 逻辑）
 * FSM URL: https://www.fsm.gov.mo/webticket/Webform1.aspx?carClass=L&Lang=C
 */
object TicketChecker {

    private const val TAG = "TicketChecker"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)   // 手动处理 302，对齐 Node.js 脚本
        .build()

    private const val URL = "https://www.fsm.gov.mo/webticket/Webform1.aspx?carClass=L&Lang=C"

    // 按钮文字（与 FSM 网页一致，无空格）
    private const val BTN_OK = "確定"

    // 正则：匹配 <input name="xxx" value="yyy">（FSM 隐藏字段用 name=）
    private val RE_VIEWSTATE       = Regex("""name="__VIEWSTATE"[^>]*value="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val RE_EVENTVALIDATION = Regex("""name="__EVENTVALIDATION"[^>]*value="([^"]*)"""", RegexOption.IGNORE_CASE)
    // 正则：匹配查询结果里的 id= 元素
    private val RE_MSG              = Regex("""id="lbMsgText"[^>]*>([^<]*)<""", RegexOption.IGNORE_CASE)
    private val RE_NO_TICKET        = Regex("""id="lbNoTicket2"[^>]*>([^<]*)<""", RegexOption.IGNORE_CASE)
    private val RE_PLATE            = Regex("""id="lbGetNum"[^>]*>([^<]*)<""")
    private val RE_CAR_IMG         = Regex("""id="ImgCarType"[^>]*\bsrc="([^"]+)""", RegexOption.IGNORE_CASE)
    private val RE_CAR_LABEL       = Regex("""id="Label2"[^>]*>([^<]*)<""")

    // ── 主入口 ─────────────────────────────────────────────────────
    suspend fun checkAndPush() {
        withContext(Dispatchers.IO) {
            val plates = parsePlates(Config.ticketPlates)
            if (plates.isEmpty()) return@withContext
            val lastJson = try { JSONObject(Config.ticketLastResult) } catch (_: Exception) { JSONObject() }

            val results = mutableListOf<TicketResult>()
            for (plate in plates) {
                try {
                    val r = queryPlate(plate)
                    val lastState = if (lastJson.has(plate)) lastJson.getBoolean(plate) else null
                    results.add(r.copy(lastHasTicket = lastState))
                } catch (e: Exception) {
                    Log.e(TAG, "[$plate] 查询失败: ${e.message}")
                    results.add(TicketResult(plate, null, null, false, "查询失败: ${e.message}"))
                }
            }

            pushToTelegram(results)

            val json = JSONObject()
            for (r in results) json.put(r.plate, r.hasTicket)
            Config.ticketLastResult = json.toString()
        }
    }

    // ── 查询单个车牌（对齐 traffic_ticket_query.js queryTicket）──
    private fun queryPlate(plate: String): TicketResult {
        val cookieJar = mutableMapOf<String, String>()

        // ── Step 1: GET 页面，获取 ViewState + Cookie ───────────
        Log.d(TAG, "[$plate] Step1 GET $URL")
        val getResp = client.newCall(
            Request.Builder().url(URL)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
        ).execute()

        // 保存 Cookie
        getResp.headers("set-cookie").forEach { c ->
            val i = c.indexOf("=")
            if (i > 0) cookieJar[c.substring(0, i)] = c.substring(i + 1).split(";")[0]
        }
        val html1 = getResp.body?.string() ?: throw Exception("Empty GET response")
        getResp.close()
        Log.d(TAG, "[$plate] GET Cookie: ${cookieJar.keys.joinToString()}")

        // 提取 ViewState/EventValidation（直接用正则，对齐旧代码）
        val vs = RE_VIEWSTATE.find(html1)?.groupValues?.get(1) ?: ""
        val ev = RE_EVENTVALIDATION.find(html1)?.groupValues?.get(1) ?: ""
        Log.d(TAG, "[$plate] GET __VIEWSTATE len=${vs.length}, __EVENTVALIDATION len=${ev.length}")

        // ── Step 2: POST 提交车牌 ─────────────────────────────────
        // 对齐 Node.js：__EVENTTARGET/__EVENTARGUMENT 无等号，其他 key=value
        val postBody = buildPostBody(vs, ev, plate)
        Log.d(TAG, "[$plate] POST body(prefix): ${postBody.take(150)}")

        var postResp = client.newCall(
            Request.Builder().url(URL).post(postBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("User-Agent", "Mozilla/5.0")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", URL)
                .header("Cookie", cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                .build()
        ).execute()

        var html = postResp.body?.string() ?: throw Exception("Empty POST response")
        postResp.close()
        Log.d(TAG, "[$plate] POST status=${postResp.code}, HTML len=${html.length}")

        // 处理 302 重定向（对齐 Node.js httpRequest）
        if (postResp.code == 302) {
            val loc = postResp.headers["location"] ?: ""
            Log.w(TAG, "[$plate] POST 302 重定向: $loc")
            val redirectUrl = when {
                loc.startsWith("http")  -> loc
                loc.startsWith("/")       -> "https://www.fsm.gov.mo$loc"
                else                        -> "https://www.fsm.gov.mo/webticket/$loc"
            }
            val redirectResp = client.newCall(
                Request.Builder().url(redirectUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Cookie", cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    .build()
            ).execute()
            html = redirectResp.body?.string() ?: throw Exception("Empty redirect response")
            redirectResp.close()
            Log.d(TAG, "[$plate] 重定向后 HTML len=${html.length}")
        }

        // 调试：输出关键元素
        val msgText = RE_MSG.find(html)?.groupValues?.get(1)?.trim()
        val noTicket = RE_NO_TICKET.find(html)?.groupValues?.get(1)?.trim()
        Log.d(TAG, "[$plate] msgText='$msgText', noTicket2='$noTicket', contains沒違例=${html.contains("沒有違例紀錄")}")

        return parseResponse(html, plate)
    }

    // ── 提取 HTML 中所有 name="xxx" value="yyy" 的字段 ─────
    private fun extractHiddenFields(html: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        // 匹配 name="xxx" value="yyy"（单/双引号都支持）
        val re = Regex("""\b(?:name|id)="([^"]+)"[^>]*\bvalue="([^"]*)""""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        // 改用逐行简单匹配（更可靠）
        val pattern = Regex("""\b(?:name|id)=['"]([^'"]+)['"][^>]*\bvalue=['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        for (m in pattern.findAll(html)) {
            fields[m.groupValues[1]] = m.groupValues[2]
        }
        // 如果上面没匹配到，尝试另一种格式（无 value 的属性）
        if (fields.isEmpty()) {
            Log.w(TAG, "extractHiddenFields: 第一次匹配失败，尝试备用方式")
            val pattern2 = Regex("""id="([^"]+)"[^>]*value="([^"]*)" """, RegexOption.IGNORE_CASE)
            for (m in pattern2.findAll(html)) {
                fields[m.groupValues[1]] = m.groupValues[2]
            }
        }
        return fields
    }

    // ── 手动拼接 POST body（对齐 Node.js）────────────────────
    // __EVENTTARGET/__EVENTARGUMENT 无等号；其他 key=value
    private fun buildPostBody(vs: String, ev: String, plate: String): String {
        return "__EVENTTARGET" +
                "&__EVENTARGUMENT" +
                "&__VIEWSTATE=" + URLEncoder.encode(vs, "utf-8") +
                "&__EVENTVALIDATION=" + URLEncoder.encode(ev, "utf-8") +
                "&Calculator=" + URLEncoder.encode(plate, "utf-8") +
                "&btnOk=" + URLEncoder.encode(BTN_OK, "utf-8")
    }

    // ── 解析响应 HTML（对齐 traffic_ticket_query.js parseResult）──
    private fun parseResponse(html: String, plate: String): TicketResult {
        val plateNumber = RE_PLATE.find(html)?.groupValues?.get(1)?.trim()

        // 车型
        val imgSrc = RE_CAR_IMG.find(html)?.groupValues?.get(1)?.lowercase() ?: ""
        val carType = when {
            imgSrc.contains("newcar")  -> "新汽車"
            imgSrc.contains("car")     -> "汽車"
            imgSrc.contains("bike") || imgSrc.contains("motor") -> "電單車"
            else -> RE_CAR_LABEL.find(html)?.groupValues?.get(1)?.trim() ?: "---"
        }

        // 三层检查（对齐 Node.js parseResult）
        val msgText   = RE_MSG.find(html)?.groupValues?.get(1)?.trim() ?: ""
        val noTicket2 = RE_NO_TICKET.find(html)?.groupValues?.get(1)?.trim() ?: ""

        val message = when {
            msgText.isNotBlank() && msgText != "null" -> msgText
            noTicket2.contains("沒有違例紀錄")           -> "沒有違例紀錄"
            html.contains("沒有違例紀錄")                -> "沒有違例紀錄"
            html.contains("有違例紀錄") || html.contains("有违例记录") -> "有違例紀錄"
            else                                               -> "查無資料"
        }

        val hasTicket = when {
            message.contains("有違例紀錄") -> true
            message.contains("沒有違例紀錄") -> false
            else                              -> html.contains("有違例紀錄") || html.contains("有违例记录")
        }

        return TicketResult(
            plate       = plate,
            plateNumber = plateNumber,
            carType     = carType,
            hasTicket   = hasTicket,
            message     = message
        )
    }

    // ── 推送到 Telegram ───────────────────────────────────────────
    private fun pushToTelegram(results: List<TicketResult>) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Macau")
        }.format(Date())

        val lines = mutableListOf<String>().apply { add(time) }
        for (r in results) {
            lines.add("${r.plate}，${r.message}")
        }

        val text = lines.joinToString("\n")
        val token = Config.botToken
        val chatId = Config.chatId
        if (token.isNotBlank() && chatId.isNotBlank()) {
            TelegramBot.sendText(token, chatId, text)
        }
    }

    // ── 解析车牌列表 ─────────────────────────────────────────────
    fun parsePlates(text: String): List<String> {
        return text.split(Regex("[\\s,;]+"))
            .map { it.trim().uppercase().replace(Regex("[- ]"), "") }
            .filter { Regex("^[A-Z]{2}[0-9]{4}$").matches(it) }
    }

    // ── 数据类 ─────────────────────────────────────────────────
    data class TicketResult(
        val plate: String,
        val plateNumber: String?,
        val carType: String?,
        val hasTicket: Boolean,
        val message: String,
        val lastHasTicket: Boolean? = null
    )
}
