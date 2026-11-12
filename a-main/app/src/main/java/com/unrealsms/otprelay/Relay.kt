package com.unrealsms.otprelay

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * When an SMS arrives: send it to Telegram (as before) and insert it straight
 * into Supabase table own_sim_sms. A database trigger on that table matches the
 * OTP to the pending order, so no website endpoint / HMAC is involved.
 */
object Relay {
    private const val PREFS = "otp_relay_prefs"
    private const val DEFAULT_SUPABASE_URL = "https://pdnbxvglzrvifhvujikd.supabase.co"
    private val OTP_RE = Regex("""\b(\d{4,8})\b""")

    data class Config(
        val botToken: String,
        val chatId: String,
        val supabaseUrl: String,
        val supabaseKey: String,
    )

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            botToken = p.getString("bot_token", "") ?: "",
            chatId = p.getString("chat_id", "") ?: "",
            supabaseUrl = p.getString("supabase_url", DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL,
            supabaseKey = p.getString("supabase_key", "") ?: "",
        )
    }

    fun saveConfig(context: Context, c: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bot_token", c.botToken)
            .putString("chat_id", c.chatId)
            .putString("supabase_url", c.supabaseUrl)
            .putString("supabase_key", c.supabaseKey)
            .apply()
    }

    /** Blocking network I/O: call from a background thread. */
    fun relay(context: Context, from: String, body: String): String {
        val cfg = loadConfig(context)

        val tg = if (cfg.botToken.isNotBlank() && cfg.chatId.isNotBlank()) {
            sendTelegram(cfg.botToken, cfg.chatId, "SMS from $from:\n$body")
        } else "telegram: not configured"

        val sb = if (cfg.supabaseUrl.isNotBlank() && cfg.supabaseKey.isNotBlank()) {
            sendToSupabase(cfg.supabaseUrl, cfg.supabaseKey, from, body)
        } else "supabase: not configured (set URL + key)"

        return "$tg\n$sb"
    }

    private fun sendTelegram(token: String, chatId: String, text: String): String = try {
        val conn = (URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val payload = JSONObject().put("chat_id", chatId).put("text", text).toString()
        conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        conn.disconnect()
        "telegram: HTTP $code"
    } catch (e: Exception) {
        "telegram: failed (${e.message})"
    }

    private fun sendToSupabase(baseUrl: String, key: String, sender: String, message: String): String = try {
        val otp = OTP_RE.find(message)?.groupValues?.get(1)
        val body = JSONObject().apply {
            put("sender", if (sender.isEmpty()) JSONObject.NULL else sender)
            put("message", message)
            put("otp_code", otp ?: JSONObject.NULL)
        }.toString()

        val conn = (URL(baseUrl.trimEnd('/') + "/rest/v1/own_sim_sms").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("apikey", key)
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Prefer", "return=minimal")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val err = if (code in 200..299) "" else
            (conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
        conn.disconnect()
        "supabase: HTTP $code $err".trim()
    } catch (e: Exception) {
        "supabase: failed (${e.message})"
    }
}
