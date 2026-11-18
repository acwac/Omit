package app.omit.shield.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object BurnerManager {
    private const val PREF = "omit_burner_prefs"
    private val OTP_REGEX = Regex("\\b\\d{4,8}\\b")
    private val NUM_REGEX = Regex("/number/(\\d{9,15})/?")

    private fun httpGet(url: String, bearer: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "application/json,text/html,*/*")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun httpPostJson(url: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        return code to stream.bufferedReader().use { it.readText() }
    }

    fun getOrCreateEmail(ctx: Context, forceNew: Boolean = false): JSONObject {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val savedAddr = prefs.getString("mail_addr", "") ?: ""
        val savedJwt = prefs.getString("mail_jwt", "") ?: ""
        if (!forceNew && savedAddr.isNotBlank() && savedJwt.isNotBlank()) {
            return JSONObject().put("address", savedAddr).put("provider", "mail.tm")
        }
        return try {
            val domainsJson = JSONObject(httpGet("https://api.mail.tm/domains"))
            val domain = domainsJson.getJSONArray("hydra:member").getJSONObject(0).getString("domain")
            val user = "omit" + UUID.randomUUID().toString().take(8)
            val addr = "$user@$domain"
            val pass = UUID.randomUUID().toString()
            httpPostJson("https://api.mail.tm/accounts", JSONObject().put("address", addr).put("password", pass))
            val (_, tokRaw) = httpPostJson("https://api.mail.tm/token", JSONObject().put("address", addr).put("password", pass))
            val jwt = JSONObject(tokRaw).getString("token")
            prefs.edit().putString("mail_addr", addr).putString("mail_pass", pass).putString("mail_jwt", jwt).apply()
            JSONObject().put("address", addr).put("provider", "mail.tm")
        } catch (_: Throwable) {
            try {
                val gm = JSONObject(httpGet("https://api.guerrillamail.com/ajax.php?f=get_email_address"))
                val addr = gm.getString("email_addr")
                val sid = gm.getString("sid_token")
                prefs.edit().putString("mail_addr", addr).putString("gm_sid", sid).putString("mail_jwt", "").apply()
                JSONObject().put("address", addr).put("provider", "guerrillamail")
            } catch (e: Throwable) {
                JSONObject().put("address", "").put("error", e.message ?: "Offline")
            }
        }
    }

    fun fetchEmailInbox(ctx: Context): JSONArray {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val jwt = prefs.getString("mail_jwt", "") ?: ""
        val gmSid = prefs.getString("gm_sid", "") ?: ""
        val out = JSONArray()
        try {
            if (jwt.isNotBlank()) {
                val raw = JSONObject(httpGet("https://api.mail.tm/messages", jwt))
                val arr = raw.optJSONArray("hydra:member") ?: JSONArray()
                for (i in 0 until minOf(arr.length(), 15)) {
                    val m = arr.getJSONObject(i)
                    val id = m.optString("id", "")
                    val subj = m.optString("subject", "")
                    val intro = m.optString("intro", "")
                    var fullText = "$subj $intro"
                    if (id.isNotBlank()) {
                        try {
                            val detail = JSONObject(httpGet("https://api.mail.tm/messages/$id", jwt))
                            fullText = "$fullText ${detail.optString("text", "")}"
                        } catch (_: Throwable) {}
                    }
                    val otp = OTP_REGEX.find(fullText)?.value ?: ""
                    out.put(JSONObject()
                        .put("id", id)
                        .put("from", m.optJSONObject("from")?.optString("address", "Unknown") ?: "Unknown")
                        .put("subject", subj)
                        .put("intro", intro)
                        .put("otp", otp))
                }
            } else if (gmSid.isNotBlank()) {
                val raw = JSONObject(httpGet("https://api.guerrillamail.com/ajax.php?f=check_email&seq=0&sid_token=$gmSid"))
                val arr = raw.optJSONArray("list") ?: JSONArray()
                for (i in 0 until minOf(arr.length(), 15)) {
                    val m = arr.getJSONObject(i)
                    val subj = m.optString("mail_subject", "")
                    val intro = m.optString("mail_excerpt", "")
                    val otp = OTP_REGEX.find("$subj $intro")?.value ?: ""
                    out.put(JSONObject()
                        .put("id", m.optString("mail_id", "$i"))
                        .put("from", m.optString("mail_from", "Unknown"))
                        .put("subject", subj)
                        .put("intro", intro)
                        .put("otp", otp))
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    fun fetchSmsNumbers(): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        for (path in listOf("https://anonymsms.com/", "https://anonymsms.com/united-states/", "https://anonymsms.com/united-kingdom/")) {
            try {
                val html = httpGet(path)
                for (m in NUM_REGEX.findAll(html)) {
                    val digits = m.groupValues[1]
                    if (seen.add(digits)) {
                        out.put(JSONObject().put("digits", digits).put("formatted", "+$digits"))
                        if (out.length() >= 15) return out
                    }
                }
            } catch (_: Throwable) {}
        }
        return out
    }

    fun fetchSmsMessages(digits: String): JSONArray {
        val out = JSONArray()
        try {
            val html = httpGet("https://anonymsms.com/number/$digits/").replace("\\\"", "\"")
            val msgRegex = Regex("\"from\"\\s*:\\s*\"([^\"]*)\"[^}]*?\"text\"\\s*:\\s*\"([^\"]*)\"[^}]*?\"receivedAt\"\\s*:\\s*\"([^\"]*)\"")
            for (m in msgRegex.findAll(html)) {
                val text = m.groupValues[2]
                val otp = OTP_REGEX.find(text)?.value ?: ""
                out.put(JSONObject()
                    .put("from", m.groupValues[1])
                    .put("text", text)
                    .put("time", m.groupValues[3])
                    .put("otp", otp))
                if (out.length() >= 20) break
            }
        } catch (_: Throwable) {}
        return out
    }
}
