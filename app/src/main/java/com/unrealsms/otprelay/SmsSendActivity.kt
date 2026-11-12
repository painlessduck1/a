package com.unrealsms.otprelay

import android.app.Activity
import android.os.Bundle

/**
 * Required stub for default SMS app — Android won't let you set a default SMS
 * app unless it has an activity that handles ACTION_SENDTO with smsto: scheme.
 * This just finishes immediately; we don't need a real compose UI.
 */
class SmsSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
