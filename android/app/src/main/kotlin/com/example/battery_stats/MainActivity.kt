// MainActivity.kt
package com.example.battery_stats

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.battery_stats/battery"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getBatteryUsageStats" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (!hasUsageStatsPermission()) {
                            // Request permission
                            requestUsageStatsPermission()
                            result.error("PERMISSION_DENIED", 
                                "Usage stats permission required. Please grant permission and try again.", null)
                            return@setMethodCallHandler
                        }
                        
                        try {
                            val stats = getBatteryUsageStats()
                            result.success(stats)
                        } catch (e: Exception) {
                            result.error("STATS_ERROR", "Error getting battery stats: ${e.message}", null)
                        }
                    } else {
                        result.error("API_ERROR", "BatteryUsageStats API requires Android P (API 28) or higher", null)
                    }
                }
                "getBatteryLevelHistory" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        if (!hasUsageStatsPermission()) {
                            // Request permission
                            requestUsageStatsPermission()
                            result.error("PERMISSION_DENIED", 
                                "Usage stats permission required. Please grant permission and try again.", null)
                            return@setMethodCallHandler
                        }
                        
                        try {
                            val history = getBatteryLevelHistory()
                            result.success(history)
                        } catch (e: Exception) {
                            result.error("HISTORY_ERROR", "Error getting battery history: ${e.message}", null)
                        }
                    } else {
                        result.error("API_ERROR", "Battery history requires Android P (API 28) or higher", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
    
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getBatteryUsageStats(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // For Android 12 (API 31) and above, use BatteryUsageStatsManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    // Using reflection to access Android S APIs without direct dependency
                    val batteryStatsManagerClass = Class.forName("android.os.BatteryUsageStatsManager")
                    val batteryUsageStatsManager = getSystemService(batteryStatsManagerClass) 
                    
                    val queryBuilderClass = Class.forName("android.os.BatteryUsageStatsQuery\$Builder")
                    val queryBuilder = queryBuilderClass.getDeclaredConstructor().newInstance()
                    
                    val setMaxStatsAgeMs = queryBuilderClass.getMethod("setMaxStatsAgeMs", Long::class.java)
                    setMaxStatsAgeMs.invoke(queryBuilder, TimeUnit.HOURS.toMillis(24))
                    
                    val includeProcessStateData = queryBuilderClass.getMethod("includeProcessStateData")
                    includeProcessStateData.invoke(queryBuilder)
                    
                    val includeBatteryDischargeData = queryBuilderClass.getMethod("includeBatteryDischargeData")
                    includeBatteryDischargeData.invoke(queryBuilder)
                    
                    val build = queryBuilderClass.getMethod("build")
                    val query = build.invoke(queryBuilder)
                    
                    val getBatteryUsageStats = batteryStatsManagerClass.getMethod("getBatteryUsageStats", 
                        Class.forName("android.os.BatteryUsageStatsQuery"))
                    val batteryUsageStats = getBatteryUsageStats.invoke(batteryUsageStatsManager!!, query)
                    
                    val getUidBatteryConsumers = batteryUsageStats?.javaClass?.getMethod("getUidBatteryConsumers")
                    val uidBatteryConsumers = getUidBatteryConsumers?.invoke(batteryUsageStats) as? List<*> ?: listOf<Any>()
                    
                    val batteryConsumerClass = Class.forName("android.os.BatteryConsumer")
                    val PROCESS_STATE_FOREGROUND = batteryConsumerClass.getField("PROCESS_STATE_FOREGROUND").get(null) as Int
                    val PROCESS_STATE_BACKGROUND = batteryConsumerClass.getField("PROCESS_STATE_BACKGROUND").get(null) as Int
                    
                    val getBatteryCapacity = batteryUsageStats?.javaClass?.getMethod("getBatteryCapacity")
                    val batteryCapacity = getBatteryCapacity?.invoke(batteryUsageStats) as? Double ?: 1.0
                    
                    // Process app stats
                    for (consumer in uidBatteryConsumers) {
                        try {
                            val getUid = consumer?.javaClass?.getMethod("getUid")
                            val uid = getUid?.invoke(consumer) as? Int ?: continue
                            
                            val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "Unknown"
                            if (packageName == "Unknown") continue
                            
                            val appInfo = packageName.let { packageManager.getApplicationInfo(it, 0) }
                            val appName = packageManager.getApplicationLabel(appInfo).toString()
                            
                            val getConsumedPower = consumer.javaClass.getMethod("getConsumedPower")
                            val consumedPower = getConsumedPower.invoke(consumer) as? Double ?: 0.0
                            
                            val getTimeInStateMs = consumer.javaClass.getMethod("getTimeInStateMs", Int::class.java)
                            val foregroundTime = (getTimeInStateMs.invoke(consumer, PROCESS_STATE_FOREGROUND) as? Long) ?: 0L
                            val backgroundTime = (getTimeInStateMs.invoke(consumer, PROCESS_STATE_BACKGROUND) as? Long) ?: 0L
                            
                            val statMap = mutableMapOf<String, Any>()
                            statMap["packageName"] = appName
                            statMap["uid"] = uid
                            statMap["consumedPowerMah"] = consumedPower
                            statMap["powerUsagePercent"] = String.format("%.1f", consumedPower / batteryCapacity * 100)
                            statMap["foregroundUsageTimeMs"] = foregroundTime
                            statMap["backgroundUsageTimeMs"] = backgroundTime
                            
                            result.add(statMap)
                        } catch (e: Exception) {
                            // Skip this entry if there's any issue
                            continue
                        }
                    }
                } catch (e: Exception) {
                    // Fall back to usage stats if reflection fails
                    getUsageStatsOnly(result)
                }
            } else {
                // For older Android versions, use the legacy approach
                getUsageStatsOnly(result)
            }
        }
        
        // Return the apps sorted by usage time if no power data available
        return if (result.any { it.containsKey("consumedPowerMah") }) {
            result.sortedByDescending { it["consumedPowerMah"] as? Double ?: 0.0 }
        } else {
            result.sortedByDescending { it["foregroundUsageTimeMs"] as? Long ?: 0L }
        }
    }
    
    private fun getUsageStatsOnly(result: MutableList<Map<String, Any>>) {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - TimeUnit.DAYS.toMillis(1)
        
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        
        // Get basic stats at least
        for (stat in usageStats) {
            try {
                if (stat.totalTimeInForeground < 1000) continue // Skip apps used less than 1 second
                
                val packageName = stat.packageName
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                
                val statMap = mutableMapOf<String, Any>()
                statMap["packageName"] = appName
                statMap["foregroundUsageTimeMs"] = stat.totalTimeInForeground
                
                // For older versions, estimate background time as 25% of foreground time (just for demo purposes)
                statMap["backgroundUsageTimeMs"] = (stat.totalTimeInForeground * 0.25).toLong()
                
                result.add(statMap)
            } catch (e: PackageManager.NameNotFoundException) {
                // Skip this entry if package can't be resolved
                continue
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun getBatteryLevelHistory(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                // Using reflection to access Android S APIs
                val batteryStatsManagerClass = Class.forName("android.os.BatteryUsageStatsManager")
                val batteryUsageStatsManager = getSystemService(batteryStatsManagerClass)
                
                val queryBuilderClass = Class.forName("android.os.BatteryUsageStatsQuery\$Builder")
                val queryBuilder = queryBuilderClass.getDeclaredConstructor().newInstance()
                
                // Set to get data for past 24 hours
                val setMaxStatsAgeMs = queryBuilderClass.getMethod("setMaxStatsAgeMs", Long::class.java)
                setMaxStatsAgeMs.invoke(queryBuilder, TimeUnit.HOURS.toMillis(24))
                
                // Include battery discharge data (history)
                val includeBatteryDischargeData = queryBuilderClass.getMethod("includeBatteryDischargeData")
                includeBatteryDischargeData.invoke(queryBuilder)
                
                val build = queryBuilderClass.getMethod("build")
                val query = build.invoke(queryBuilder)
                
                val getBatteryUsageStats = batteryStatsManagerClass.getMethod("getBatteryUsageStats", 
                    Class.forName("android.os.BatteryUsageStatsQuery"))
                val batteryUsageStats = getBatteryUsageStats.invoke(batteryUsageStatsManager!!, query)
                
                // Get battery history data
                val getBatteryHistory = batteryUsageStats?.javaClass?.getMethod("getBatteryHistory")
                val batteryHistory = getBatteryHistory?.invoke(batteryUsageStats) as? List<*> ?: listOf<Any>()
                
                for (historyEntry in batteryHistory) {
                    try {
                        val entryClass = historyEntry?.javaClass
                        val getTimestampMs = entryClass?.getMethod("getTimestampMs")
                        val timestamp = getTimestampMs?.invoke(historyEntry) as? Long ?: continue
                        
                        val getBatteryLevel = entryClass?.getMethod("getBatteryLevel")
                        val batteryLevel = getBatteryLevel?.invoke(historyEntry) as? Int ?: continue
                        
                        result.add(mapOf(
                            "timestamp" to timestamp,
                            "batteryLevel" to batteryLevel
                        ))
                    } catch (e: Exception) {
                        continue
                    }
                }
                
                // Return empty list if we can't get real data
                return result.sortedBy { it["timestamp"] as Long }
                
            } catch (e: Exception) {
                // Return empty list instead of mock data
                return emptyList()
            }
        } else {
            // Return empty list for older devices instead of mock data
            return emptyList()
        }
    }
}