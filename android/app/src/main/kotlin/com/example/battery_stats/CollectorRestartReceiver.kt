package com.example.battery_stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CollectorRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> CollectorStarter.startIfReady(context)
        }
    }
}
