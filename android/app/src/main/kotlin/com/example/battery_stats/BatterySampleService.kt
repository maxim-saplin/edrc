package com.example.battery_stats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class BatterySampleService : Service() {
    private lateinit var store: SampleStore
    private lateinit var scheduler: SampleScheduler
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiversRegistered = false
    private var handlerStarted = false

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            recordSample("handler_tick")
            refreshNotification()
            handler.postDelayed(this, HANDLER_TICK_MS)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF -> recordSample(intent.action ?: "unknown")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = SampleStore(this)
        scheduler = SampleScheduler(this)
        createNotificationChannel()
        registerReceivers()
        recordSample("service_start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        scheduler.scheduleNext()
        CollectorHeartbeatJob.schedule(this)
        if (!handlerStarted) {
            handlerStarted = true
            handler.postDelayed(tickRunnable, HANDLER_TICK_MS)
        }
        if (intent?.action == ACTION_TICK) {
            recordSample("alarm_tick")
            refreshNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        handlerStarted = false
        scheduler.cancel()
        releaseWakeLock()
        unregisterReceivers()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
        receiversRegistered = false
    }

    private fun recordSample(reason: String) {
        acquireWakeLock()
        try {
            val reading = BatteryProbe.read(this) ?: return
            store.appendSample(reading.toSample(reason))
        } finally {
            releaseWakeLock()
        }
    }

    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "battery_stats:sample").apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_V2)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery sampling",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Required while the collector runs. Minimize or hide this channel in system settings."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sampling battery")
            .setContentText("Logging screen-on endurance")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "battery_sampling_v3"
        const val LEGACY_CHANNEL_ID = "battery_sampling"
        const val LEGACY_CHANNEL_ID_V2 = "battery_sampling_v2"
        const val NOTIFICATION_ID = 42
        const val ACTION_TICK = "com.example.battery_stats.action.TICK"

        private const val HANDLER_TICK_MS = 5 * 60 * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, BatterySampleService::class.java)
            isRunning = true
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                isRunning = false
                throw e
            }
        }

        fun requestTick(context: Context) {
            val intent = Intent(context, BatterySampleService::class.java).apply {
                action = ACTION_TICK
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatterySampleService::class.java))
        }
    }
}
