package com.unrealsms.otprelay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    private var smsObserver: SmsContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, "otp_relay_channel")
            .setContentTitle("OTP Relay")
            .setContentText("Listening for SMS…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1001, notif)
        }

        // Register ContentObserver if not already running and READ_SMS is granted.
        // This is the most reliable forward path — no default SMS role needed.
        if (smsObserver == null) {
            registerObserverIfPermitted()
        }

        return START_STICKY
    }

    private fun registerObserverIfPermitted() {
        val hasReadSms = checkSelfPermission(android.Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasReadSms) {
            Log.w("OtpRelay", "READ_SMS not granted — ContentObserver not registered")
            return
        }
        try {
            val observer = SmsContentObserver(Handler(Looper.getMainLooper()), applicationContext)
            contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,   // notifyForDescendants — catches inbox, sent, drafts sub-URIs
                observer
            )
            smsObserver = observer
            Log.i("OtpRelay", "ContentObserver registered on content://sms")
        } catch (e: Exception) {
            Log.e("OtpRelay", "Failed to register ContentObserver: ${e.message}")
        }
    }

    override fun onDestroy() {
        smsObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
            Log.i("OtpRelay", "ContentObserver unregistered")
        }
        smsObserver = null
        super.onDestroy()
    }
}
