package com.unrealsms.otprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /**
         * Returns the slot index (0 = SIM1, 1 = SIM2) for the given subscriptionId,
         * or -1 if it cannot be determined.
         */
        fun slotForSubId(context: Context, subId: Int): Int {
            if (subId < 0) return -1
            return try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as? SubscriptionManager ?: return -1
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    sm.getActiveSubscriptionInfo(subId)
                else
                    @Suppress("DEPRECATION") sm.getActiveSubscriptionInfo(subId)
                info?.simSlotIndex ?: -1
            } catch (_: Exception) { -1 }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // --- SIM filter ---
        val prefs     = context.getSharedPreferences("otp_relay_prefs", Context.MODE_PRIVATE)
        val simFilter = prefs.getInt("sim_filter", -1)   // -1 = All, 0 = SIM1, 1 = SIM2

        if (simFilter >= 0) {
            // subId is in the intent extras on Android 5.1+
            val subId = intent.getIntExtra("subscription", -1)
                .takeIf { it >= 0 }
                ?: intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
            val slot = slotForSubId(context, subId)
            Log.d("OtpRelay", "SMS subId=$subId slot=$slot filter=$simFilter")
            if (slot >= 0 && slot != simFilter) return   // wrong SIM, skip
        }

        val grouped = LinkedHashMap<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: "Unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(msg.displayMessageBody)
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        try { appContext.startForegroundService(Intent(appContext, KeepAliveService::class.java)) } catch (_: Exception) {}

        scope.launch {
            try {
                for ((sender, bodyBuilder) in grouped) {
                    val body = bodyBuilder.toString().trim()
                    Log.d("OtpRelay", "Relaying SMS from $sender")
                    prefs.edit()
                        .putString("last_sms", "Last SMS: $sender @ ${
                            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        }").apply()
                    val result = Relay.relay(appContext, sender, body)
                    Log.i("OtpRelay", result)
                    prefs.edit().putString("last_result", result).apply()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
