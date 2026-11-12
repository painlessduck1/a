package com.unrealsms.otprelay

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Relay {
    private const val PREFS = "otp_relay_prefs"
    private const val DEFAULT_SUPABASE_URL = "https://pdnbxvglzrvifhvujikd.supabase.co"
    private val OTP_RE = Regex("""\b(\d{4,8})\b""")
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    // OkHttp client — same config as DM3's TelegramManager
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Config(
        val botToken: String,
        val chatId: String,
        val supabaseUrl: String,
        val supabaseKey: String,
    )

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            botToken    = p.getString("bot_token", "") ?: "",
            chatId      = p.getString("chat_id", "") ?: "",
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

    fun relay(context: Context, from: String, body: String): String {
        val cfg = loadConfig(context)

        val tg = if (cfg.botToken.isNotBlank() && cfg.chatId.isNotBlank())
            sendTelegram(cfg.botToken, cfg.chatId, "SMS from $from:\n$body")
        else "telegram: not configured"

        val sb = if (cfg.supabaseUrl.isNotBlank() && cfg.supabaseKey.isNotBlank())
            sendToSupabase(cfg.supabaseUrl, cfg.supabaseKey, from, body)
        else "supabase: not configured"

        return "$tg\n$sb"
    }

    private fun sendTelegram(token: String, chatId: String, text: String): String = try {
        val body = JSONObject().put("chat_id", chatId).put("text", text).toString()
            .toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(body)
            .build()
        val code = http.newCall(req).execute().use { it.code }
        "telegram: HTTP $code"
    } catch (e: Exception) {
        "telegram: failed (${e.message})"
    }

    private fun sendToSupabase(baseUrl: String, key: String, sender: String, message: String): String = try {
        val otp = OTP_RE.find(message)?.groupValues?.get(1)
        val json = JSONObject().apply {
            put("sender",   if (sender.isEmpty()) JSONObject.NULL else sender)
            put("message",  message)
            put("otp_code", otp ?: JSONObject.NULL)
        }.toString()

        val body = json.toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/rest/v1/own_sim_sms")
            .post(body)
            .addHeader("apikey", key)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Prefer", "return=minimal")
            .build()

        val resp = http.newCall(req).execute()
        val code = resp.code
        val err  = if (code in 200..299) "" else resp.body?.string() ?: ""
        resp.close()
        "supabase: HTTP $code $err".trim()
    } catch (e: Exception) {
        "supabase: failed (${e.message})"
    }
}
