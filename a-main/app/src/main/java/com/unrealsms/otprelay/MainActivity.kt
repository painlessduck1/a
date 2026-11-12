package com.unrealsms.otprelay

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** One screen: bot token, chat id, site URL, shared supabaseKey, Save, and a Send test button. */
class MainActivity : Activity() {

    private lateinit var botToken: EditText
    private lateinit var chatId: EditText
    private lateinit var supabaseUrl: EditText
    private lateinit var supabaseKey: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        botToken = findViewById(R.id.botToken)
        chatId = findViewById(R.id.chatId)
        supabaseUrl = findViewById(R.id.supabaseUrl)
        supabaseKey = findViewById(R.id.supabaseKey)
        statusText = findViewById(R.id.statusText)

        val cfg = Relay.loadConfig(this)
        botToken.setText(cfg.botToken)
        chatId.setText(cfg.chatId)
        if (cfg.supabaseUrl.isNotBlank()) supabaseUrl.setText(cfg.supabaseUrl)
        supabaseKey.setText(cfg.supabaseKey)

        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }
        findViewById<Button>(R.id.testBtn).setOnClickListener { sendTest() }

        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1)
        }
    }

    private fun save() {
        Relay.saveConfig(
            this,
            Relay.Config(
                botToken = botToken.text.toString().trim(),
                chatId = chatId.text.toString().trim(),
                supabaseUrl = supabaseUrl.text.toString().trim(),
                supabaseKey = supabaseKey.text.toString().trim(),
            ),
        )
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun sendTest() {
        save()
        statusText.text = "Sending..."
        Thread {
            val result = Relay.relay(this, "TEST", "Your OTP is 123456")
            runOnUiThread { statusText.text = result }
        }.start()
    }
}
