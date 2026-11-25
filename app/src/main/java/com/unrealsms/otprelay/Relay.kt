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
    private val OTP_RE    = Regex("""\b(\d{4,8})\b""")
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Config(
        val botToken:       String,
        val chatId:         String,
        val supabaseUrl:    String,
        val supabaseKey:    String,
        // ── Private inbox mode ───────────────────────────────────────────────
        // When privateMode is true the app writes incoming SMS to the
        // private_number_sms table tagged with userId, instead of own_sim_sms.
        // Admin assigns a user their UUID; they paste it here.
        val privateMode:    Boolean,
        val userId:         String,       // UUID from admin — which user owns this inbox
        val ownPhoneNumber: String,       // this SIM's own number (stored with each SMS row)
    )

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            botToken       = p.getString("bot_token",        "") ?: "",
            chatId         = p.getString("chat_id",          "") ?: "",
            supabaseUrl    = p.getString("supabase_url",     DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL,
            supabaseKey    = p.getString("supabase_key",     "") ?: "",
            privateMode    = p.getBoolean("private_mode",   false),
            userId         = p.getString("user_id",          "") ?: "",
            ownPhoneNumber = p.getString("own_phone_number", "") ?: "",
        )
    }

    fun saveConfig(context: Context, c: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bot_token",        c.botToken)
            .putString("chat_id",          c.chatId)
            .putString("supabase_url",     c.supabaseUrl)
            .putString("supabase_key",     c.supabaseKey)
            .putBoolean("private_mode",    c.privateMode)
            .putString("user_id",          c.userId)
            .putString("own_phone_number", c.ownPhoneNumber)
            .apply()
    }

    fun relay(context: Context, from: String, body: String): String {
        val cfg = loadConfig(context)

        // ── Telegram (always, if configured) ─────────────────────────────────
        val tg = if (cfg.botToken.isNotBlank() && cfg.chatId.isNotBlank())
            sendTelegram(cfg.botToken, cfg.chatId, "SMS from $from:\n$body")
        else "telegram: not configured"

        // ── Supabase ─────────────────────────────────────────────────────────
        val sb = if (cfg.supabaseUrl.isNotBlank() && cfg.supabaseKey.isNotBlank()) {
            if (cfg.privateMode && cfg.userId.isNotBlank()) {
                // Private inbox mode — write to private_number_sms with user_id tag.
                // This is a rented/dedicated number assigned exclusively to this user.
                // Does NOT feed the shared OTP order routing in own_sim_sms.
                sendToPrivateSupabase(
                    baseUrl     = cfg.supabaseUrl,
                    key         = cfg.supabaseKey,
                    userId      = cfg.userId,
                    phoneNumber = cfg.ownPhoneNumber,
                    sender      = from,
                    message     = body,
                )
            } else {
                // Shared/OTP mode — write to own_sim_sms; triggers order routing.
                sendToOwnSimSms(cfg.supabaseUrl, cfg.supabaseKey, from, body)
            }
        } else "supabase: not configured"

        return "$tg\n$sb"
    }

    // ── Telegram ──────────────────────────────────────────────────────────────

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

    // ── Shared / OTP-routing mode — own_sim_sms ───────────────────────────────

    private fun sendToOwnSimSms(
        baseUrl: String,
        key:     String,
        sender:  String,
        message: String,
    ): String = try {
        val otp  = OTP_RE.find(message)?.groupValues?.get(1)
        val json = JSONObject().apply {
            put("sender",   if (sender.isEmpty()) JSONObject.NULL else sender)
            put("message",  message)
            put("otp_code", otp ?: JSONObject.NULL)
        }.toString()

        val body = json.toRequestBody(JSON_TYPE)
        val req  = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/rest/v1/own_sim_sms")
            .post(body)
            .addHeader("apikey",        key)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Prefer",        "return=minimal")
            .build()

        val resp = http.newCall(req).execute()
        val code = resp.code
        val err  = if (code in 200..299) "" else resp.body?.string() ?: ""
        resp.close()
        "supabase: HTTP $code $err".trim()
    } catch (e: Exception) {
        "supabase: failed (${e.message})"
    }

    // ── Private inbox mode — private_number_sms ───────────────────────────────
    // Requires the service_role key (same key already used for own_sim_sms).
    // The row is tagged with the user's UUID so only they (and admins) can read it.

    private fun sendToPrivateSupabase(
        baseUrl:     String,
        key:         String,
        userId:      String,
        phoneNumber: String, // this SIM's number — stored for reference in the row
        sender:      String,
        message:     String,
    ): String = try {
        val otp  = OTP_RE.find(message)?.groupValues?.get(1)
        val json = JSONObject().apply {
            put("user_id",      userId)
            put("phone_number", if (phoneNumber.isNotBlank()) phoneNumber else JSONObject.NULL)
            put("sender",       if (sender.isNotEmpty())      sender      else JSONObject.NULL)
            put("message",      message)
            put("otp_code",     otp ?: JSONObject.NULL)
        }.toString()

        val body = json.toRequestBody(JSON_TYPE)
        val req  = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/rest/v1/private_number_sms")
            .post(body)
            .addHeader("apikey",        key)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Prefer",        "return=minimal")
            .build()

        val resp = http.newCall(req).execute()
        val code = resp.code
        val err  = if (code in 200..299) "" else resp.body?.string() ?: ""
        resp.close()
        Log.i("OtpRelay", "private inbox: HTTP $code ${err.take(120)}")
        "private inbox: HTTP $code $err".trim()
    } catch (e: Exception) {
        Log.e("OtpRelay", "private inbox send failed: ${e.message}")
        "private inbox: failed (${e.message})"
    }
}
