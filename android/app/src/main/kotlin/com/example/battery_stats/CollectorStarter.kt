package com.example.battery_stats

import android.content.Context

object CollectorStarter {
    fun isReady(context: Context): Boolean {
        return SetupHelper.hasNotificationPermission(context) &&
            SetupHelper.isBatteryUnrestricted(context) &&
            SetupHelper.isAutostartAcknowledged(context)
    }

    fun startIfReady(context: Context) {
        SampleScheduler(context).scheduleNext()
        CollectorHeartbeatJob.schedule(context)
        if (!isReady(context)) return
        try {
            if (BatterySampleService.isRunning) {
                BatterySampleService.requestTick(context)
            } else {
                BatterySampleService.start(context)
            }
        } catch (_: Exception) {
            SampleScheduler(context).scheduleNext()
        }
    }
}
