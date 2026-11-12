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
        // Same pattern as DM3's SmsForwardReceiver — singleton supervised scope
        // survives after onReceive() returns so network calls always complete
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

        scope.launch {
            for ((sender, bodyBuilder) in grouped) {
                val body = bodyBuilder.toString().trim()
                Log.d("OtpRelay", "SMS from $sender: $body")
                val result = Relay.relay(appContext, sender, body)
                Log.i("OtpRelay", result)
            }
        }
    }
}
