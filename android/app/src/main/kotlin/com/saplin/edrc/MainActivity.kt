package com.saplin.edrc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread

class MainActivity : FlutterActivity() {
    private val channelName = "com.saplin.edrc/battery"
    private var eventSink: EventChannel.EventSink? = null
    private var shizukuSink: EventChannel.EventSink? = null
    private var eventsRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val shizukuStatusListener: (Map<String, Any>) -> Unit = { status ->
        shizukuSink?.success(status)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            eventSink?.success(snapshotMap())
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        ShizukuHelper.init(this)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSnapshot" -> result.success(snapshotMap())
                    "getSetupStatus" -> result.success(ShizukuHelper.status(this))
                    "requestSetup" -> {
                        when (call.argument<String>("type")) {
                            "shizuku_open" -> {
                                ShizukuHelper.openShizuku(this)
                                result.success(true)
                            }
                            "shizuku_permission" -> {
                                ShizukuHelper.requestPermission()
                                result.success(true)
                            }
                            else -> result.error("INVALID", "Unknown setup type", null)
                        }
                    }
                    "getMetrics" -> {
                        thread {
                            try {
                                val payload = BatterystatsRepository.metrics(
                                    this,
                                    force = call.argument<Boolean>("force") == true,
                                )
                                mainHandler.post { result.success(payload) }
                            } catch (e: Exception) {
                                mainHandler.post {
                                    result.error("DUMP", e.message, null)
                                }
                            }
                        }
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

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "$channelName/shizuku")
            .setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                        shizukuSink = events
                        ShizukuHelper.addStatusListener(shizukuStatusListener)
                    }

                    override fun onCancel(arguments: Any?) {
                        ShizukuHelper.removeStatusListener(shizukuStatusListener)
                        shizukuSink = null
                    }
                },
            )
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.maybeAskPermission()
        shizukuSink?.success(ShizukuHelper.status(this))
    }

    override fun onDestroy() {
        ShizukuHelper.removeStatusListener(shizukuStatusListener)
        unregisterBatteryEvents()
        eventSink = null
        shizukuSink = null
        super.onDestroy()
    }

    private fun snapshotMap(): Map<String, Any> {
        val live = BatteryProbe.read(this)
        return mapOf(
            "level" to (live?.level ?: -1),
            "plugged" to (live?.plugged ?: false),
            "charging" to (live?.charging ?: false),
            "screenOn" to (live?.screenOn ?: false),
            "timestampMs" to (live?.timestampMs ?: 0L),
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
