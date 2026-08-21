package com.saplin.edrc

import java.util.regex.Pattern

data class CycleSnapshot(
    val startClock: String,
    val screenOnHours: Double,
    val screenOnMah: Double,
    val screenOffHours: Double,
    val screenOffMah: Double,
    val timeOnBatteryHours: Double,
    val capacityMah: Double,
    val onBattery: Boolean,
    val tsMs: Long,
) {
    val screenOnPercent: Double
        get() = percentOfPack(screenOnMah)

    val hasEnoughData: Boolean
        get() = screenOnPercent >= MIN_PERCENT

    val sotHoursPer100: Double?
        get() = hoursPerFull(screenOnHours, screenOnMah)

    val idlePercent: Double
        get() = percentOfPack(screenOffMah)

    val hasEnoughIdle: Boolean
        get() = idlePercent >= MIN_PERCENT

    val idleHoursPer100: Double?
        get() = hoursPerFull(screenOffHours, screenOffMah)

    private fun percentOfPack(mah: Double): Double =
        if (capacityMah > 0) mah / capacityMah * 100.0 else 0.0

    private fun hoursPerFull(hours: Double, mah: Double): Double? {
        val percent = percentOfPack(mah)
        return if (percent >= MIN_PERCENT && percent > 0) hours / (percent / 100.0) else null
    }

    fun toFrame(): Frame = Frame(
        tsMs = tsMs,
        startClock = startClock,
        screenOnHours = screenOnHours,
        screenOnMah = screenOnMah,
        screenOffHours = screenOffHours,
        screenOffMah = screenOffMah,
        timeOnBatteryHours = timeOnBatteryHours,
        capacityMah = capacityMah,
        onBattery = onBattery,
    )

    fun toMap(): Map<String, Any?> = mapOf(
        "startClock" to startClock,
        "screenOnHours" to screenOnHours,
        "drainMah" to screenOnMah,
        "screenOffHours" to screenOffHours,
        "screenOffMah" to screenOffMah,
        "timeOnBatteryHours" to timeOnBatteryHours,
        "capacityMah" to capacityMah,
        "onBattery" to onBattery,
        "sotHoursPer100" to sotHoursPer100,
        "hasEnoughData" to hasEnoughData,
        "idleHoursPer100" to idleHoursPer100,
        "hasEnoughIdle" to hasEnoughIdle,
        "tsMs" to tsMs,
    )

    companion object {
        const val MIN_PERCENT = 3.0
        const val DEFAULT_CAPACITY_MAH = 5600.0
    }
}

data class Frame(
    val tsMs: Long,
    val startClock: String,
    val screenOnHours: Double,
    val screenOnMah: Double,
    val screenOffHours: Double = 0.0,
    val screenOffMah: Double = 0.0,
    val timeOnBatteryHours: Double,
    val capacityMah: Double,
    val onBattery: Boolean,
) {
    fun toSnapshot(): CycleSnapshot = CycleSnapshot(
        startClock = startClock,
        screenOnHours = screenOnHours,
        screenOnMah = screenOnMah,
        screenOffHours = screenOffHours,
        screenOffMah = screenOffMah,
        timeOnBatteryHours = timeOnBatteryHours,
        capacityMah = capacityMah,
        onBattery = onBattery,
        tsMs = tsMs,
    )

    fun sameTotals(other: Frame): Boolean =
        startClock == other.startClock &&
            screenOnHours == other.screenOnHours &&
            screenOnMah == other.screenOnMah &&
            screenOffHours == other.screenOffHours &&
            screenOffMah == other.screenOffMah

    fun toMap(): Map<String, Any?> = mapOf(
        "tsMs" to tsMs,
        "startClock" to startClock,
        "screenOnHours" to screenOnHours,
        "drainMah" to screenOnMah,
        "timeOnBatteryHours" to timeOnBatteryHours,
        "capacityMah" to capacityMah,
        "onBattery" to onBattery,
    )
}

data class Interval(
    val fromTsMs: Long,
    val toTsMs: Long,
    val screenOnHours: Double,
    val drainMah: Double,
    val sotHoursPer100: Double?,
    val hasEnoughData: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "fromTsMs" to fromTsMs,
        "toTsMs" to toTsMs,
        "screenOnHours" to screenOnHours,
        "drainMah" to drainMah,
        "sotHoursPer100" to sotHoursPer100,
        "hasEnoughData" to hasEnoughData,
    )

    companion object {
        const val MIN_MAH = 100.0
    }
}

object BatterystatsParser {
    private val durationToken = Pattern.compile("(\\d+)(ms|d|h|m|s)")

    fun parseChargedHeader(dump: String, nowMs: Long): CycleSnapshot {
        val section = sectionAfter(dump, "Statistics since last charge:") ?: dump
        val capacity = firstDouble(section, """Estimated battery capacity:\s*([\d.]+)\s*mAh""")
            ?: CycleSnapshot.DEFAULT_CAPACITY_MAH
        val screenOnHours = firstDurationHours(section, """Screen on:\s*([^\n(]+)""") ?: 0.0
        val screenOnMah = firstDouble(section, """Screen on discharge:\s*([\d.]+)\s*mAh""") ?: 0.0
        val screenOffHours = firstDurationHours(section, """Time on battery screen off:\s*([^\n(]+)""") ?: 0.0
        val screenOffMah = firstDouble(section, """Screen off discharge:\s*([\d.]+)\s*mAh""") ?: 0.0
        val timeOnBatteryHours = firstDurationHours(section, """Time on battery:\s*([^\n(]+)""") ?: 0.0
        val startClock = firstString(section, """Start clock time:\s*(\S+)""") ?: ""
        val onBattery = firstString(section, """currently on battery:\s*(true|false)""") != "false"
        return CycleSnapshot(
            startClock = startClock,
            screenOnHours = screenOnHours,
            screenOnMah = screenOnMah,
            screenOffHours = screenOffHours,
            screenOffMah = screenOffMah,
            timeOnBatteryHours = timeOnBatteryHours,
            capacityMah = capacity,
            onBattery = onBattery,
            tsMs = nowMs,
        )
    }

    internal fun parseDurationToHours(raw: String): Double {
        val matcher = durationToken.matcher(raw.replace(" ", ""))
        var ms = 0L
        while (matcher.find()) {
            val n = matcher.group(1)?.toLongOrNull() ?: continue
            ms += when (matcher.group(2)) {
                "d" -> n * 24L * 3_600_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1_000L
                "ms" -> n
                else -> 0L
            }
        }
        return ms / 3_600_000.0
    }

    private fun sectionAfter(text: String, header: String): String? {
        val idx = text.indexOf(header)
        if (idx < 0) return null
        return text.substring(idx)
    }

    private fun firstDouble(text: String, regex: String): Double? {
        val m = Pattern.compile(regex).matcher(text)
        return if (m.find()) m.group(1)?.toDoubleOrNull() else null
    }

    private fun firstString(text: String, regex: String): String? {
        val m = Pattern.compile(regex).matcher(text)
        return if (m.find()) m.group(1) else null
    }

    private fun firstDurationHours(text: String, regex: String): Double? {
        val m = Pattern.compile(regex).matcher(text)
        if (!m.find()) return null
        return parseDurationToHours(m.group(1) ?: return null)
    }
}
