package com.unrealsms.otprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Fires when an SMS arrives on this SIM. Reads only that one message's
 * sender and text, then hands it to Relay. Nothing else on the phone is
 * touched.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val from = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }

        // goAsync() keeps the receiver alive long enough for the network
        // calls below to finish (a plain onReceive is killed almost
        // immediately once it returns).
        val pending = goAsync()
        Thread {
            try {
                val result = Relay.relay(context, from, body)
                Log.i("OtpRelay", result)
            } catch (e: Exception) {
                Log.e("OtpRelay", "relay failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
