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

    private const val URL = "https://www.fsm.gov.mo/psp/SMGQuerySystem/tnr.aspx"

    private val RE_VIEWSTATE = Regex("""id="__VIEWSTATE"\s+value="([^"]+)"""")
    private val RE_VIEWSTATEGENERATOR = Regex("""id="__VIEWSTATEGENERATOR"\s+value="([^"]+)"""")
    private val RE_EVENTVALIDATION = Regex("""id="__EVENTVALIDATION"\s+value="([^"]+)"""")
    private val RE_MSG = Regex("""<span id="lblMsg"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)

    private const val S_HAS_TICKET = "æé²ä¾è¨é"
    private const val S_NO_TICKET = "æ²æé²ä¾è¨é"
    private const val S_BTN_OK = "ç¢º å®"
    private const val S_QUERY_RESULT = "æ¥è©¢çµæ"
    private const val S_CANNOT_PARSE = "ç¡æ³è§£æ"

    suspend fun checkAndPush() {
        withContext(Dispatchers.IO) {
            val plates = parsePlates(Config.ticketPlates)
            if (plates.isEmpty()) return@withContext
            val lastJson = try { JSONObject(Config.ticketLastResult ?: "{}") } catch (_: Exception) { JSONObject() }

            val getReq = Request.Builder().url(URL).build()
            val getResp = client.newCall(getReq).execute()
            val html0 = getResp.body?.string() ?: throw Exception("Empty GET response")
            getResp.close()

            val vs = RE_VIEWSTATE.find(html0)?.groupValues?.get(1) ?: ""
            val vsg = RE_VIEWSTATEGENERATOR.find(html0)?.groupValues?.get(1) ?: ""
            val ev = RE_EVENTVALIDATION.find(html0)?.groupValues?.get(1) ?: ""
            val cookieName = "ASP.NET_SessionId"
            val rawCookie = getResp.header("Set-Cookie") ?: ""
            val cookieVal = rawCookie.split(";").getOrNull(0)?.removePrefix("$cookieName=") ?: ""

            val results = mutableListOf<TicketResult>()
            for (plate in plates) {
                try {
                    results.add(queryPlate(plate, vs, vsg, ev, cookieName, cookieVal))
                } catch (e: Exception) {
                    results.add(TicketResult(plate, null, false, "Query failed: ${e.message}"))
                }
            }

            val changed = results.any { r ->
                val prev = lastJson.optBoolean(r.plate, !r.hasTicket)
                prev != r.hasTicket
            }
            if (changed || results.any { it.hasTicket }) {
                pushToTelegram(results)
            }
        }
    }

    private fun queryPlate(plate: String, vs: String, vsg: String, ev: String, cookieName: String, cookieVal: String): TicketResult {
        val plateNum = plate.take(2)
        val plateAlpha = plate.drop(2)
        val btnOkEncoded = URLEncoder.encode(S_BTN_OK, "utf-8")
        val pairs = listOf(
            "__EVENTTARGET" to "",
            "__EVENTARGUMENT" to "",
            "__VIEWSTATE" to vs,
            "__VIEWSTATEGENERATOR" to vsg,
            "__EVENTVALIDATION" to ev,
            "txtPlate2" to plateNum,
            "txtPlate3" to plateAlpha,
            "btnOk" to btnOkEncoded
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
            .header("Cookie", "$cookieName=$cookieVal")
            .build()
        val postResp = client.newCall(postReq).execute()
        val html = postResp.body?.string() ?: throw Exception("Empty POST response")
        postResp.close()
        val msgMatch = RE_MSG.find(html)
        val msg = msgMatch?.groupValues?.get(1)
            ?.replace("<[^>]+>".toRegex(), "")
            ?.replace("&nbsp;".toRegex(), " ")
            ?.replace("&amp;".toRegex(), "&")
            ?.trim() ?: ""
        val hasTicket = html.contains(S_HAS_TICKET) || html.contains("有輸例记录")
        val noTicket = html.contains(S_NO_TICKET) || html.contains("没有輸例记录")
        val displayMsg = when {
            msg.isNotBlank() && !msg.contains(S_QUERY_RESULT) -> msg
            hasTicket -> S_HAS_TICKET
            noTicket -> S_NO_TICKET
            else -> S_CANNOT_PARSE
        }
        return TicketResult(plate, plateNum, hasTicket, displayMsg)
    }

    private fun pushToTelegram(results: List<TicketResult>) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val header = "[ Macau Traffic Monitor | $time ]"
        val sep = "--------------------------------"
        val msgLines = results.mapIndexed { i, r ->
            val flag = if (r.hasTicket) "[NEW]" else "    "
            val icon = if (r.hasTicket) "[!]" else "[OK]"
            "${i + 1}. $flag $icon ${r.plate} -- ${r.message}"
        }
        val body = msgLines.joinToString("\n")
        val total = results.size
        val violations = results.count { it.hasTicket }
        val summary = "[ $total plates | $violations violations ]"
        val text = "$header\n$sep\n$body\n$sep\n$summary"
    }

    fun parsePlates(text: String): List<String> {
        return text.split("\\s+".toRegex())
            .map { it.trim().uppercase() }
            .filter { it.matches(Regex("^[A-Z]{2}[0-9]{4}$")) }
    }

    data class TicketResult(val plate: String, val plateNumber: String?, val hasTicket: Boolean, val message: String)
}