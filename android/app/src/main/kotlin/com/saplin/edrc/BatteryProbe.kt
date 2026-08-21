package com.saplin.edrc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class BatteryReading(
    val timestampMs: Long,
    val level: Int,
    val plugged: Boolean,
    val charging: Boolean,
    val screenOn: Boolean,
)

object BatteryProbe {
    fun read(context: Context): BatteryReading? {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pluggedBits = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        if (percent < 0) return null

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val apiCharging = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && bm.isCharging
        val plugged = pluggedBits != 0 || apiCharging
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (apiCharging && status != BatteryManager.BATTERY_STATUS_DISCHARGING)
        val screenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        return BatteryReading(
            timestampMs = System.currentTimeMillis(),
            level = percent,
            plugged = plugged,
            charging = charging,
            screenOn = screenOn,
        )
    }
}
