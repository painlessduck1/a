package com.unrealsms.otprelay

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * All this app does: when an SMS arrives, send its text to your Telegram
 * bot (so you still see it exactly like before) AND POST it straight to
 * your site's /device/sms endpoint (so the OTP lands in Supabase without
 * you touching the forwarder app's webhook/HMAC settings).
 *
 * Nothing else is read from the phone: no contacts, location, calls,
 * notifications, mic. Just the text of the SMS that triggered this.
 */
object Relay {
    private const val PREFS = "otp_relay_prefs"

    data class Config(
        val botToken: String,
        val chatId: String,
        val siteUrl: String,
        val secret: String,
    )

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            botToken = p.getString("bot_token", "") ?: "",
            chatId = p.getString("chat_id", "") ?: "",
            siteUrl = p.getString("site_url", "https://unrealsms.store/device/sms") ?: "",
            secret = p.getString("secret", "") ?: "",
        )
    }

    fun saveConfig(context: Context, c: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("bot_token", c.botToken)
            .putString("chat_id", c.chatId)
            .putString("site_url", c.siteUrl)
            .putString("secret", c.secret)
            .apply()
    }

    /**
     * Sends [body] (the raw SMS text) to Telegram and to the site.
     * Blocks on network I/O — always call this from a background thread.
     * Returns a short human-readable status line for each destination.
     */
    fun relay(context: Context, from: String, body: String): String {
        val cfg = loadConfig(context)

        val tgResult = if (cfg.botToken.isNotBlank() && cfg.chatId.isNotBlank()) {
            sendTelegram(cfg.botToken, cfg.chatId, "SMS from $from:\n$body")
        } else {
            "telegram: not configured"
        }

        val siteResult = if (cfg.siteUrl.isNotBlank() && cfg.secret.isNotBlank()) {
            sendToSite(cfg.siteUrl, cfg.secret, body)
        } else {
            "site: not configured"
        }

        return "$tgResult\n$siteResult"
    }

    private fun sendTelegram(token: String, chatId: String, text: String): String {
        return try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val conn = (url.openConnection() as HttpURLConnection).apply {
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
    }

    private fun sendToSite(siteUrl: String, secret: String, message: String): String {
        return try {
            // Must match src/lib/device-sms.server.ts on the website: a JSON
            // body with a "message" field, signed with X-Signature: sha256=<hex HMAC>.
            val body = JSONObject().put("message", message).toString()
            val signature = hmacSha256Hex(secret, body)
            val url = URL(siteUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Signature", "sha256=$signature")
            }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            "site: HTTP $code $text"
        } catch (e: Exception) {
            "site: failed (${e.message})"
        }
    }

    private fun hmacSha256Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
