package com.unrealsms.otprelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var botToken:    EditText
    private lateinit var chatId:      EditText
    private lateinit var supabaseUrl: EditText
    private lateinit var supabaseKey: EditText
    private lateinit var statusText:  TextView
    private lateinit var lastSmsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botToken    = findViewById(R.id.botToken)
        chatId      = findViewById(R.id.chatId)
        supabaseUrl = findViewById(R.id.supabaseUrl)
        supabaseKey = findViewById(R.id.supabaseKey)
        statusText  = findViewById(R.id.statusText)
        lastSmsText = findViewById(R.id.lastSmsText)

        val cfg = Relay.loadConfig(this)
        botToken.setText(cfg.botToken)
        chatId.setText(cfg.chatId)
        if (cfg.supabaseUrl.isNotBlank()) supabaseUrl.setText(cfg.supabaseUrl)
        supabaseKey.setText(cfg.supabaseKey)

        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { openBatterySettings() }

        // Start foreground service
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            statusText.text = "Service error: ${e.message}"
        }

        // SMS permission
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        // Show last real SMS result each time screen comes to foreground
        val prefs = getSharedPreferences("otp_relay_prefs", MODE_PRIVATE)
        val lastSms    = prefs.getString("last_sms", "No SMS received yet") ?: ""
        val lastResult = prefs.getString("last_result", "") ?: ""
        lastSmsText.text = if (lastResult.isNotBlank()) "$lastSms\n$lastResult" else lastSms
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

    private fun save() {
        Relay.saveConfig(this, Relay.Config(
            botToken    = botToken.text.toString().trim(),
            chatId      = chatId.text.toString().trim(),
            supabaseUrl = supabaseUrl.text.toString().trim(),
            supabaseKey = supabaseKey.text.toString().trim(),
        ))
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
}
