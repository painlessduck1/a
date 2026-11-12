package com.example.otprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Relay : BroadcastReceiver() {

    companion object {
        private const val TAG = "OTPRelay"
        private const val PREFS = "otp_relay_prefs"
        private const val SUPABASE_URL = "https://pdnbxvglzrvifhvujikd.supabase.co"
        private val OTP_RE = Regex("""\b(\d{4,8})\b""")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender  = messages[0].originatingAddress ?: ""
        val message = messages.joinToString("") { it.messageBody ?: "" }
        val otp     = OTP_RE.find(message)?.groupValues?.get(1)

        Log.d(TAG, "SMS from $sender | OTP: $otp")

        // read key from SharedPreferences (set in app settings)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val supabaseKey = prefs.getString("supabase_key", "") ?: ""

        if (supabaseKey.isBlank()) {
            Log.e(TAG, "Supabase key not set in settings")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            postToSupabase(supabaseKey, sender, message, otp)
        }
    }

    private fun postToSupabase(key: String, sender: String, message: String, otp: String?) {
        try {
            val url  = URL("$SUPABASE_URL/rest/v1/own_sim_sms")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod       = "POST"
            conn.doOutput            = true
            conn.connectTimeout      = 10_000
            conn.readTimeout         = 10_000
            conn.setRequestProperty("apikey",        key)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type",  "application/json")
            conn.setRequestProperty("Prefer",        "return=minimal")

            val body = JSONObject().apply {
                put("sender",   sender.ifEmpty { JSONObject.NULL })
                put("message",  message)
                put("otp_code", otp ?: JSONObject.NULL)
            }.toString()

            conn.outputStream.use { it.write(body.toByteArray()) }
            Log.d(TAG, "Supabase response: ${conn.responseCode}")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Supabase POST failed: ${e.message}")
        }
    }
}
