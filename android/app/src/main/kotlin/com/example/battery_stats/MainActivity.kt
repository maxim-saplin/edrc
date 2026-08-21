package com.example.battery_stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.example.battery_stats/battery"
    private lateinit var store: SampleStore
    private var eventSink: EventChannel.EventSink? = null
    private var eventsRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            eventSink?.success(snapshotMap())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SampleStore(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSnapshot" -> result.success(snapshotMap())
                    "getDayMetrics" -> {
                        val todayKey = store.dateKeyFor(System.currentTimeMillis())
                        val dayKeys = store.lastSevenDateKeys()
                        val days = dayKeys.map { store.metricsForDateKey(it).toMap() }
                        val week = store.metricsForLastSevenDays().toMap()
                        result.success(
                            mapOf(
                                "todayKey" to todayKey,
                                "days" to days,
                                "week" to week,
                            ),
                        )
                    }
                    "getSetupStatus" -> result.success(SetupHelper.getSetupStatus(this))
                    "requestSetup" -> {
                        when (call.argument<String>("type")) {
                            "notifications" -> {
                                SetupHelper.requestNotificationPermission(this)
                                result.success(true)
                            }
                            "battery_optimization" -> {
                                SetupHelper.requestBatteryUnrestricted(this)
                                result.success(true)
                            }
                            "battery_settings" -> {
                                SetupHelper.openBatterySettings(this)
                                result.success(true)
                            }
                            "autostart" -> {
                                SetupHelper.openAutostartSettings(this)
                                result.success(true)
                            }
                            "autostart_ack" -> {
                                SetupHelper.acknowledgeAutostart(this)
                                result.success(true)
                            }
                            "notification_channel" -> {
                                SetupHelper.openSamplingNotificationSettings(this)
                                result.success(true)
                            }
                            else -> result.error("INVALID", "Unknown setup type", null)
                        }
                    }
                    "startCollector" -> {
                        if (!CollectorStarter.isReady(this)) {
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        CollectorStarter.startIfReady(this)
                        result.success(BatterySampleService.isRunning)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "$channelName/events")
            .setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                        eventSink = events
                        registerBatteryEvents()
                        events?.success(snapshotMap())
                    }

                    override fun onCancel(arguments: Any?) {
                        unregisterBatteryEvents()
                        eventSink = null
                    }
                },
            )
    }

    override fun onDestroy() {
        unregisterBatteryEvents()
        eventSink = null
        super.onDestroy()
    }

    private fun snapshotMap(): Map<String, Any> {
        val live = BatteryProbe.read(this)
        val lastLog = store.latestSample()
        return mapOf(
            "level" to (live?.level ?: -1),
            "plugged" to (live?.plugged ?: false),
            "charging" to (live?.charging ?: false),
            "screenOn" to (live?.screenOn ?: false),
            "timestampMs" to (live?.timestampMs ?: 0L),
            "lastLogTimestampMs" to (lastLog?.timestampMs ?: 0L),
            "collectorRunning" to BatterySampleService.isRunning,
        )
    }

    private fun registerBatteryEvents() {
        if (eventsRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        eventsRegistered = true
    }

    private fun unregisterBatteryEvents() {
        if (!eventsRegistered) return
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: IllegalArgumentException) {
        }
        eventsRegistered = false
    }
}
