package com.example.battery_stats

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object SetupHelper {
    private const val PREFS = "battery_stats_setup"
    private const val KEY_AUTOSTART_ACK = "autostart_acknowledged"

    fun getSetupStatus(context: Context): Map<String, Any> {
        val notificationsOk = hasNotificationPermission(context)
        val batteryUnrestricted = isBatteryUnrestricted(context)
        val autostartAcknowledged = isAutostartAcknowledged(context)
        val ready = notificationsOk && batteryUnrestricted && autostartAcknowledged
        return mapOf(
            "notificationsGranted" to notificationsOk,
            "batteryUnrestricted" to batteryUnrestricted,
            "autostartAcknowledged" to autostartAcknowledged,
            "ready" to ready,
            "collectorRunning" to BatterySampleService.isRunning,
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAutostartAcknowledged(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART_ACK, false)
    }

    fun acknowledgeAutostart(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTOSTART_ACK, true)
            .apply()
    }

    fun requestNotificationPermission(activity: MainActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }
    }

    fun requestBatteryUnrestricted(activity: MainActivity) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${activity.packageName}".toUri()
        }
        activity.startActivity(intent)
    }

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAutostartSettings(context: Context) {
        val candidates = listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.privacypermissionsentry.PermissionTopActivity",
            ),
        )
        for (component in candidates) {
            try {
                val intent = Intent().setComponent(component).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        openBatterySettings(context)
    }

    fun openSamplingNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, BatterySampleService.CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openBatterySettings(context)
        }
    }

    const val REQUEST_NOTIFICATIONS = 1001
}
