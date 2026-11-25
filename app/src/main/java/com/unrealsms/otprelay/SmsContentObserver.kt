package com.unrealsms.otprelay

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Watches content://sms for new rows. Fires whenever the default SMS app writes
 * a new message to the inbox — no SMS role or broadcast permission needed, only
 * READ_SMS. This is the guaranteed path that works regardless of default-app state.
 *
 * Registered and unregistered by KeepAliveService.
 */
class SmsContentObserver(
    handler: Handler,
    private val context: Context
) : ContentObserver(handler) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track the timestamp of the last row we processed so we don't re-forward
    // messages that were in the inbox before the observer started.
    // Start 10 s in the past to catch any message that arrived while the service
    // was restarting, but not anything older.
    private var lastSeenTimestamp = System.currentTimeMillis() - 10_000L

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        scope.launch {
            try {
                val prefs = context.getSharedPreferences("otp_relay_prefs", Context.MODE_PRIVATE)

                val cursor = context.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("_id", "address", "body", "date", "type"),
                    "date > ? AND type = 1",   // type 1 = received
                    arrayOf(lastSeenTimestamp.toString()),
                    "date ASC"                 // oldest-first so we forward in order
                ) ?: return@launch

                cursor.use {
                    while (it.moveToNext()) {
                        val date   = it.getLong(it.getColumnIndexOrThrow("date"))
                        val sender = it.getString(it.getColumnIndexOrThrow("address"))?.trim()
                            ?: continue
                        val body   = it.getString(it.getColumnIndexOrThrow("body"))?.trim()
                            ?: continue

                        // Advance our cursor regardless — don't re-process this row
                        if (date > lastSeenTimestamp) lastSeenTimestamp = date

                        if (sender.isBlank() || body.isBlank()) continue

                        val dedupKey = "$sender|$body"
                        if (SmsReceiver.isDuplicate(dedupKey)) {
                            Log.d("OtpRelay", "ContentObserver: skipping duplicate from $sender")
                            continue
                        }

                        Log.d("OtpRelay", "ContentObserver: forwarding SMS from $sender")

                        prefs.edit()
                            .putString(
                                "last_sms", "Last SMS: $sender @ ${
                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(date))
                                } [inbox]"
                            ).apply()

                        val result = Relay.relay(context, sender, body)
                        Log.i("OtpRelay", "ContentObserver relay result: $result")
                        prefs.edit().putString("last_result", result).apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("OtpRelay", "ContentObserver error: ${e.message}")
            }
        }
    }
}
