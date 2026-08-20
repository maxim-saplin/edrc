package com.example.battery_stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SampleTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CollectorStarter.startIfReady(context)
        SampleScheduler(context).scheduleNext()
    }
}
