package com.saplin.edrc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class DumpService() : IDumpService.Stub() {
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val collectMutex = Any()
    private var state = FrameLogState(emptyList(), emptyList())
    private var started = false

    constructor(context: Context) : this() {
        appContext = context.applicationContext
        startLocked()
    }

    private val tick = object : Runnable {
        override fun run() {
            Thread({
                try {
                    collectFrame(false)
                } catch (e: Exception) {
                    Log.w(TAG, "scheduled collect failed", e)
                }
                handler.postDelayed(this, INTERVAL_MS)
            }, "dump-collect").start()
        }
    }

    @Synchronized
    private fun startLocked() {
        if (started) return
        started = true
        synchronized(lock) {
            state = FrameStore.load()
        }
        handler.post {
            Thread({
                try {
                    collectFrame(true)
                } catch (e: Exception) {
                    Log.w(TAG, "initial collect failed", e)
                }
                handler.postDelayed(tick, INTERVAL_MS)
            }, "dump-collect").start()
        }
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        System.exit(0)
    }

    override fun getStateJson(): String {
        if (!started) startLocked()
        synchronized(lock) {
            return FrameStore.stateToJson(state)
        }
    }

    override fun collectFrame(force: Boolean): String {
        if (!started) startLocked()
        synchronized(collectMutex) {
            synchronized(lock) {
                val lastAt = state.lastFrameAtMs
                val now = System.currentTimeMillis()
                val gap = if (force) FORCE_GAP_MS else MIN_GAP_MS
                if (lastAt != null && now - lastAt < gap) {
                    Log.i(TAG, "collectFrame skipped (${now - lastAt}ms since last)")
                    return FrameStore.stateToJson(state)
                }
            }
            val dump = dumpChargedHeader()
            val snap = BatterystatsParser.parseChargedHeader(dump, System.currentTimeMillis())
            synchronized(lock) {
                state = FrameLog.append(state, snap.toFrame())
                FrameStore.save(state)
                Log.i(TAG, "collectFrame clock=${snap.startClock} on=${snap.screenOnHours} mah=${snap.screenOnMah}")
                return FrameStore.stateToJson(state)
            }
        }
    }

    private fun dumpChargedHeader(): String {
        val context = appContext
        val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edrc:dump")
        wake?.setReferenceCounted(false)
        wake?.acquire(30_000)
        try {
            Log.i(TAG, "dumpsys batterystats --charged")
            val process = ProcessBuilder("dumpsys", "batterystats", "--charged")
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            var inSection = false
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains("Statistics since last charge:")) inSection = true
                    if (!inSection) continue
                    sb.appendLine(line)
                    val trimmed = line.trim()
                    if (trimmed.startsWith("Screen on:") &&
                        !trimmed.startsWith("Screen on discharge") &&
                        sb.contains("Screen on discharge:")
                    ) {
                        break
                    }
                    if (sb.length > 32_000) break
                }
            }
            process.destroyForcibly()
            Log.i(TAG, "header ${sb.length} chars")
            return sb.toString()
        } finally {
            if (wake?.isHeld == true) wake.release()
        }
    }

    companion object {
        private const val TAG = "DumpService"
        private const val INTERVAL_MS = 60L * 60L * 1000L
        private const val MIN_GAP_MS = 2L * 60L * 1000L
        private const val FORCE_GAP_MS = 10L * 1000L
    }
}
