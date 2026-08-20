package com.example.battery_stats

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class BatterySample(
    val timestampMs: Long,
    val level: Int,
    val chargeCounterUah: Int?,
    val plugged: Boolean,
    val charging: Boolean,
    val screenOn: Boolean,
    val reason: String? = null,
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("t", timestampMs)
        put("l", level)
        if (chargeCounterUah != null) put("c", chargeCounterUah)
        put("p", plugged)
        put("chg", charging)
        put("s", screenOn)
        if (!reason.isNullOrEmpty()) put("r", reason)
    }.toString()

    companion object {
        fun fromJsonLine(line: String): BatterySample? {
            return try {
                val o = JSONObject(line)
                val plugged = o.getBoolean("p")
                BatterySample(
                    timestampMs = o.getLong("t"),
                    level = o.getInt("l"),
                    chargeCounterUah = if (o.has("c")) o.getInt("c") else null,
                    plugged = plugged,
                    charging = if (o.has("chg")) o.getBoolean("chg") else plugged,
                    screenOn = o.getBoolean("s"),
                    reason = if (o.has("r")) o.getString("r") else null,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class DayMetrics(
    val dateKey: String,
    val sotHoursPer100: Double?,
    val screenOnHours: Double,
    val screenOnPercentDrop: Double,
    val totalUnpluggedPercentDrop: Double,
    val stepCount: Int,
    val hasEnoughData: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "dateKey" to dateKey,
        "sotHoursPer100" to sotHoursPer100,
        "screenOnHours" to screenOnHours,
        "screenOnPercentDrop" to screenOnPercentDrop,
        "totalUnpluggedPercentDrop" to totalUnpluggedPercentDrop,
        "stepCount" to stepCount,
        "hasEnoughData" to hasEnoughData,
    )
}

class SampleStore(private val context: Context) {
    private val samplesFile: File
        get() = File(context.filesDir, "battery_samples.jsonl")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile
    private var cachedLatest: BatterySample? = null

    fun appendSample(sample: BatterySample) {
        if (shouldSkipDuplicate(sample)) return
        FileWriter(samplesFile, true).use { writer ->
            writer.append(sample.toJsonLine())
            writer.append('\n')
        }
        cachedLatest = sample
    }

    private fun shouldSkipDuplicate(sample: BatterySample): Boolean {
        val latest = latestSample() ?: return false
        if (sample.timestampMs - latest.timestampMs > DEDUPE_WINDOW_MS) return false
        if (sample.level != latest.level) return false
        if (sample.plugged != latest.plugged) return false
        if (sample.charging != latest.charging) return false
        if (sample.screenOn != latest.screenOn) return false
        if (sample.chargeCounterUah != null && latest.chargeCounterUah != null) {
            val delta = abs(sample.chargeCounterUah - latest.chargeCounterUah)
            if (delta >= MIN_CHARGE_DROP_UAH / 2) return false
        }
        return true
    }

    fun readSamples(): List<BatterySample> {
        val file = samplesFile
        if (!file.exists()) return emptyList()
        return file.readLines()
            .mapNotNull { BatterySample.fromJsonLine(it) }
            .sortedBy { it.timestampMs }
    }

    fun latestSample(): BatterySample? {
        cachedLatest?.let { return it }
        cachedLatest = peekLastSample()
        return cachedLatest
    }

    private fun peekLastSample(): BatterySample? {
        val file = samplesFile
        if (!file.exists() || file.length() == 0L) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                var pos = raf.length() - 1
                while (pos > 0) {
                    raf.seek(pos)
                    if (raf.readByte().toInt() != '\n'.code) break
                    pos--
                }
                while (pos > 0) {
                    raf.seek(pos)
                    if (raf.readByte().toInt() == '\n'.code) {
                        pos++
                        break
                    }
                    pos--
                }
                raf.seek(pos)
                val line = raf.readLine() ?: return null
                BatterySample.fromJsonLine(line)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun dateKeyFor(timestampMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return dateFormat.format(cal.time)
    }

    fun startOfDay(dateKey: String): Long {
        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(dateKey) ?: Date()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun endOfDay(dateKey: String): Long {
        val cal = Calendar.getInstance()
        cal.time = dateFormat.parse(dateKey) ?: Date()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun lastSevenDateKeys(): List<String> {
        val cal = Calendar.getInstance()
        return (6 downTo 0).map { offset ->
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_YEAR, -offset)
            dateFormat.format(c.time)
        }
    }

    fun metricsForRange(startMs: Long, endMs: Long, label: String): DayMetrics {
        val samples = readSamples().filter { it.timestampMs in startMs..endMs }
        return computeMetrics(samples, label)
    }

    fun metricsForDateKey(dateKey: String): DayMetrics {
        return metricsForRange(startOfDay(dateKey), endOfDay(dateKey), dateKey)
    }

    fun metricsForLastSevenDays(): DayMetrics {
        val keys = lastSevenDateKeys()
        val startMs = startOfDay(keys.first())
        val endMs = endOfDay(keys.last())
        return metricsForRange(startMs, endMs, "7d")
    }

    private fun estimateCapacityUah(samples: List<BatterySample>): Int {
        val estimates = samples.mapNotNull { sample ->
            if (sample.level <= 0 || sample.chargeCounterUah == null) return@mapNotNull null
            (sample.chargeCounterUah * 100.0 / sample.level).toInt()
        }
        return maxOf(estimates.maxOrNull() ?: DEFAULT_CAPACITY_UAH, DEFAULT_CAPACITY_UAH)
    }

    private fun computeMetrics(samples: List<BatterySample>, label: String): DayMetrics {
        if (samples.size < 2) {
            return DayMetrics(
                dateKey = label,
                sotHoursPer100 = null,
                screenOnHours = 0.0,
                screenOnPercentDrop = 0.0,
                totalUnpluggedPercentDrop = 0.0,
                stepCount = 0,
                hasEnoughData = false,
            )
        }

        val capacityUah = estimateCapacityUah(samples)

        var anchorLevel: Int? = null
        var anchorChargeUah: Int? = null
        var lastTime: Long? = null
        var lastScreenOn = false
        var screenOnMsInStep = 0L
        var screenOffMsInStep = 0L
        var flushedPercentInStep = 0.0

        var screenOnHoursTotal = 0.0
        var screenOnPercentTotal = 0.0
        var totalUnpluggedPercent = 0.0
        var stepCount = 0

        fun clearInterval() {
            screenOnMsInStep = 0L
            screenOffMsInStep = 0L
            flushedPercentInStep = 0.0
        }

        fun resetStepTracking(level: Int, chargeUah: Int?) {
            anchorLevel = level
            anchorChargeUah = chargeUah
            clearInterval()
        }

        fun creditDrop(percentDrop: Double) {
            if (percentDrop <= 0) return
            val stepMs = screenOnMsInStep + screenOffMsInStep
            if (stepMs <= 0) return
            val screenOnFraction = screenOnMsInStep.toDouble() / stepMs.toDouble()
            screenOnHoursTotal += screenOnMsInStep / 3_600_000.0
            screenOnPercentTotal += percentDrop * screenOnFraction
            totalUnpluggedPercent += percentDrop
            stepCount++
            screenOnMsInStep = 0L
            screenOffMsInStep = 0L
        }

        fun isChargeIncrease(sample: BatterySample): Boolean {
            val anchor = anchorLevel
            val anchorCharge = anchorChargeUah
            if (anchor != null && sample.level > anchor) return true
            if (anchorCharge != null && sample.chargeCounterUah != null) {
                return sample.chargeCounterUah > anchorCharge + CHARGE_NOISE_UAH
            }
            return false
        }

        for (sample in samples) {
            val chargingNow = sample.charging || isChargeIncrease(sample)
            if (chargingNow) {
                resetStepTracking(sample.level, sample.chargeCounterUah)
                lastTime = sample.timestampMs
                lastScreenOn = sample.screenOn
                continue
            }

            if (anchorLevel == null) {
                resetStepTracking(sample.level, sample.chargeCounterUah)
                lastTime = sample.timestampMs
                lastScreenOn = sample.screenOn
                continue
            }

            val prevTime = lastTime
            if (prevTime != null && sample.timestampMs > prevTime) {
                val delta = sample.timestampMs - prevTime
                if (lastScreenOn) {
                    screenOnMsInStep += delta
                } else {
                    screenOffMsInStep += delta
                }
            }

            val anchorCharge = anchorChargeUah
            val charge = sample.chargeCounterUah
            val anchor = anchorLevel
            val levelDropped = anchor != null && sample.level <= anchor - 1
            if (!levelDropped && anchorCharge != null && charge != null) {
                val chargeDrop = anchorCharge - charge
                if (chargeDrop >= MIN_CHARGE_DROP_UAH) {
                    val percentDrop = chargeDrop * 100.0 / capacityUah
                    creditDrop(percentDrop)
                    flushedPercentInStep += percentDrop
                    anchorChargeUah = charge
                }
            }

            if (levelDropped && anchor != null) {
                val raw = (anchor - sample.level).toDouble()
                val remaining = (raw - flushedPercentInStep).coerceAtLeast(0.0)
                creditDrop(remaining)
                resetStepTracking(sample.level, sample.chargeCounterUah)
            }

            lastTime = sample.timestampMs
            lastScreenOn = sample.screenOn
        }

        val last = samples.last()
        if (!last.charging && anchorLevel != null && (screenOnMsInStep + screenOffMsInStep) > 0) {
            val anchorCharge = anchorChargeUah
            val charge = last.chargeCounterUah
            if (anchorCharge != null && charge != null) {
                val chargeDrop = anchorCharge - charge
                if (chargeDrop >= MIN_CHARGE_DROP_UAH) {
                    creditDrop(chargeDrop * 100.0 / capacityUah)
                }
            }
        }

        val hasEnoughData = screenOnPercentTotal >= 3.0 && stepCount > 0
        val sot = if (hasEnoughData && screenOnPercentTotal > 0) {
            screenOnHoursTotal / (screenOnPercentTotal / 100.0)
        } else {
            null
        }

        return DayMetrics(
            dateKey = label,
            sotHoursPer100 = sot,
            screenOnHours = screenOnHoursTotal,
            screenOnPercentDrop = screenOnPercentTotal,
            totalUnpluggedPercentDrop = totalUnpluggedPercent,
            stepCount = stepCount,
            hasEnoughData = hasEnoughData,
        )
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 30_000L
        private const val MIN_CHARGE_DROP_UAH = 50_000
        private const val CHARGE_NOISE_UAH = 10_000
        private const val DEFAULT_CAPACITY_UAH = 5_600_000
    }
}
