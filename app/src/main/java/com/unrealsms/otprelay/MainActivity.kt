package com.unrealsms.otprelay

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var botToken:    EditText
    private lateinit var chatId:      EditText
    private lateinit var supabaseUrl: EditText
    private lateinit var supabaseKey: EditText
    private lateinit var statusText:  TextView
    private lateinit var lastSmsText: TextView
    private lateinit var simGroup:    RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botToken    = findViewById(R.id.botToken)
        chatId      = findViewById(R.id.chatId)
        supabaseUrl = findViewById(R.id.supabaseUrl)
        supabaseKey = findViewById(R.id.supabaseKey)
        statusText  = findViewById(R.id.statusText)
        lastSmsText = findViewById(R.id.lastSmsText)
        simGroup    = findViewById(R.id.simGroup)

        val cfg = Relay.loadConfig(this)
        botToken.setText(cfg.botToken)
        chatId.setText(cfg.chatId)
        if (cfg.supabaseUrl.isNotBlank()) supabaseUrl.setText(cfg.supabaseUrl)
        supabaseKey.setText(cfg.supabaseKey)

        // Restore saved SIM filter: -1=All, 0=SIM1, 1=SIM2
        val savedSim = getSharedPreferences("otp_relay_prefs", MODE_PRIVATE).getInt("sim_filter", -1)
        simGroup.check(when (savedSim) {
            0    -> R.id.simOne
            1    -> R.id.simTwo
            else -> R.id.simAll
        })

        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.notifBtn).setOnClickListener { openNotificationAccess() }
        findViewById<Button>(R.id.defaultSmsBtn).setOnClickListener { requestDefaultSmsApp() }

        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            statusText.text = "Service error: ${e.message}"
        }

        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs      = getSharedPreferences("otp_relay_prefs", MODE_PRIVATE)
        val lastSms    = prefs.getString("last_sms", "No SMS received yet") ?: ""
        val lastResult = prefs.getString("last_result", "") ?: ""

        val defaultStatus = if (isDefaultSmsApp())
            "✅ Default SMS app: YES (100% reliable)"
        else
            "⚠️ Default SMS app: NO — tap button to set"

        val notifStatus = if (isNotificationListenerEnabled())
            "✅ Notification access: granted"
        else
            "⚠️ Notification access: NOT granted"

        val simLabel = when (prefs.getInt("sim_filter", -1)) {
            0    -> "📶 Forwarding: SIM 1 only"
            1    -> "📶 Forwarding: SIM 2 only"
            else -> "📶 Forwarding: All SIMs"
        }

        lastSmsText.text = "$defaultStatus\n$notifStatus\n$simLabel\n\n${
            if (lastResult.isNotBlank()) "$lastSms\n$lastResult" else lastSms
        }"
    }

    private fun save() {
        Relay.saveConfig(this, Relay.Config(
            botToken    = botToken.text.toString().trim(),
            chatId      = chatId.text.toString().trim(),
            supabaseUrl = supabaseUrl.text.toString().trim(),
            supabaseKey = supabaseKey.text.toString().trim(),
        ))

        val simFilter = when (simGroup.checkedRadioButtonId) {
            R.id.simOne -> 0
            R.id.simTwo -> 1
            else        -> -1
        }
        getSharedPreferences("otp_relay_prefs", MODE_PRIVATE).edit()
            .putInt("sim_filter", simFilter)
            .apply()

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun sendTest() {
        save()
        statusText.text = "Sending…"
        Thread {
            val result = Relay.relay(this, "TEST", "Your OTP is 123456")
            runOnUiThread { statusText.text = result }
        }.start()
    }

    private fun isDefaultSmsApp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
    }

    private fun requestDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS), 42)
            } else {
                Toast.makeText(this, "Already default SMS app!", Toast.LENGTH_SHORT).show()
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            startActivity(intent)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(this, SmsNotificationListener::class.java).flattenToString())
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (_: Exception) {
            Toast.makeText(this, "Settings → Notification access → OTP Relay", Toast.LENGTH_LONG).show()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Settings → Apps → OTP Relay → Battery → Unrestricted", Toast.LENGTH_LONG).show()
            }
        }
    }
}
