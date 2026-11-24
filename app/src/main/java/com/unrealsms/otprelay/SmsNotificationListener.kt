package com.unrealsms.otprelay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsNotificationListener : NotificationListenerService() {

    companion object {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
            "com.miui.sms",
            "com.motorola.messaging",
            "com.sonyericsson.conversations",
            "com.vivo.messaging",
            "com.oppo.mms",
        )

        // Patterns that identify service/status notifications rather than real SMS.
        // Checked against the notification body (lowercase).
        private val NOISE_BODY_PATTERNS = listOf(
            "is doing work in the background",
            "is working in the background",
            "working in the background",
            "sync in progress",
            "checking for messages",
            "downloading messages",
            "new message notification",   // generic app-level, no content
        )

        // If the sender looks like an app name (contains the package's app label keywords)
        // and the body matches a noise pattern — it's a service notification.
        fun isServiceNoise(sender: String, body: String): Boolean {
            val bodyLower = body.lowercase()
            return NOISE_BODY_PATTERNS.any { bodyLower.contains(it) }
        }

        // SIM slot labels that appear in notification text on most phones
        // e.g. "[SIM 1]", "[SIM1]", "SIM 2 •", "SIM2:", etc.
        private val SIM1_PATTERNS = listOf(Regex("\\bSIM\\s*1\\b", RegexOption.IGNORE_CASE))
        private val SIM2_PATTERNS = listOf(Regex("\\bSIM\\s*2\\b", RegexOption.IGNORE_CASE))

        /** Returns 0 for SIM1 hint, 1 for SIM2 hint, -1 if unknown */
        fun detectSimFromText(text: String): Int {
            if (SIM1_PATTERNS.any { it.containsMatchIn(text) }) return 0
            if (SIM2_PATTERNS.any { it.containsMatchIn(text) }) return 1
            return -1
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in SMS_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val sender  = extras.getCharSequence("android.title")?.toString()?.trim() ?: return
        val body    = extras.getCharSequence("android.text")?.toString()?.trim() ?: return

        // Skip blank sender — these are Android privacy redactions ("Sensitive notification
        // content hidden"). No real content to forward even if we wanted to.
        if (sender.isBlank()) return
        if (body.isBlank()) return

        // Skip service/status notifications from the SMS app itself — not actual messages.
        // These show up as "[App] is doing work in the background", sync notices, etc.
        if (isServiceNoise(sender, body)) {
            Log.d("OtpRelay", "Notification skipped (noise): $sender | $body")
            return
        }

        // --- SIM filter ---
        val prefs      = getSharedPreferences("otp_relay_prefs", MODE_PRIVATE)
        val simFilter  = prefs.getInt("sim_filter", -1)

        if (simFilter >= 0) {
            // Try to detect SIM from notification title or body text
            val combined = "$sender $body"
            val detectedSlot = detectSimFromText(combined)
            Log.d("OtpRelay", "Notif SIM detection: '$combined' → slot=$detectedSlot filter=$simFilter")
            // Only skip if we positively detected the WRONG SIM
            if (detectedSlot >= 0 && detectedSlot != simFilter) return
        }

        Log.d("OtpRelay", "Notification relay: $sender")

        val appContext = applicationContext
        prefs.edit().putString("last_sms", "SMS via notification: $sender @ ${
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        }").apply()

        scope.launch {
            val result = Relay.relay(appContext, sender, body)
            Log.i("OtpRelay", result)
            prefs.edit().putString("last_result", result).apply()
        }
    }
}
