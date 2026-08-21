package com.saplin.edrc

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayList

object FrameStore {
    private val framesFile = File("/data/local/tmp/com.saplin.edrc.frames.jsonl")
    private val lastCycleFile = File("/data/local/tmp/com.saplin.edrc.last_cycle.json")
    private val pastFile = File("/data/local/tmp/com.saplin.edrc.past.jsonl")

    fun load(): FrameLogState {
        val frames = mutableListOf<Frame>()
        if (framesFile.exists()) {
            framesFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { frames.add(frameFromJson(JSONObject(line))) }
            }
        }
        val past = mutableListOf<CycleSnapshot>()
        if (pastFile.exists()) {
            pastFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching { past.add(snapshotFromJson(JSONObject(line))) }
            }
        } else if (lastCycleFile.exists()) {
            runCatching { past.add(snapshotFromJson(JSONObject(lastCycleFile.readText()))) }
        }
        return FrameLogState(frames.takeLast(FrameLog.MAX_FRAMES), past.takeLast(FrameLog.MAX_PAST))
    }

    fun save(state: FrameLogState) {
        framesFile.parentFile?.mkdirs()
        framesFile.writeText(
            state.frames.joinToString("\n") { frameToJson(it).toString() } +
                if (state.frames.isEmpty()) "" else "\n",
        )
        pastFile.writeText(
            state.past.joinToString("\n") { snapshotToJson(it).toString() } +
                if (state.past.isEmpty()) "" else "\n",
        )
        val last = state.lastCycle
        if (last != null) {
            lastCycleFile.writeText(snapshotToJson(last).toString())
        }
    }

    fun stateToJson(state: FrameLogState): String {
        val cycle = state.currentCycle()
        val intervals = state.currentIntervals()
        val obj = JSONObject()
        if (cycle != null) obj.put("cycle", snapshotToJson(cycle)) else obj.put("cycle", JSONObject.NULL)
        val last = state.lastCycle
        if (last != null) {
            obj.put("lastCycle", snapshotToJson(last))
        } else {
            obj.put("lastCycle", JSONObject.NULL)
        }
        val frameArr = JSONArray()
        state.frames.filter { it.startClock == state.currentClock }.forEach {
            frameArr.put(frameToJson(it))
        }
        obj.put("frames", frameArr)
        val useful = intervals.filter { it.hasEnoughData }.asReversed().take(5)
        val intervalArr = JSONArray()
        useful.forEach { intervalArr.put(intervalToJson(it)) }
        obj.put("intervals", intervalArr)
        val pastArr = JSONArray()
        state.past.asReversed().forEach { pastArr.put(snapshotToJson(it)) }
        obj.put("past", pastArr)
        obj.put("lastFrameAtMs", state.lastFrameAtMs ?: JSONObject.NULL)
        return obj.toString()
    }

    fun jsonToChannelMap(raw: String): Map<String, Any?> {
        val obj = JSONObject(raw)
        val cycleObj = obj.optJSONObject("cycle")
        val lastObj = obj.optJSONObject("lastCycle")
        val pastArr = obj.optJSONArray("past")
        val intervalsArr = obj.optJSONArray("intervals")
        val past = if (pastArr != null && pastArr.length() > 0) {
            ArrayList(
                (0 until pastArr.length()).map { i ->
                    snapshotFromJson(pastArr.getJSONObject(i)).toMap()
                },
            )
        } else if (lastObj != null) {
            arrayListOf(snapshotFromJson(lastObj).toMap())
        } else {
            ArrayList<Map<String, Any?>>()
        }
        return mapOf(
            "cycle" to cycleObj?.let { snapshotFromJson(it).toMap() },
            "past" to past,
            "intervals" to ArrayList(
                (0 until (intervalsArr?.length() ?: 0)).map { i ->
                    intervalFromJson(intervalsArr!!.getJSONObject(i)).toMap()
                },
            ),
            "updatedAtMs" to if (obj.isNull("lastFrameAtMs")) null else obj.optLong("lastFrameAtMs"),
        )
    }

    private fun intervalFromJson(obj: JSONObject) = Interval(
        fromTsMs = obj.getLong("fromTsMs"),
        toTsMs = obj.getLong("toTsMs"),
        screenOnHours = obj.getDouble("screenOnHours"),
        drainMah = obj.getDouble("drainMah"),
        sotHoursPer100 = if (obj.isNull("sotHoursPer100")) null else obj.getDouble("sotHoursPer100"),
        hasEnoughData = obj.optBoolean("hasEnoughData", false),
    )

    private fun frameToJson(frame: Frame) = JSONObject()
        .put("tsMs", frame.tsMs)
        .put("startClock", frame.startClock)
        .put("screenOnHours", frame.screenOnHours)
        .put("screenOnMah", frame.screenOnMah)
        .put("screenOffHours", frame.screenOffHours)
        .put("screenOffMah", frame.screenOffMah)
        .put("timeOnBatteryHours", frame.timeOnBatteryHours)
        .put("capacityMah", frame.capacityMah)
        .put("onBattery", frame.onBattery)

    private fun snapshotToJson(snap: CycleSnapshot) = JSONObject()
        .put("tsMs", snap.tsMs)
        .put("startClock", snap.startClock)
        .put("screenOnHours", snap.screenOnHours)
        .put("screenOnMah", snap.screenOnMah)
        .put("screenOffHours", snap.screenOffHours)
        .put("screenOffMah", snap.screenOffMah)
        .put("timeOnBatteryHours", snap.timeOnBatteryHours)
        .put("capacityMah", snap.capacityMah)
        .put("onBattery", snap.onBattery)

    private fun intervalToJson(interval: Interval) = JSONObject()
        .put("fromTsMs", interval.fromTsMs)
        .put("toTsMs", interval.toTsMs)
        .put("screenOnHours", interval.screenOnHours)
        .put("drainMah", interval.drainMah)
        .put("sotHoursPer100", interval.sotHoursPer100 ?: JSONObject.NULL)
        .put("hasEnoughData", interval.hasEnoughData)

    private fun frameFromJson(obj: JSONObject) = Frame(
        tsMs = obj.getLong("tsMs"),
        startClock = obj.getString("startClock"),
        screenOnHours = obj.getDouble("screenOnHours"),
        screenOnMah = obj.getDouble("screenOnMah"),
        screenOffHours = obj.optDouble("screenOffHours", 0.0),
        screenOffMah = obj.optDouble("screenOffMah", 0.0),
        timeOnBatteryHours = obj.optDouble("timeOnBatteryHours", 0.0),
        capacityMah = obj.optDouble("capacityMah", CycleSnapshot.DEFAULT_CAPACITY_MAH),
        onBattery = obj.optBoolean("onBattery", true),
    )

    private fun snapshotFromJson(obj: JSONObject) = CycleSnapshot(
        startClock = obj.getString("startClock"),
        screenOnHours = obj.getDouble("screenOnHours"),
        screenOnMah = obj.getDouble("screenOnMah"),
        screenOffHours = obj.optDouble("screenOffHours", 0.0),
        screenOffMah = obj.optDouble("screenOffMah", 0.0),
        timeOnBatteryHours = obj.optDouble("timeOnBatteryHours", 0.0),
        capacityMah = obj.optDouble("capacityMah", CycleSnapshot.DEFAULT_CAPACITY_MAH),
        onBattery = obj.optBoolean("onBattery", true),
        tsMs = obj.getLong("tsMs"),
    )
}
