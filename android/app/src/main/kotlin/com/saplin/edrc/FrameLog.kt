package com.saplin.edrc

data class FrameLogState(
    val frames: List<Frame>,
    val past: List<CycleSnapshot>,
) {
    val lastFrame: Frame? get() = frames.lastOrNull()
    val lastFrameAtMs: Long? get() = lastFrame?.tsMs
    val currentClock: String? get() = lastFrame?.startClock
    val lastCycle: CycleSnapshot? get() = past.lastOrNull()

    fun currentCycle(): CycleSnapshot? = lastFrame?.toSnapshot()

    fun currentIntervals(): List<Interval> {
        val clock = currentClock ?: return emptyList()
        return FrameLog.intervals(frames.filter { it.startClock == clock })
    }
}

object FrameLog {
    const val MAX_FRAMES = 72
    const val MAX_PAST = 7

    fun append(state: FrameLogState, frame: Frame): FrameLogState {
        val last = state.frames.lastOrNull()
        if (last != null && last.startClock != frame.startClock) {
            val closed = last.toSnapshot()
            val next = (state.frames + frame).takeLast(MAX_FRAMES)
            val past = (state.past + closed).takeLast(MAX_PAST)
            return FrameLogState(next, past)
        }
        if (last != null && last.sameTotals(frame)) {
            val replaced = state.frames.dropLast(1) + frame
            return FrameLogState(replaced, state.past)
        }
        return FrameLogState((state.frames + frame).takeLast(MAX_FRAMES), state.past)
    }

    fun intervals(frames: List<Frame>): List<Interval> {
        if (frames.size < 2) return emptyList()
        val out = mutableListOf<Interval>()
        for (i in 1 until frames.size) {
            val a = frames[i - 1]
            val b = frames[i]
            if (a.startClock != b.startClock) continue
            val dOn = b.screenOnHours - a.screenOnHours
            val dMah = b.screenOnMah - a.screenOnMah
            if (dOn < 0 || dMah < 0) continue
            val enough = dMah >= Interval.MIN_MAH
            val sot = if (enough && dMah > 0 && b.capacityMah > 0) {
                dOn / (dMah / b.capacityMah)
            } else {
                null
            }
            out.add(
                Interval(
                    fromTsMs = a.tsMs,
                    toTsMs = b.tsMs,
                    screenOnHours = dOn,
                    drainMah = dMah,
                    sotHoursPer100 = sot,
                    hasEnoughData = enough,
                ),
            )
        }
        return out
    }
}
