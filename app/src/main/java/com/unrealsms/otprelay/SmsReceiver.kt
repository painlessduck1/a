package com.unrealsms.otprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val grouped = LinkedHashMap<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: "Unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(msg.displayMessageBody)
        }

        val appContext = context.applicationContext

        // goAsync() tells Android "don't kill this process yet" — without it
        // Android kills the app within ~5 seconds of onReceive() returning,
        // before OkHttp finishes the network calls
        val pending = goAsync()

        // Also start foreground service so the process stays alive even longer
        try {
            appContext.startForegroundService(Intent(appContext, KeepAliveService::class.java))
        } catch (_: Exception) {}

        scope.launch {
            try {
                for ((sender, bodyBuilder) in grouped) {
                    val body = bodyBuilder.toString().trim()
                    Log.d("OtpRelay", "SMS from $sender")

                    // Record that we received an SMS (visible on main screen)
                    appContext.getSharedPreferences("otp_relay_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_sms", "Last SMS: $sender @ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                        .apply()

                    val result = Relay.relay(appContext, sender, body)
                    Log.i("OtpRelay", result)

                    // Save result so user can check it on the main screen
                    appContext.getSharedPreferences("otp_relay_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_result", result)
                        .apply()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
